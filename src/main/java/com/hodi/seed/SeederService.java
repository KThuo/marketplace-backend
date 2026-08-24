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

    private final UserTypeRepository userTypes;
    private final AppModuleRepository appModules;
    private final PermissionRepository permissions;
    private final UserGroupRepository userGroups;
    private final UserRepository users;
    private final UserProfileService userProfiles;
    private final ConfigurationRepository configurations;
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
        int types = seedUserTypes();
        int modules = seedAppModules();
        int perms = seedPermissions();
        int configs = seedConfigurations();
        int templates = seedRoleTemplates();
        int platform = topUpPlatformGroup();
        int buyer = topUpBuyerGroup();
        boolean bootstrapped = seedBootstrapAdmin();

        log.info("Seeder: {} user types, {} modules, {} permissions, {} configs, {} templates "
                        + "(+{} platform, +{} buyer perms){}",
                types, modules, perms, configs, templates, platform, buyer,
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
        byType.put("LISTING_MANAGER", List.of("DASHBOARD_VIEW"));
        byType.put("SALES_AGENT", List.of("DASHBOARD_VIEW"));
        byType.put("MORTGAGE_OFFICER", List.of(
                "DASHBOARD_VIEW", "INSTITUTION_SELF_VIEW", "PARTNERSHIPS_VIEW"));
        byType.put("CREDIT_ANALYST", List.of(
                "DASHBOARD_VIEW", "INSTITUTION_SELF_VIEW", "PARTNERSHIPS_VIEW"));
        // No AUDIT_VIEW: the audit trail is what distinguishes PLATFORM_AUDITOR from support, and the AUDIT
        // module does not admit SUPPORT_ADMIN — so granting it here produced a template naming a permission
        // its own user type could never hold, which surfaced as "these permissions are not available for this
        // kind of user" the first time anybody tried to clone it.
        byType.put("SUPPORT_ADMIN", List.of(
                "DASHBOARD_VIEW", "TENANTS_VIEW", "INSTITUTIONS_VIEW", "USERS_VIEW",
                "PARTNERSHIPS_VIEW"));
        byType.put("PLATFORM_AUDITOR", List.of(
                "DASHBOARD_VIEW", "AUDIT_VIEW", "TENANTS_VIEW", "INSTITUTIONS_VIEW"));

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
