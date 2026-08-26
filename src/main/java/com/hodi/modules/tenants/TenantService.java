package com.hodi.modules.tenants;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.DuplicateResourceException;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.auth.RefreshTokenService;
import com.hodi.modules.tenantmodules.TenantModuleService;
import com.hodi.modules.usergroups.UserGroup;
import com.hodi.modules.usergroups.UserGroupRepository;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.profiles.UserProfileService;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.modules.usertypes.UserType;
import com.hodi.modules.usertypes.UserTypeRepository;
import com.hodi.modules.appmodules.AppModule;
import com.hodi.modules.appmodules.AppModuleRepository;
import com.hodi.modules.kyc.KycPolicy;
import com.hodi.modules.partnerships.PartnershipRepository;
import com.hodi.modules.permissions.Permission;
import com.hodi.modules.permissions.PermissionRepository;
import com.hodi.security.TenantScope;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.password.PasswordService;
import com.hodi.security.principal.AuthContext;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Seller-organisation lifecycle: onboarding, suspension, reinstatement, termination.
 *
 * <p><strong>Onboarding is one transaction and cannot half-succeed.</strong> That is the payoff of the
 * single-schema decision: axis needs a provisioning state machine, a compensating {@code DROP SCHEMA} and a
 * {@code provision_status} column because Flyway commits as it goes and no ambient transaction can undo DDL.
 * Here it is three inserts and a module top-up, so either the organisation exists with its owner and its
 * modules, or nothing happened.
 *
 * <p>Onboarding produces four things, and the fourth is the one that matters: a tenant row, its core module
 * enablement, an owner user group carrying the full seller permission set, and a first owner account with a
 * temporary password. Without that last one the organisation exists and nobody can get into it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TEMP_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    /** The group every seller organisation gets, and must always retain one live member of. */
    public static final String OWNER_GROUP_NAME = "Organisation Owner";

    private final TenantRepository repository;
    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final com.hodi.modules.properties.PropertyRepository propertiesRepo;
    private final UserProfileService userProfiles;
    private final UserGroupRepository userGroups;
    private final UserTypeRepository userTypes;
    private final PermissionRepository permissions;
    private final AppModuleRepository appModules;
    private final PartnershipRepository partnerships;
    private final TenantModuleService tenantModules;
    private final KycPolicy kycPolicy;
    private final PasswordService passwords;
    private final RefreshTokenService refreshTokens;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record TenantResponse(
            String id, String name, String slug, String tenantRef, String sellerType,
            String contactName, String contactEmail, String contactPhone,
            String country, String currency, String timezone,
            String onboardingStatus, OffsetDateTime activatedAt, OffsetDateTime suspendedAt,
            String suspensionReason,
            long staffCount, long activePartnerships,
            Integer status, String statusFlag, OffsetDateTime createdAt, String createdBy) {}

    public record CreateTenantRequest(
            @NotBlank(message = "An organisation name is required")
            @Size(max = 255, message = "That name is too long") String name,
            @Size(max = 128) String slug,
            @Size(max = 32) String sellerType,
            @NotBlank(message = "An owner's first name is required") @Size(max = 64) String ownerFirstName,
            @NotBlank(message = "An owner's last name is required") @Size(max = 64) String ownerLastName,
            @NotBlank(message = "An owner's email address is required")
            @Email(message = "That does not look like an email address")
            @Size(max = 128) String ownerEmail,
            @Size(max = 32) String ownerPhone,
            @Size(max = 2) String country,
            @Size(max = 3) String currency,
            @Size(max = 64) String timezone) {}

    public record UpdateTenantRequest(
            @NotBlank(message = "An organisation name is required") @Size(max = 255) String name,
            @Size(max = 32) String sellerType,
            @Size(max = 128) String contactName,
            @Email(message = "That does not look like an email address") @Size(max = 128) String contactEmail,
            @Size(max = 32) String contactPhone,
            @Size(max = 2) String country,
            @Size(max = 3) String currency,
            @Size(max = 64) String timezone) {}

    /** What onboarding returns: the organisation, and the owner's one-time credential. */
    public record OnboardedTenant(TenantResponse tenant, String ownerUsername,
                                  String ownerTemporaryPassword) {}

    // ── reads ─────────────────────────────────────────────────────────────────

    /**
     * The organisations the caller may see.
     *
     * <p>This is the list where {@code TenantScope} earns its keep: platform staff see every seller, a
     * seller's own staff see exactly one row, and a lender's staff see the sellers they are partnered with —
     * which is the whole point of the partnership model and is expressed here as one composable predicate
     * rather than three branches.
     */
    @Transactional(readOnly = true)
    public PagedResponse<TenantResponse> list(PagedDataRequest request) {
        Specification<Tenant> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                // "id", not "tenantId": on the Tenant entity itself the tenant id *is* the primary key.
                TenantScope.restrict("id"));
        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.ASC, "name")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public TenantResponse find(String hashId) {
        Long id = HashIdUtil.decodeId(hashId);
        TenantScope.assertAllowed(id);
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organisation", hashId)));
    }

    /** The caller's own organisation, for the seller-facing settings screen. */
    @Transactional(readOnly = true)
    public TenantResponse mine() {
        Long id = TenantScope.ownTenantId();
        if (id == null) {
            throw new HodiException("Your account is not attached to a seller organisation.",
                    HttpStatus.BAD_REQUEST);
        }
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organisation", id)));
    }

    // ── onboarding ────────────────────────────────────────────────────────────

    @Transactional
    public OnboardedTenant create(CreateTenantRequest request) {
        String slug = normaliseSlug(request.slug(), request.name());
        if (repository.existsBySlugIgnoreCase(slug)) {
            throw new DuplicateResourceException(
                    "An organisation with the handle \"" + slug + "\" already exists");
        }
        String ownerEmail = request.ownerEmail().trim().toLowerCase();
        if (users.existsByEmail(ownerEmail)) {
            throw new DuplicateResourceException(
                    "An account with that owner email address already exists");
        }

        Tenant tenant = repository.save(Tenant.builder()
                .name(request.name().trim())
                .slug(slug)
                .tenantRef(uniqueRef())
                .sellerType(kycPolicy.normalise(request.sellerType()))
                .contactName(request.ownerFirstName().trim() + " " + request.ownerLastName().trim())
                .contactEmail(ownerEmail)
                .contactPhone(blankToNull(request.ownerPhone()))
                .country(blankToNull(request.country()))
                .currency(request.currency() == null || request.currency().isBlank()
                        ? "KES" : request.currency().trim().toUpperCase())
                .timezone(request.timezone() == null || request.timezone().isBlank()
                        ? "Africa/Nairobi" : request.timezone().trim())
                // ACTIVE immediately. There is no provisioning step to wait for, so a PENDING state would be
                // a status nothing ever moves out of.
                .onboardingStatus(AppConstant.ONBOARDING_ACTIVE)
                .activatedAt(OffsetDateTime.now())
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy(AuthContext.username())
                .build());

        int modules = tenantModules.enableCoreModules(tenant.getId());
        UserGroup ownerGroup = createOwnerGroup(tenant);
        String temporary = temporaryPassword();
        User owner = createOwner(tenant, ownerGroup, request, ownerEmail, temporary);

        /*
         * The KYC policy, applied after the owner's profile exists — there is nothing to move before that.
         *
         * A seller of a covered type starts at PENDING rather than NOT_REQUIRED, which means they cannot
         * list until Compliance clears them. That is the point: the alternative is an organisation that can
         * publish property to the public before anybody has checked who they are.
         */
        kycPolicy.applyTo(tenant.getId(), tenant.getSellerType());

        audit.record(AppConstant.ACTION_CREATE, "Tenant", tenant.getId(), null, snapshot(tenant));
        log.info("Onboarded seller {} ({}) with {} core modules and owner {}",
                tenant.getName(), tenant.getSlug(), modules, owner.getUsername());
        return new OnboardedTenant(toResponse(tenant), owner.getUsername(), temporary);
    }

    /**
     * The one-person organisation an approved agent lists into (M9).
     *
     * <p>Deliberately not {@link #create}: that path mints an owner group and an owner account with a
     * temporary password, and an agent already has an account — the one they registered with. What they lack
     * is somewhere to put a listing, and this supplies exactly that.
     *
     * <p>Here rather than in the agents module so that slug normalisation, reference allocation and
     * collision handling stay in one place. A second implementation of "make a unique handle" is a second
     * chance to get uniqueness wrong, and the failure is a duplicate-key error in front of somebody being
     * approved.
     *
     * <p>The caller attaches the agent's profile to it and decides their KYC standing — this method takes no
     * view on either, because for an agent both are decided by the approval rather than by onboarding.
     */
    @Transactional
    public Tenant createForAgent(String name, String contactName, String email, String phone) {
        String base = normaliseSlug(null, name);
        String slug = base;
        for (int suffix = 2; repository.existsBySlugIgnoreCase(slug) && suffix < 1000; suffix++) {
            slug = base + "-" + suffix;
        }
        if (repository.existsBySlugIgnoreCase(slug)) {
            throw new HodiException("Could not allocate a handle for this agent — try a different name.",
                    HttpStatus.CONFLICT);
        }

        Tenant tenant = repository.save(Tenant.builder()
                .name(name.trim())
                .slug(slug)
                .tenantRef(uniqueRef())
                // An independent agent is an agency in the seller-type taxonomy, which is what makes the
                // register and the reporting read honestly rather than showing a null.
                .sellerType("AGENCY")
                .contactName(contactName)
                .contactEmail(email)
                .contactPhone(phone)
                .country("KE")
                .currency("KES")
                .timezone("Africa/Nairobi")
                .onboardingStatus(AppConstant.ONBOARDING_ACTIVE)
                .activatedAt(OffsetDateTime.now())
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy(AuthContext.username())
                .build());

        tenantModules.enableCoreModules(tenant.getId());
        audit.record(AppConstant.ACTION_CREATE, "Tenant", tenant.getId(), null, snapshot(tenant));
        log.info("Created agent organisation {} ({})", tenant.getName(), tenant.getSlug());
        return tenant;
    }

    /**
     * The organisation's own owner group, carrying every permission a seller may hold.
     *
     * <p>Built from a query — {@code findByPlatformOnlyFalse} — rather than a hand-maintained list, because a
     * list here would silently fall behind every new permission and the owner would be the last to get access
     * to features they are paying for. The {@code platform_only} flag on the row is what keeps
     * {@code TENANTS_CREATE} and its neighbours out of it.
     *
     * <p>Marked {@code is_system}, which is what makes the lock-out guard apply: the organisation must always
     * retain one live member of this group.
     */
    private UserGroup createOwnerGroup(Tenant tenant) {
        UserType ownerType = userTypes.findByCode("SELLER_OWNER")
                .orElseThrow(() -> new HodiException(
                        "The SELLER_OWNER user type is missing — the seeder has not run.",
                        HttpStatus.INTERNAL_SERVER_ERROR));

        Set<Permission> granted = new LinkedHashSet<>(
                sellerGrantablePermissions(ownerType.getCode()));

        return userGroups.save(UserGroup.builder()
                .name(OWNER_GROUP_NAME)
                .description("Full control of this organisation. Cannot be edited or emptied.")
                .userTypeId(ownerType.getId())
                .userTypeCode(ownerType.getCode())
                .userTypeName(ownerType.getName())
                .tenantId(tenant.getId())
                .template(false)
                .system(true)
                .permissions(granted)
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy(AuthContext.username())
                .build());
    }

    /**
     * Everything a seller-side group may hold: not platform-only, and in a module that admits the type.
     *
     * <p>Both filters are load-bearing. Without the first, the owner group would carry {@code TENANTS_CREATE}
     * and its neighbours — an organisation able to onboard organisations. Without the second, it would carry
     * permissions for modules that do not admit {@code SELLER_OWNER} at all, which
     * {@code EffectivePermissionResolver} then drops at login: a group that claims more than it grants, which
     * is the harder of the two to diagnose because nothing anywhere reports the discrepancy.
     */
    private List<Permission> sellerGrantablePermissions(String userTypeCode) {
        Map<String, AppModule> modules = appModules.findAll().stream()
                .collect(Collectors.toMap(AppModule::getCode, Function.identity(), (a, b) -> a));
        return permissions.findByPlatformOnlyFalseAndStatusNot(AppConstant.STATUS_DELETED).stream()
                .filter(p -> {
                    AppModule module = modules.get(p.getModuleCode());
                    return module != null
                            && AppConstant.isLive(module.getStatus())
                            && module.allows(userTypeCode);
                })
                .toList();
    }

    private User createOwner(Tenant tenant, UserGroup group, CreateTenantRequest request,
                             String email, String temporary) {
        UserType ownerType = userTypes.findByCode("SELLER_OWNER").orElseThrow();
        String username = deriveUsername(email);

        User owner = User.builder()
                .firstName(request.ownerFirstName().trim())
                .lastName(request.ownerLastName().trim())
                .email(email)
                .username(username)
                .phone(blankToNull(request.ownerPhone()))
                .enabled(true)
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy(AuthContext.username())
                .build();
        passwords.applyTo(owner, temporary);
        owner.setMustChangePassword(true);
        User saved = users.save(owner);
        // The profile is what attaches them to this organisation as its owner.
        userProfiles.provisionFirst(saved.getId(), ownerType, group, tenant.getId(), tenant.getName(),
                null, null);
        return saved;
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    @Transactional
    public TenantResponse update(String hashId, UpdateTenantRequest request) {
        Long id = HashIdUtil.decodeId(hashId);
        TenantScope.assertAllowed(id);
        Tenant tenant = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Organisation", hashId));
        String before = snapshot(tenant);

        boolean renamed = !tenant.getName().equals(request.name().trim());
        tenant.setName(request.name().trim());
        tenant.setSellerType(kycPolicy.normalise(request.sellerType()));
        tenant.setContactName(blankToNull(request.contactName()));
        tenant.setContactEmail(request.contactEmail() == null || request.contactEmail().isBlank()
                ? null : request.contactEmail().trim().toLowerCase());
        tenant.setContactPhone(blankToNull(request.contactPhone()));
        tenant.setCountry(blankToNull(request.country()));
        if (request.currency() != null && !request.currency().isBlank()) {
            tenant.setCurrency(request.currency().trim().toUpperCase());
        }
        if (request.timezone() != null && !request.timezone().isBlank()) {
            tenant.setTimezone(request.timezone().trim());
        }
        /*
         * The slug is deliberately not editable. It is the local part of this organisation's outbound email
         * identity, so changing it changes every address they send from — and anything already sent would
         * appear to come from an address that no longer resolves to them. Renaming the business changes the
         * name, never the handle.
         */
        tenant.setStatus(AppConstant.STATUS_EDITED);
        tenant.setStatusFlag(AppConstant.FLAG_EDITED);
        tenant.setUpdatedBy(AuthContext.username());

        Tenant saved = repository.save(tenant);
        if (renamed) {
            // One writer for the label cache, in the owning service — the denormalisation rule.
            profiles.renameTenantLabel(saved.getId(), saved.getName());
        }
        // A type set or changed here is the other moment the policy applies. It only ever moves people from
        // "never asked" into "asked" — an already-cleared organisation is left alone.
        kycPolicy.applyTo(saved.getId(), saved.getSellerType());
        audit.record(AppConstant.ACTION_UPDATE, "Tenant", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    /**
     * Stops an organisation trading.
     *
     * <p>Suspension is a property of the organisation, not of its people: staff rows are left alone, and
     * {@code AuthService.assertOrganisationTradeable} refuses them at login and at refresh. Deactivating every
     * user instead would work once and then lose the distinction between "this person was deactivated" and
     * "their employer was", so reinstating would either restore somebody who had been individually
     * deactivated or leave somebody out who had not.
     *
     * <p>Live sessions are revoked, because otherwise a suspended organisation keeps working until each
     * session's window lapses.
     */
    @Transactional
    public void suspend(String hashId, String reason) {
        Tenant tenant = requirePlatformManaged(hashId);
        if (tenant.isTerminated()) {
            throw new HodiException("That organisation is already closed.", HttpStatus.CONFLICT);
        }
        String before = snapshot(tenant);
        tenant.setOnboardingStatus(AppConstant.ONBOARDING_SUSPENDED);
        tenant.setSuspendedAt(OffsetDateTime.now());
        tenant.setSuspensionReason(reason);
        tenant.setUpdatedBy(AuthContext.username());
        repository.save(tenant);

        int sessions = 0;
        for (Long staffId : profiles.findLiveUserIdsByTenant(tenant.getId())) {
            sessions += refreshTokens.revokeAllForUser(staffId);
        }
        audit.record(AppConstant.ACTION_SUSPEND, "Tenant", tenant.getId(), before, snapshot(tenant));
        log.info("Suspended seller {} and ended {} session(s)", tenant.getSlug(), sessions);
    }

    @Transactional
    public void reinstate(String hashId) {
        Tenant tenant = requirePlatformManaged(hashId);
        if (!tenant.isSuspended()) {
            throw new HodiException("That organisation is not suspended.", HttpStatus.CONFLICT);
        }
        String before = snapshot(tenant);
        tenant.setOnboardingStatus(AppConstant.ONBOARDING_ACTIVE);
        tenant.setSuspendedAt(null);
        tenant.setSuspensionReason(null);
        tenant.setUpdatedBy(AuthContext.username());
        repository.save(tenant);
        audit.record(AppConstant.ACTION_REINSTATE, "Tenant", tenant.getId(), before, snapshot(tenant));
    }

    /**
     * Closes an organisation for good.
     *
     * <p>Terminal by intent but still a soft state: the row stays, the audit trail stays, and the staff rows
     * stay. Nothing is deleted, because the record of what an organisation did has to outlive the
     * organisation — which is also why {@code audit_logs.tenant_id} has no foreign key.
     */
    @Transactional
    public void terminate(String hashId, String reason) {
        Tenant tenant = requirePlatformManaged(hashId);
        String before = snapshot(tenant);
        tenant.setOnboardingStatus(AppConstant.ONBOARDING_TERMINATED);
        tenant.setTerminatedAt(OffsetDateTime.now());
        tenant.setSuspensionReason(reason);
        tenant.setStatus(AppConstant.STATUS_INACTIVE);
        tenant.setStatusFlag(AppConstant.FLAG_INACTIVE);
        tenant.setDeactivationReason(reason);
        tenant.setUpdatedBy(AuthContext.username());
        repository.save(tenant);

        for (Long staffId : profiles.findLiveUserIdsByTenant(tenant.getId())) {
            refreshTokens.revokeAllForUser(staffId);
        }
        audit.record(AppConstant.ACTION_TERMINATE, "Tenant", tenant.getId(), before, snapshot(tenant));
    }

    @Transactional
    public void activate(String hashId) {
        Tenant tenant = requirePlatformManaged(hashId);
        String before = snapshot(tenant);
        tenant.setOnboardingStatus(AppConstant.ONBOARDING_ACTIVE);
        tenant.setStatus(AppConstant.STATUS_ACTIVE);
        tenant.setStatusFlag(AppConstant.FLAG_ACTIVE);
        tenant.setDeactivationReason(null);
        tenant.setTerminatedAt(null);
        tenant.setUpdatedBy(AuthContext.username());
        repository.save(tenant);
        audit.record(AppConstant.ACTION_ACTIVATE, "Tenant", tenant.getId(), before, snapshot(tenant));
    }

    @Transactional
    public void deactivate(String hashId, String reason) {
        Tenant tenant = requirePlatformManaged(hashId);
        String before = snapshot(tenant);
        tenant.setStatus(AppConstant.STATUS_INACTIVE);
        tenant.setStatusFlag(AppConstant.FLAG_INACTIVE);
        tenant.setDeactivationReason(reason);
        tenant.setUpdatedBy(AuthContext.username());
        repository.save(tenant);
        audit.record(AppConstant.ACTION_DEACTIVATE, "Tenant", tenant.getId(), before, snapshot(tenant));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Lifecycle changes are platform-only.
     *
     * <p>{@code TenantScope} would let a seller's own owner through — their organisation is in their visible
     * set — and suspending or terminating your own account is not something to offer as a self-service
     * button. The permission gate on the controller already says platform-only; this is the check that holds
     * if somebody widens that gate later.
     */
    private Tenant requirePlatformManaged(String hashId) {
        if (!AuthContext.require().isPlatformStaff()) {
            throw new HodiException("Only platform staff can change an organisation's status.",
                    HttpStatus.FORBIDDEN);
        }
        return repository.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Organisation", hashId));
    }

    /**
     * A URL-safe handle, from the supplied one or derived from the name.
     *
     * <p>Lower-cased and stripped to {@code [a-z0-9-]}, because it becomes an email local part and a
     * database CHECK enforces the lower-casing. Leading and trailing hyphens are trimmed so a name like
     * "Acme Ltd." does not produce {@code acme-ltd-}.
     */
    private static String normaliseSlug(String supplied, String name) {
        String base = (supplied == null || supplied.isBlank()) ? name : supplied;
        String slug = base.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            throw new HodiException("That name cannot be turned into a handle — supply one.",
                    HttpStatus.BAD_REQUEST);
        }
        return slug.length() > 128 ? slug.substring(0, 128) : slug;
    }

    /** Retried on collision: the generator is statistically safe, not guaranteed. */
    private String uniqueRef() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String ref = RrnGenerator.generate("HS");
            if (!repository.existsByTenantRef(ref)) return ref;
        }
        throw new HodiException("Could not allocate an organisation reference — try again.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private String deriveUsername(String email) {
        String base = email.substring(0, email.indexOf('@')).replaceAll("[^a-zA-Z0-9._-]", "");
        if (base.isEmpty()) base = "owner";
        if (!users.existsByUsernameIgnoreCase(base)) return base;
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = base + suffix;
            if (!users.existsByUsernameIgnoreCase(candidate)) return candidate;
        }
        throw new HodiException("Could not derive a username for the owner.", HttpStatus.CONFLICT);
    }

    private static String temporaryPassword() {
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 10; i++) {
            sb.append(TEMP_ALPHABET.charAt(RANDOM.nextInt(TEMP_ALPHABET.length())));
        }
        sb.append('#').append(RANDOM.nextInt(10));
        sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
        return sb.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private TenantResponse toResponse(Tenant tenant) {
        return new TenantResponse(
                HashIdUtil.encodeId(tenant.getId()),
                tenant.getName(),
                tenant.getSlug(),
                tenant.getTenantRef(),
                tenant.getSellerType(),
                tenant.getContactName(),
                tenant.getContactEmail(),
                tenant.getContactPhone(),
                tenant.getCountry(),
                tenant.getCurrency(),
                tenant.getTimezone(),
                tenant.getOnboardingStatus(),
                tenant.getActivatedAt(),
                tenant.getSuspendedAt(),
                tenant.getSuspensionReason(),
                profiles.countByTenant(tenant.getId(), AppConstant.STATUS_DELETED),
                partnerships.findActiveInstitutionIdsForTenant(tenant.getId()).size(),
                tenant.getStatus(),
                tenant.getStatusFlag(),
                tenant.getCreatedAt(),
                tenant.getCreatedBy());
    }

    private static String snapshot(Tenant tenant) {
        return "{\"name\":\"%s\",\"slug\":\"%s\",\"onboarding\":\"%s\",\"status\":%d}"
                .formatted(tenant.getName(), tenant.getSlug(),
                        tenant.getOnboardingStatus(), tenant.getStatus());
    }
}
