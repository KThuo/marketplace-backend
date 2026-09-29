package com.hodi.seed;

import com.hodi.common.AppConstant;
import com.hodi.common.EncryptionUtil;
import com.hodi.enums.AppModuleEnum;
import com.hodi.enums.AppPermissionEnum;
import com.hodi.enums.ConfigKey;
import com.hodi.enums.UserTypeEnum;
import com.hodi.modules.appmodules.AppModule;
import com.hodi.modules.appmodules.AppModuleRepository;
import com.hodi.modules.configurations.Configuration;
import com.hodi.modules.configurations.ConfigurationRepository;
import com.hodi.modules.permissions.Permission;
import com.hodi.modules.permissions.PermissionRepository;
import com.hodi.modules.usergroups.UserGroup;
import com.hodi.modules.usergroups.UserGroupRepository;
import com.hodi.modules.profiles.UserProfileService;
import com.hodi.modules.tenantmodules.TenantModuleService;
import com.hodi.modules.tenants.Tenant;
import com.hodi.modules.tenants.TenantRepository;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.modules.usertypes.UserType;
import com.hodi.modules.usertypes.UserTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Reconciles the database against the enums, on every boot.
 *
 * <p><strong>Reconcile, not insert.</strong> Every step below is idempotent and repairs drift rather than
 * assuming a clean database: it is what makes "add a permission to the enum and restart" a complete
 * deployment step, and it is why the seeder can be left enabled in production. A seeder that only inserted on
 * an empty database would mean every new permission needed a hand-written migration, and the enum would
 * quietly stop being the source of truth.
 *
 * <p>What it deliberately does <em>not</em> touch: values a human has changed. Configuration rows keep their
 * value once seeded — only the metadata around them (label, description, flags) is refreshed — because
 * overwriting an operator's tuned session timeout on every restart would make the settings screen a lie.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeederService {

    private static final String ACTOR = AppConstant.USERNAME_SEEDER;
    private static final String PLATFORM_GROUP = "Platform Super Admin";
    private static final String BUYER_GROUP = "Buyer";
    private static final String VALUER_GROUP = "Valuer";
    private static final String AGENT_GROUP = "Property Agent";
    private static final String VENDOR_GROUP = "Vendor";

    private final UserTypeRepository userTypes;
    private final com.hodi.modules.consent.ConsentService consent;
    private final AppModuleRepository appModules;
    private final PermissionRepository permissions;
    private final UserGroupRepository userGroups;
    private final UserRepository users;
    private final UserProfileService userProfiles;
    private final ConfigurationRepository configurations;
    private final TenantRepository tenants;
    private final com.hodi.modules.configurations.ConfigurationCache configCache;
    private final TenantModuleService tenantModules;
    private final PasswordEncoder passwordEncoder;
    private final EncryptionUtil encryption;
    /** Starts the seed's transaction, because a self-invocation cannot — see {@link #seedOnBoot}. */
    private final TransactionTemplate newTransaction;

    @Value("${hodi.seed.enabled:true}")
    private boolean enabled;

    @Value("${hodi.seed.bootstrap-username:superadmin}")
    private String bootstrapUsername;

    @Value("${hodi.seed.bootstrap-email:admin@hodi.local}")
    private String bootstrapEmail;

    @Value("${hodi.seed.bootstrap-password:ChangeMe#2026}")
    private String bootstrapPassword;

    /**
     * Runs after the context is ready rather than in {@code @PostConstruct}.
     *
     * <p>It needs a working transaction manager, a migrated schema and the password encoder — all of which
     * are only guaranteed by then. Failures are logged and swallowed: a seeder that cannot reconcile should
     * not stop an application that is otherwise able to serve, and the log line is what brings somebody to
     * look.
     *
     * <h2>Why the transaction is started here, explicitly</h2>
     *
     * <p>Because {@code seed()} is a method on this same bean, and Spring's {@code @Transactional} is
     * proxy-based: a self-invocation does not pass through the proxy, so the annotation on {@code seed()}
     * was silently doing nothing. Every repository call then ran in its own transaction, which mostly
     * looked fine — the seeder is idempotent, so the individual writes still landed — until a step read an
     * entity in one transaction and touched it in another, at which point it failed with
     * <em>"Session/EntityManager is closed"</em>. That symptom depended on which branch each step took, so
     * it appeared on a database that was already seeded and not on a fresh one.
     *
     * <p>Reconciling the whole catalogue is one logical unit and has to be atomic: a half-applied seed
     * leaves permissions without the modules they name. Hence the explicit template.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedOnBoot() {
        if (!enabled) {
            log.info("Seeder is disabled (hodi.seed.enabled=false)");
            return;
        }
        try {
            newTransaction.executeWithoutResult(status -> seed());
        } catch (RuntimeException e) {
            log.error("Seeding failed — the platform may be missing catalogue rows", e);
        }
    }

    /**
     * Reconciles everything.
     *
     * <p>{@code @Transactional} is kept for the benefit of callers that reach this through the proxy — a
     * test, or a future admin endpoint — where it does apply. {@link #seedOnBoot} cannot rely on it.
     */
    @Transactional
    public void seed() {
        /*
         * The configuration cache is emptied first.
         *
         * Values live in Redis, which outlives a restart — so a migration that corrects a configuration value
         * behind the application's back leaves the old one being served indefinitely. That happened once, to
         * `storage.local.base.url`: the migration fixed the row, the cache kept the broken value, and every
         * photograph stayed a broken image across two restarts.
         *
         * Emptying it on boot costs one round trip and a handful of misses. Serving a value the database no
         * longer holds costs however long it takes somebody to think of Redis.
         */
        configCache.evictAllGlobal();

        int types = seedUserTypes();
        int modules = seedAppModules();
        int perms = seedPermissions();
        int configs = seedConfigurations();
        int templates = seedRoleTemplates();
        int platform = topUpPlatformGroup();
        int buyer = topUpBuyerGroup();
        int valuerPerms = topUpValuerGroup();
        int agentPerms = topUpAgentGroup();
        int vendorPerms = topUpVendorGroup();
        int owners = topUpOrganisationOwnerGroups();
        int enabled = enableCoreModulesEverywhere();
        boolean bootstrapped = seedBootstrapAdmin();

        log.info("Seeder: {} user types, {} modules, {} permissions, {} configs, {} templates "
                        + "(+{} platform, +{} buyer, +{} valuer, +{} agent, +{} vendor, "
                        + "+{} owner perms, +{} tenant modules){}",
                types, modules, perms, configs, templates, platform, buyer, valuerPerms, agentPerms,
                vendorPerms, owners, enabled,
                bootstrapped ? ", bootstrap admin created" : "");
    }

    // ── user types ────────────────────────────────────────────────────────────

    private int seedUserTypes() {
        int touched = 0;
        for (UserTypeEnum e : UserTypeEnum.values()) {
            UserType held = userTypes.findByCode(e.name()).orElse(null);
            if (held == null) {
                userTypes.save(UserType.builder()
                        .code(e.name())
                        .name(e.getDisplayName())
                        .description(e.getDescription())
                        .actorClass(e.getActorClass())
                        .sortOrder(e.getSortOrder())
                        .status(AppConstant.STATUS_ACTIVE)
                        .statusFlag(AppConstant.FLAG_ACTIVE)
                        .createdBy(ACTOR)
                        .build());
                touched++;
                continue;
            }
            /*
             * Names, descriptions and ordering are refreshed; actor_class is not.
             *
             * A type's actor class decides which organisation column its holders carry and how their visible
             * tenants resolve. Rewriting it from the enum would silently reclassify live users if somebody
             * edited the enum — so a change there is a migration, and the mismatch is logged loudly rather
             * than applied.
             */
            if (!e.getActorClass().equals(held.getActorClass())) {
                log.error("User type {} is {} in the database but {} in the enum — NOT changed. "
                                + "Moving a type between actor classes needs a migration.",
                        e.name(), held.getActorClass(), e.getActorClass());
            }
            boolean dirty = false;
            if (!e.getDisplayName().equals(held.getName())) {
                held.setName(e.getDisplayName());
                dirty = true;
            }
            if (!e.getDescription().equals(held.getDescription())) {
                held.setDescription(e.getDescription());
                dirty = true;
            }
            if (held.getSortOrder() == null || held.getSortOrder() != e.getSortOrder()) {
                held.setSortOrder(e.getSortOrder());
                dirty = true;
            }
            if (dirty) {
                held.setUpdatedBy(ACTOR);
                userTypes.save(held);
                touched++;
            }
        }
        return touched;
    }

    // ── app modules ───────────────────────────────────────────────────────────

    private int seedAppModules() {
        int touched = 0;
        for (AppModuleEnum e : AppModuleEnum.values()) {
            AppModule held = appModules.findByCode(e.getCode()).orElse(null);
            if (held == null) {
                appModules.save(AppModule.builder()
                        .code(e.getCode())
                        .name(e.getDisplayName())
                        .description(e.getDescription())
                        .allowedUserTypes(e.getAllowedUserTypes())
                        .core(e.isCore())
                        .sortOrder(e.getSortOrder())
                        .status(AppConstant.STATUS_ACTIVE)
                        .statusFlag(AppConstant.FLAG_ACTIVE)
                        .createdBy(ACTOR)
                        .build());
                touched++;
                continue;
            }
            /*
             * allowed_user_types is NOT overwritten once the row exists.
             *
             * It is the field the super admin edits at runtime — access axis (1) — so rewriting it from the
             * enum on every boot would silently undo their configuration at the next deploy. The enum value
             * is the seeded default and nothing more. Everything else about the module is derived and safe to
             * refresh.
             */
            boolean dirty = false;
            if (!e.getDisplayName().equals(held.getName())) {
                held.setName(e.getDisplayName());
                dirty = true;
            }
            if (!e.getDescription().equals(held.getDescription())) {
                held.setDescription(e.getDescription());
                dirty = true;
            }
            if (held.isCore() != e.isCore()) {
                held.setCore(e.isCore());
                dirty = true;
            }
            if (held.getSortOrder() == null || held.getSortOrder() != e.getSortOrder()) {
                held.setSortOrder(e.getSortOrder());
                dirty = true;
            }
            if (dirty) {
                held.setUpdatedBy(ACTOR);
                appModules.save(held);
                touched++;
            }
        }
        return touched;
    }

    // ── permissions ───────────────────────────────────────────────────────────

    private int seedPermissions() {
        Map<String, AppModule> modulesByCode = appModules.findAll().stream()
                .collect(Collectors.toMap(AppModule::getCode, Function.identity(), (a, b) -> a));

        int touched = 0;
        for (AppPermissionEnum e : AppPermissionEnum.values()) {
            AppModule module = modulesByCode.get(e.getModule().getCode());
            if (module == null) {
                log.error("Permission {} names module {}, which does not exist — skipped",
                        e.getActionCode(), e.getModule().getCode());
                continue;
            }
            Permission held = permissions.findByActionCode(e.getActionCode()).orElse(null);
            if (held == null) {
                permissions.save(Permission.builder()
                        .actionCode(e.getActionCode())
                        .actionName(e.getActionName())
                        .appModuleId(module.getId())
                        .moduleCode(module.getCode())
                        .moduleName(module.getName())
                        .platformOnly(e.isPlatformOnly())
                        .status(AppConstant.STATUS_ACTIVE)
                        .statusFlag(AppConstant.FLAG_ACTIVE)
                        .createdBy(ACTOR)
                        .build());
                touched++;
                continue;
            }
            /*
             * Every derived field is reconciled, and platform_only in particular.
             *
             * If a permission is reclassified as platform-only in the enum, that has to reach the database on
             * the next boot — both the role templates and the organisation system-group top-up read the
             * column, so a stale `false` there would keep handing the permission to organisations.
             */
            boolean dirty = false;
            if (!e.getActionName().equals(held.getActionName())) {
                held.setActionName(e.getActionName());
                dirty = true;
            }
            if (!module.getId().equals(held.getAppModuleId())) {
                held.setAppModuleId(module.getId());
                held.setModuleCode(module.getCode());
                dirty = true;
            }
            if (!module.getName().equals(held.getModuleName())) {
                held.setModuleName(module.getName());
                dirty = true;
            }
            if (held.isPlatformOnly() != e.isPlatformOnly()) {
                log.warn("Permission {} platform_only {} -> {}", e.getActionCode(),
                        held.isPlatformOnly(), e.isPlatformOnly());
                held.setPlatformOnly(e.isPlatformOnly());
                dirty = true;
            }
            if (dirty) {
                held.setUpdatedBy(ACTOR);
                permissions.save(held);
                touched++;
            }
        }
        return touched;
    }

    // ── configurations ────────────────────────────────────────────────────────

    private int seedConfigurations() {
        int touched = 0;
        for (ConfigKey key : ConfigKey.values()) {
            Configuration held = configurations.findByConfigKey(key.getKey()).orElse(null);
            if (held == null) {
                String value = key.isSecret() && !key.getDefaultValue().isBlank()
                        ? encryption.encryptString(key.getDefaultValue())
                        : key.getDefaultValue();
                configurations.save(Configuration.builder()
                        .configKey(key.getKey())
                        .configValue(value)
                        .valueType(key.getValueType())
                        .category(key.getCategory())
                        .label(key.getLabel())
                        .description(key.getDescription())
                        .secret(key.isSecret())
                        .overridable(key.isOverridable())
                        .editable(true)
                        .status(AppConstant.STATUS_ACTIVE)
                        .statusFlag(AppConstant.FLAG_ACTIVE)
                        .createdBy(ACTOR)
                        .build());
                touched++;
                continue;
            }
            /*
             * The VALUE is never touched. Only the metadata around it.
             *
             * Somebody has tuned these through the settings screen; rewriting them from the enum's defaults
             * on every restart would make the screen a lie and would silently reset a hardened session
             * timeout at the next deploy. The label, description and the two flags are code-owned and are
             * refreshed — is_overridable especially, since narrowing it is how a key stops being shadowable
             * and that has to take effect.
             */
            boolean dirty = false;
            if (!key.getLabel().equals(held.getLabel())) {
                held.setLabel(key.getLabel());
                dirty = true;
            }
            if (!key.getDescription().equals(held.getDescription())) {
                held.setDescription(key.getDescription());
                dirty = true;
            }
            if (!key.getCategory().equals(held.getCategory())) {
                held.setCategory(key.getCategory());
                dirty = true;
            }
            if (held.isSecret() != key.isSecret()) {
                log.warn("Config {} secret {} -> {}", key.getKey(), held.isSecret(), key.isSecret());
                held.setSecret(key.isSecret());
                dirty = true;
            }
            if (held.isOverridable() != key.isOverridable()) {
                log.warn("Config {} overridable {} -> {}", key.getKey(),
                        held.isOverridable(), key.isOverridable());
                held.setOverridable(key.isOverridable());
                dirty = true;
            }
            if (dirty) {
                held.setUpdatedBy(ACTOR);
                configurations.save(held);
                touched++;
            }
        }
        return touched;
    }

    // ── global role templates ─────────────────────────────────────────────────

    /**
     * The cloneable starting points, one per non-owner staff type.
     *
     * <p>Deliberately not "every permission the type could hold": a template is a <em>sensible default</em>
     * that an organisation clones and then narrows or widens. A listing manager template carrying user
     * administration would mean every organisation's first act is taking permissions away, which is the wrong
     * default for the same reason least privilege is a principle.
     *
     * <p>Permissions are named by code rather than derived from modules, so what each template grants is
     * readable here rather than being an emergent property of the module matrix.
     */
    private int seedRoleTemplates() {
        Map<String, List<String>> byType = new LinkedHashMap<>();
        /*
         * Every code here must be one its user type can actually hold — i.e. in a module whose
         * allowed_user_types admits that type. A template granting more than that is not merely useless: the
         * resolver drops the extra authorities at login, so the group reads as granted and behaves as empty,
         * and cloning it fails outright because UserGroupService refuses permissions the type cannot hold.
         * The first version gave the seller agents TENANT_SELF_VIEW, whose module did not admit them, and the
         * clone endpoint was where it surfaced.
         */
        /*
         * A listing manager manages listings, and for a long time this template did not say so.
         *
         * It granted the leads, the site visits and the payments a manager reads — and not one
         * PROPERTIES_*, DEVELOPMENTS_* or UNITS_* code. Every organisation that cloned it got a group whose
         * name promised the job and whose permissions refused it: the listings screen answered "Access
         * denied", and a project whose units had just been generated showed no Units tab, because the button
         * is drawn on UNITS_VIEW. The gap read as a platform-wide authorisation fault, which is what it was
         * reported as.
         *
         * So the verbs of the role are here now, and the line the template draws is between doing the work
         * and deciding it. Create, edit, photograph, submit and withdraw are the work. APPROVE, DELETE and
         * MARK_SOLD are decisions an organisation grants on purpose, to somebody chosen, and they stay out —
         * as do PAYMENTS_RECEIVE and PURCHASE_REQUESTS_DECIDE, which move money and close sales.
         *
         * Least privilege is still the principle. What changed is the reading of it: a default that cannot
         * perform the role is not a conservative default, it is a broken one, and every organisation's first
         * act was granting back the permissions the job is made of.
         */
        byType.put("LISTING_MANAGER", List.of(
                "DASHBOARD_VIEW",
                // The listings themselves — through submission, not through approval.
                "PROPERTIES_VIEW", "PROPERTIES_CREATE", "PROPERTIES_UPDATE", "PROPERTIES_MEDIA",
                "PROPERTIES_SUBMIT", "PROPERTIES_WITHDRAW",
                // A project and its inventory. UNITS_MANAGE is what generates them; UNITS_SELL reserves and
                // sells one, which is the sales side of the house.
                "DEVELOPMENTS_VIEW", "DEVELOPMENTS_CREATE", "DEVELOPMENTS_UPDATE", "DEVELOPMENTS_MEDIA",
                "DEVELOPMENTS_PHASES", "DEVELOPMENTS_PROGRESS", "DEVELOPMENTS_SUBMIT",
                "DEVELOPMENTS_WITHDRAW", "UNITS_VIEW", "UNITS_MANAGE",
                // The leads a listing manager works day to day (M4). Deciding an offer is not here: it is
                // the most consequential thing a seller organisation grants, and a default template should
                // not hand it to everybody who can edit a listing.
                "ENQUIRIES_VIEW", "ENQUIRIES_REPLY", "ENQUIRIES_ASSIGN", "ENQUIRIES_CLOSE",
                "SITE_VISITS_VIEW", "SITE_VISITS_DECIDE", "SITE_VISITS_COMPLETE",
                "PURCHASE_REQUESTS_VIEW", "BOOKINGS_VIEW",
                "CALENDAR_VIEW", "CALENDAR_MANAGE",
                "RATINGS_VIEW", "RATINGS_REPLY",
                "APPROVALS_VIEW", "PROMOTIONS_VIEW", "PROMOTIONS_REQUEST",
                // Reading payments, not recording them: a manager checks what a buyer has paid; who may
                // write it down is the organisation's decision, made by granting PAYMENTS_RECEIVE on
                // purpose.
                "PAYMENTS_VIEW", "PAYMENT_TYPES_VIEW", "DEVELOPMENTS_FINANCE_VIEW", "SETTLEMENTS_VIEW"));
        /*
         * An agent answers questions and shows people round — so they read the stock and write nothing about
         * it. The listings, projects and units are view-only here, which is the difference between this
         * template and the one above; without them an agent could not look up the flat the person on the
         * phone is asking about.
         *
         * The offers module does not admit this type at all, so PURCHASE_REQUESTS_VIEW here would be a
         * permission their user type could never hold.
         */
        byType.put("SALES_AGENT", List.of(
                "DASHBOARD_VIEW",
                "PROPERTIES_VIEW", "DEVELOPMENTS_VIEW", "UNITS_VIEW", "BOOKINGS_VIEW",
                "ENQUIRIES_VIEW", "ENQUIRIES_REPLY",
                "SITE_VISITS_VIEW", "SITE_VISITS_DECIDE", "SITE_VISITS_COMPLETE",
                "CALENDAR_VIEW", "CALENDAR_MANAGE", "PAYMENTS_VIEW"));
        // Reading the catalogue, not writing it. An officer quoting a rate to a buyer needs to see the
        // products; changing one is the administrator's, and publishing one is separate again.
        // A valuer's whole world: their own panel row, and the jobs assigned to them.
        byType.put("VALUER", List.of(
                "VALUER_PANEL_VIEW", "VALUATIONS_VIEW", "VALUATIONS_WORK"));
        /*
         * A bank's staff read the money on the projects the bank financed: that is the exposure they watch.
         *
         * The projects themselves are here now. Reading a drawdown figure without being able to open the
         * project it belongs to, see its units or its bookings, is a number with nothing behind it — and
         * DEVELOPMENTS_FINANCE_VIEW without DEVELOPMENTS_VIEW meant the finance screen 403'd on the way in.
         *
         * Still entirely read-only: nothing here writes, and the bank writing on a seller's project would be
         * a decision an organisation grants deliberately rather than inherits from a default.
         */
        List<String> bankStaff = List.of(
                "DASHBOARD_VIEW",
                "MORTGAGE_PRODUCTS_VIEW", "PAYMENTS_VIEW", "PAYMENT_TYPES_VIEW",
                "DEVELOPMENTS_VIEW", "UNITS_VIEW", "BOOKINGS_VIEW", "APPROVALS_VIEW",
                "DEVELOPMENTS_FINANCE_VIEW");
        byType.put("MORTGAGE_OFFICER", bankStaff);
        byType.put("CREDIT_ANALYST", bankStaff);
        // No AUDIT_VIEW: the audit trail is what distinguishes PLATFORM_AUDITOR from support, and the AUDIT
        // module does not admit SUPPORT_ADMIN — so granting it here produced a template naming a permission
        // its own user type could never hold, which surfaced as "these permissions are not available for this
        // kind of user" the first time anybody tried to clone it.
        // KYC_VIEW without KYC_REVIEW: support can see where an organisation stands, which is what a
        // "why can I not list?" call needs, without being able to decide it or open the documents.
        /*
         * Support answers "why can I not do this?", so support has to be able to look at the thing being
         * asked about. Both of these templates are read-only and were merely too narrow to do the job:
         * neither could open a listing, a project or a unit, which is most of what a support call is about.
         *
         * Every code here is a _VIEW. That is the rule for both roles, and it is what makes widening them
         * safe — a support administrator who can see everything and change nothing cannot make a bad
         * afternoon worse. Writes stay with the organisation that owns the row.
         */
        List<String> platformReadOnly = List.of(
                "DASHBOARD_VIEW", "TENANTS_VIEW",
                "PROPERTIES_VIEW", "DEVELOPMENTS_VIEW", "UNITS_VIEW", "BOOKINGS_VIEW",
                "ENQUIRIES_VIEW", "SITE_VISITS_VIEW", "PURCHASE_REQUESTS_VIEW",
                "APPROVALS_VIEW", "RATINGS_VIEW", "AGENTS_VIEW", "VENDORS_VIEW",
                "AUCTIONS_VIEW", "AUCTIONEERS_VIEW", "COMMISSIONS_VIEW", "SETTLEMENTS_VIEW",
                "MORTGAGE_PRODUCTS_VIEW", "VALUATIONS_VIEW", "VALUER_PANEL_VIEW",
                "KYC_VIEW", "PAYMENTS_VIEW", "PAYMENT_TYPES_VIEW", "DEVELOPMENTS_FINANCE_VIEW");

        // USERS_VIEW and the property-type catalogue on top: support resolves "who is this person" and
        // "why is that type not on the form", and neither is the auditor's question.
        List<String> supportAdmin = new ArrayList<>(platformReadOnly);
        supportAdmin.addAll(List.of("USERS_VIEW", "ASSIGNMENT_VIEW", "PROPERTY_TYPES_VIEW",
                "CALENDAR_VIEW"));
        byType.put("SUPPORT_ADMIN", supportAdmin);

        // The trail itself, plus affordability, which is a record of a decision rather than a live screen.
        // AUDIT is admitted to this type and to no other non-platform one — it is what the role is for.
        List<String> platformAuditor = new ArrayList<>(platformReadOnly);
        platformAuditor.addAll(List.of("AUDIT_VIEW", "AFFORDABILITY_VIEW"));
        byType.put("PLATFORM_AUDITOR", platformAuditor);

        int touched = 0;
        for (var entry : byType.entrySet()) {
            UserType type = userTypes.findByCode(entry.getKey()).orElse(null);
            if (type == null) continue;

            String name = type.getName();
            UserGroup template = userGroups.findGlobalByName(name).orElse(null);
            Set<Permission> expected = resolve(entry.getValue());

            if (template == null) {
                userGroups.save(UserGroup.builder()
                        .name(name)
                        .description("Suggested starting point for a " + type.getName()
                                + ". Clone it to make a version you can change.")
                        .userTypeId(type.getId())
                        .userTypeCode(type.getCode())
                        .userTypeName(type.getName())
                        .template(true)
                        .system(false)
                        .permissions(expected)
                        .status(AppConstant.STATUS_ACTIVE)
                        .statusFlag(AppConstant.FLAG_ACTIVE)
                        .createdBy(ACTOR)
                        .build());
                touched++;
                continue;
            }
            /*
             * Topped up, never trimmed.
             *
             * A new permission belonging in a template should reach it; a permission somebody deliberately
             * removed from the platform's own copy of the template should stay removed. Adding-only is the
             * behaviour that satisfies both, and existing clones are untouched either way — a clone is
             * independent from the moment it is made.
             */
            boolean dirty = false;
            for (Permission p : expected) {
                if (template.getPermissions().stream()
                        .noneMatch(x -> x.getActionCode().equals(p.getActionCode()))) {
                    template.getPermissions().add(p);
                    dirty = true;
                }
            }
            if (dirty) {
                template.setUpdatedBy(ACTOR);
                userGroups.save(template);
                touched++;
            }
        }
        return touched;
    }

    /**
     * The platform super-admin group: everything, including the platform-only permissions.
     *
     * <p>The one group built from "all permissions" rather than a named list, because the super admin is
     * defined as the actor with no restrictions — and a hand-maintained list here would fall behind every new
     * permission, leaving the platform's own administrator unable to reach a feature they just deployed.
     */
    private int topUpPlatformGroup() {
        UserType type = userTypes.findByCode("SUPER_ADMIN").orElse(null);
        if (type == null) return 0;

        Map<String, AppModule> modules = appModules.findAll().stream()
                .collect(Collectors.toMap(AppModule::getCode, Function.identity(), (a, b) -> a));
        Set<Permission> all = permissions.findByStatusNot(AppConstant.STATUS_DELETED).stream()
                // Still filtered by the module matrix: a permission whose module does not admit SUPER_ADMIN
                // would be dropped by the resolver anyway, and carrying it would make the group's count lie.
                .filter(p -> {
                    AppModule module = modules.get(p.getModuleCode());
                    return module != null && module.allows("SUPER_ADMIN");
                })
                .collect(Collectors.toCollection(LinkedHashSet::new));

        UserGroup group = userGroups.findGlobalByName(PLATFORM_GROUP).orElse(null);
        if (group == null) {
            userGroups.save(UserGroup.builder()
                    .name(PLATFORM_GROUP)
                    .description("Full platform control. Maintained by the seeder.")
                    .userTypeId(type.getId())
                    .userTypeCode(type.getCode())
                    .userTypeName(type.getName())
                    .template(false)
                    .system(true)
                    .permissions(all)
                    .status(AppConstant.STATUS_ACTIVE)
                    .statusFlag(AppConstant.FLAG_ACTIVE)
                    .createdBy(ACTOR)
                    .build());
            return all.size();
        }
        int added = 0;
        for (Permission p : all) {
            if (group.getPermissions().stream()
                    .noneMatch(x -> x.getActionCode().equals(p.getActionCode()))) {
                group.getPermissions().add(p);
                added++;
            }
        }
        if (added > 0) {
            group.setUpdatedBy(ACTOR);
            userGroups.save(group);
        }
        return added;
    }

    /**
     * Every live organisation gets every core module.
     *
     * <p>Onboarding switches on the core modules that exist that day. Nothing revisited it, so a core module
     * shipped afterwards reached organisations onboarded later and nobody else — the same shape of gap as the
     * owner-group top-up above, and it surfaced the same way: a module added this phase was invisible to every
     * organisation already on the platform.
     *
     * <p>Only core modules. A non-core one is a choice an organisation makes, and switching it on for them
     * would be the platform overriding that choice on every deploy.
     */
    private int enableCoreModulesEverywhere() {
        int enabled = 0;
        for (Tenant tenant : tenants.findAll()) {
            if (!AppConstant.isLive(tenant.getStatus())) continue;
            enabled += tenantModules.enableCoreModules(tenant.getId());
        }
        return enabled;
    }

    /**
     * Every organisation's owner group gains whatever its user type may now hold.
     *
     * <p>An owner group is built once, at onboarding, from the permissions that existed that day. Nothing
     * revisited it — so a module shipped afterwards was invisible to every organisation already on the
     * platform until somebody hand-edited each owner group, with nothing anywhere saying that was needed. It
     * surfaced the first time a module was added after onboarding (APPROVALS), and it would have surfaced
     * once per module for the fifteen still to come.
     *
     * <p>Only the <strong>system</strong> group of each organisation — the one that means "the owner, who can
     * do everything here". Groups an organisation built for itself are theirs, and adding permissions to them
     * would be the platform quietly widening a role somebody deliberately narrowed.
     *
     * <p>Still filtered through the module matrix and {@code platformOnly}, exactly as onboarding is: an
     * owner gets everything their kind of user may hold, and nothing that was never theirs to hold.
     */
    private int topUpOrganisationOwnerGroups() {
        Map<String, AppModule> modules = appModules.findAll().stream()
                .collect(Collectors.toMap(AppModule::getCode, Function.identity(), (a, b) -> a));
        List<Permission> everything = permissions.findByStatusNot(AppConstant.STATUS_DELETED);
        /*
         * "Platform only" means a platform actor, not a group with no organisation on it.
         *
         * The bank's own administrator group carries an institution_id, so it was being filtered here
         * exactly as a seller's owner group was — and a BANK_ADMIN is actor class PLATFORM. Co-op runs this
         * platform; its staff are the platform's staff, and a rule that reads "platform only" while denying
         * them is describing a column rather than an authority.
         *
         * The test is therefore the group's user type. A seller's owner still gets nothing that was never
         * theirs to hold, which is what this filter was for.
         */
        Map<String, String> actorByType = userTypes.findAll().stream()
                .collect(Collectors.toMap(UserType::getCode, UserType::getActorClass, (a, b) -> a));

        int added = 0;
        for (UserGroup group : userGroups.findSystemGroups()) {
            // The platform and buyer groups are global and have their own top-ups above.
            if (group.getTenantId() == null && group.getInstitutionId() == null) continue;

            boolean platformActor = AppConstant.ACTOR_PLATFORM
                    .equals(actorByType.get(group.getUserTypeCode()));
            List<Permission> grantable = platformActor
                    ? everything
                    : everything.stream().filter(p -> !p.isPlatformOnly()).toList();

            List<Permission> due = grantable.stream()
                    .filter(p -> {
                        AppModule module = modules.get(p.getModuleCode());
                        return module != null
                                && AppConstant.isLive(module.getStatus())
                                && module.allows(group.getUserTypeCode());
                    })
                    .filter(p -> group.getPermissions().stream()
                            .noneMatch(x -> x.getActionCode().equals(p.getActionCode())))
                    .toList();
            if (due.isEmpty()) continue;

            group.getPermissions().addAll(due);
            group.setUpdatedBy(ACTOR);
            userGroups.save(group);
            added += due.size();
            log.info("Owner group '{}' gained {} newly available permission(s)",
                    group.getName(), due.size());
        }
        return added;
    }

    /** The buyer group: exactly one permission, and nothing that could ever widen it. */
    private int topUpBuyerGroup() {
        UserType type = userTypes.findByCode("BUYER").orElse(null);
        if (type == null) return 0;
        Set<Permission> expected = resolve(List.of("BUYER_PORTAL_ACCESS"));

        UserGroup group = userGroups.findGlobalByName(BUYER_GROUP).orElse(null);
        if (group == null) {
            userGroups.save(UserGroup.builder()
                    .name(BUYER_GROUP)
                    .description("What every buyer holds: access to their own account, and nothing else.")
                    .userTypeId(type.getId())
                    .userTypeCode(type.getCode())
                    .userTypeName(type.getName())
                    .template(false)
                    .system(true)
                    .permissions(expected)
                    .status(AppConstant.STATUS_ACTIVE)
                    .statusFlag(AppConstant.FLAG_ACTIVE)
                    .createdBy(ACTOR)
                    .build());
            return expected.size();
        }
        int added = 0;
        for (Permission p : expected) {
            if (group.getPermissions().stream()
                    .noneMatch(x -> x.getActionCode().equals(p.getActionCode()))) {
                group.getPermissions().add(p);
                added++;
            }
        }
        if (added > 0) {
            group.setUpdatedBy(ACTOR);
            userGroups.save(group);
        }
        return added;
    }

    /**
     * The valuer group: their own panel row, and the jobs assigned to them.
     *
     * <p>Its own group rather than a template, for the same reason the buyer's is: there is exactly one
     * right answer for what a valuer holds, and an organisation cloning and narrowing it is not a case that
     * exists — a valuer belongs to no organisation.
     */
    private int topUpValuerGroup() {
        UserType type = userTypes.findByCode("VALUER").orElse(null);
        if (type == null) return 0;
        Set<Permission> expected = resolve(List.of(
                "VALUER_PANEL_VIEW", "VALUATIONS_VIEW", "VALUATIONS_WORK"));
        // Three permissions across three modules, all of which admit VALUER. If one ever does not, the
        // resolver drops the authority at login and the group reads as granted while behaving as empty.

        UserGroup group = userGroups.findGlobalByName(VALUER_GROUP).orElse(null);
        if (group == null) {
            userGroups.save(UserGroup.builder()
                    .name(VALUER_GROUP)
                    .description("What every panel valuer holds: their own record, and their own jobs.")
                    .userTypeId(type.getId())
                    .userTypeCode(type.getCode())
                    .userTypeName(type.getName())
                    .template(false)
                    .system(true)
                    .permissions(expected)
                    .status(AppConstant.STATUS_ACTIVE)
                    .statusFlag(AppConstant.FLAG_ACTIVE)
                    .createdBy(ACTOR)
                    .build());
            return expected.size();
        }
        int added = 0;
        for (Permission p : expected) {
            if (group.getPermissions().stream()
                    .noneMatch(x -> x.getActionCode().equals(p.getActionCode()))) {
                group.getPermissions().add(p);
                added++;
            }
        }
        if (added > 0) {
            group.setUpdatedBy(ACTOR);
            userGroups.save(group);
        }
        return added;
    }

    /**
     * The group every independent agent belongs to (M9).
     *
     * <p>Global and {@code system}, like the valuer's and the buyer's, because an agent is not somebody's
     * staff: there is no organisation whose owner would maintain their role. What they hold is fixed by the
     * platform, and widening it means editing this list rather than editing a group.
     *
     * <p>The listing verbs are here <em>and</em> gated. An agent holds {@code PROPERTIES_CREATE} from the
     * moment they register; {@code EffectivePermissionResolver} drops it until their profile's KYC standing
     * clears, which approval is what does. Granting the permission and gating it is deliberate — the
     * alternative, editing the group at approval, would mean an agent's rights lived in a mutable row
     * somebody could hand-edit rather than in a rule.
     */
    private int topUpAgentGroup() {
        UserType type = userTypes.findByCode("AGENT").orElse(null);
        if (type == null) return 0;
        Set<Permission> expected = resolve(List.of(
                "DASHBOARD_VIEW", "AGENT_SELF_VIEW", "AGENT_SELF_UPDATE", "AGENTS_VIEW",
                "PROPERTIES_VIEW", "PROPERTIES_CREATE", "PROPERTIES_UPDATE", "PROPERTIES_SUBMIT",
                "PROPERTIES_MEDIA", "PROPERTIES_WITHDRAW", "PROPERTIES_MARK_SOLD",
                "ENQUIRIES_VIEW", "ENQUIRIES_REPLY", "ENQUIRIES_CLOSE",
                "SITE_VISITS_VIEW", "SITE_VISITS_DECIDE", "SITE_VISITS_COMPLETE",
                "PURCHASE_REQUESTS_VIEW", "PURCHASE_REQUESTS_DECIDE",
                "MORTGAGE_PRODUCTS_VIEW", "VALUATIONS_VIEW", "VALUATIONS_REQUEST",
                // What people said about them, and the right of reply (M7).
                "RATINGS_VIEW", "RATINGS_REPLY",
                // Their own week (M12), and what they pay for and owe (M13).
                "CALENDAR_VIEW", "CALENDAR_MANAGE", "ASSIGNMENT_VIEW", "ASSIGNMENT_MANAGE",
                "PROMOTIONS_VIEW", "PROMOTIONS_REQUEST", "COMMISSIONS_VIEW"));

        UserGroup group = userGroups.findGlobalByName(AGENT_GROUP).orElse(null);
        if (group == null) {
            userGroups.save(UserGroup.builder()
                    .name(AGENT_GROUP)
                    .description("What every independent agent holds: their own registration, their "
                            + "listings, and the leads those listings produce.")
                    .userTypeId(type.getId())
                    .userTypeCode(type.getCode())
                    .userTypeName(type.getName())
                    .template(false)
                    .system(true)
                    .permissions(expected)
                    .status(AppConstant.STATUS_ACTIVE)
                    .statusFlag(AppConstant.FLAG_ACTIVE)
                    .createdBy(ACTOR)
                    .build());
            return expected.size();
        }
        int added = 0;
        for (Permission p : expected) {
            if (group.getPermissions().stream()
                    .noneMatch(x -> x.getActionCode().equals(p.getActionCode()))) {
                group.getPermissions().add(p);
                added++;
            }
        }
        if (added > 0) {
            group.setUpdatedBy(ACTOR);
            userGroups.save(group);
        }
        return added;
    }

    /**
     * The group every vendor belongs to (M10).
     *
     * <p>Global and {@code system}, like the agent's and the valuer's. A vendor's catalogue verbs are here
     * from the moment they register and are gated the same way an agent's listing verbs are: the resolver
     * drops them until the profile's KYC standing clears, which approval is what does. Granting and gating
     * beats editing the group at approval — rights that live in a rule cannot be hand-edited into existence.
     */
    private int topUpVendorGroup() {
        UserType type = userTypes.findByCode("VENDOR").orElse(null);
        if (type == null) return 0;
        Set<Permission> expected = resolve(List.of(
                "DASHBOARD_VIEW", "VENDOR_SELF_VIEW", "VENDOR_SELF_UPDATE", "VENDORS_VIEW",
                "CATALOGUE_CREATE", "CATALOGUE_UPDATE", "CATALOGUE_SUBMIT", "CATALOGUE_WITHDRAW",
                "CATALOGUE_DELETE",
                // What people said about them, and the right of reply (M7).
                "RATINGS_VIEW", "RATINGS_REPLY",
                // Their own week (M12).
                "CALENDAR_VIEW", "CALENDAR_MANAGE"));

        UserGroup group = userGroups.findGlobalByName(VENDOR_GROUP).orElse(null);
        if (group == null) {
            userGroups.save(UserGroup.builder()
                    .name(VENDOR_GROUP)
                    .description("What every vendor holds: their own business, and the catalogue they "
                            + "publish.")
                    .userTypeId(type.getId())
                    .userTypeCode(type.getCode())
                    .userTypeName(type.getName())
                    .template(false)
                    .system(true)
                    .permissions(expected)
                    .status(AppConstant.STATUS_ACTIVE)
                    .statusFlag(AppConstant.FLAG_ACTIVE)
                    .createdBy(ACTOR)
                    .build());
            return expected.size();
        }
        int added = 0;
        for (Permission p : expected) {
            if (group.getPermissions().stream()
                    .noneMatch(x -> x.getActionCode().equals(p.getActionCode()))) {
                group.getPermissions().add(p);
                added++;
            }
        }
        if (added > 0) {
            group.setUpdatedBy(ACTOR);
            userGroups.save(group);
        }
        return added;
    }

    // ── bootstrap admin ───────────────────────────────────────────────────────

    /**
     * The first account, so a fresh database is reachable.
     *
     * <p>Created once and never reconciled: if it exists, it is left entirely alone, including its password.
     * Re-applying the configured password on every boot would mean the credential in a properties file
     * silently overwrote whatever the real administrator had changed it to — an environment variable that
     * quietly resets production access every deploy.
     *
     * <p>{@code must_change_password} is set, so the configured value cannot survive first use.
     */
    private boolean seedBootstrapAdmin() {
        if (users.existsByUsernameIgnoreCase(bootstrapUsername)) return false;

        UserType type = userTypes.findByCode("SUPER_ADMIN").orElse(null);
        UserGroup group = userGroups.findGlobalByName(PLATFORM_GROUP).orElse(null);
        if (type == null || group == null) {
            log.error("Cannot create the bootstrap admin — user type or platform group is missing");
            return false;
        }
        String email = bootstrapEmail.trim().toLowerCase();
        if (users.existsByEmail(email)) {
            log.warn("Bootstrap email {} is already taken — no bootstrap admin created", email);
            return false;
        }

        User admin = users.save(User.builder()
                .firstName("Platform")
                .lastName("Administrator")
                .email(email)
                .username(bootstrapUsername)
                .password(passwordEncoder.encode(bootstrapPassword))
                .passwordChangedAt(OffsetDateTime.now())
                .mustChangePassword(true)
                .enabled(true)
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy(ACTOR)
                .build());
        // The profile is what makes them a super administrator. Without it the row can authenticate and
        // resolve nothing — no actor class, no user type, no permissions.
        userProfiles.provisionFirst(admin.getId(), type, group, null, null, null, null);
        consent.captureAtOnboarding(admin.getId());
        log.warn("Created bootstrap admin '{}' — sign in and change the password immediately",
                bootstrapUsername);
        return true;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Resolves action codes to rows, logging anything the enum names but the catalogue does not have. */
    private Set<Permission> resolve(List<String> actionCodes) {
        Set<Permission> found = new LinkedHashSet<>(
                permissions.findByActionCodeIn(Set.copyOf(actionCodes)));
        if (found.size() != actionCodes.size()) {
            List<String> known = found.stream().map(Permission::getActionCode).toList();
            List<String> missing = new ArrayList<>(actionCodes);
            missing.removeAll(known);
            log.warn("Template references permissions that do not exist: {}", missing);
        }
        return found;
    }
}
