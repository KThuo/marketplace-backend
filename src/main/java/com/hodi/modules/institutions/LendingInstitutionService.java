package com.hodi.modules.institutions;

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
import com.hodi.modules.partnerships.PartnershipRepository;
import com.hodi.modules.permissions.Permission;
import com.hodi.modules.permissions.PermissionRepository;
import com.hodi.modules.appmodules.AppModule;
import com.hodi.modules.appmodules.AppModuleRepository;
import com.hodi.modules.usergroups.UserGroup;
import com.hodi.modules.usergroups.UserGroupRepository;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.modules.usertypes.UserType;
import com.hodi.modules.usertypes.UserTypeRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.password.PasswordService;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
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
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Lending-institution lifecycle. Registered by the platform, staffed by their own administrator.
 *
 * <p>Structurally the mirror of {@code TenantService} — register the organisation, create its system group,
 * create its first administrator — with one deliberate asymmetry: <strong>there is no module enablement</strong>.
 * Institutions are gated by user type alone in this phase (plan section 12, question 2), so there is no
 * {@code institution_modules} table and this service does not pretend there is. Building half of it would
 * mean two places to ask "is this module on", one of which always answered yes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LendingInstitutionService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TEMP_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    public static final String ADMIN_GROUP_NAME = "Institution Administrator";

    private final LendingInstitutionRepository repository;
    private final UserRepository users;
    private final UserGroupRepository userGroups;
    private final UserTypeRepository userTypes;
    private final PermissionRepository permissions;
    private final AppModuleRepository appModules;
    private final PartnershipRepository partnerships;
    private final PasswordService passwords;
    private final RefreshTokenService refreshTokens;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record InstitutionResponse(
            String id, String name, String slug, String institutionRef, String institutionType,
            String licenceNumber, String contactName, String contactEmail, String contactPhone,
            String country, long staffCount, long activePartnerships,
            Integer status, String statusFlag, OffsetDateTime createdAt, String createdBy) {}

    public record CreateInstitutionRequest(
            @NotBlank(message = "An institution name is required") @Size(max = 255) String name,
            @Size(max = 128) String slug,
            @NotBlank(message = "Choose the kind of institution")
            @Pattern(regexp = "^(BANK|SACCO|MFI|INSURER|OTHER)$",
                    message = "Must be BANK, SACCO, MFI, INSURER or OTHER")
            String institutionType,
            @Size(max = 64) String licenceNumber,
            @NotBlank(message = "An administrator's first name is required")
            @Size(max = 64) String adminFirstName,
            @NotBlank(message = "An administrator's last name is required")
            @Size(max = 64) String adminLastName,
            @NotBlank(message = "An administrator's email address is required")
            @Email(message = "That does not look like an email address")
            @Size(max = 128) String adminEmail,
            @Size(max = 32) String adminPhone,
            @Size(max = 2) String country) {}

    public record UpdateInstitutionRequest(
            @NotBlank(message = "An institution name is required") @Size(max = 255) String name,
            @Pattern(regexp = "^(BANK|SACCO|MFI|INSURER|OTHER)$",
                    message = "Must be BANK, SACCO, MFI, INSURER or OTHER")
            String institutionType,
            @Size(max = 64) String licenceNumber,
            @Size(max = 128) String contactName,
            @Email(message = "That does not look like an email address")
            @Size(max = 128) String contactEmail,
            @Size(max = 32) String contactPhone,
            @Size(max = 2) String country) {}

    public record RegisteredInstitution(InstitutionResponse institution, String adminUsername,
                                        String adminTemporaryPassword) {}

    // ── reads ─────────────────────────────────────────────────────────────────

    /**
     * The institution list.
     *
     * <p>Unscoped by {@code TenantScope}, deliberately: institutions are not tenants, so there is no visible
     * tenant set that describes them. A seller browsing for a finance partner needs to see the lenders on the
     * platform — that is a directory, not somebody's private data — and a lender's own staff are narrowed to
     * their own row by {@link #visibleTo} instead.
     */
    @Transactional(readOnly = true)
    public PagedResponse<InstitutionResponse> list(PagedDataRequest request) {
        Specification<LendingInstitution> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                visibleTo(AuthContext.require()));
        var page = repository.findAll(spec, request.toPageable(Sort.by(Sort.Direction.ASC, "name")));
        return PagedResponse.from(page, this::toResponse);
    }

    /**
     * A lender's own staff see only their institution; everybody else sees the directory.
     *
     * <p>The narrowing is for their benefit rather than for secrecy — a mortgage officer's institution list
     * showing forty competitors is noise — and the row itself is not sensitive, which is why sellers and the
     * platform see all of them.
     */
    private Specification<LendingInstitution> visibleTo(UserPrincipal caller) {
        if (caller.getInstitutionId() == null) return null;
        return (root, query, cb) -> cb.equal(root.get("id"), caller.getInstitutionId());
    }

    @Transactional(readOnly = true)
    public InstitutionResponse find(String hashId) {
        return toResponse(require(hashId));
    }

    @Transactional(readOnly = true)
    public InstitutionResponse mine() {
        Long id = AuthContext.institutionId();
        if (id == null) {
            throw new HodiException("Your account is not attached to a lending institution.",
                    HttpStatus.BAD_REQUEST);
        }
        return toResponse(repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Institution", id)));
    }

    /** Lenders a seller could propose a partnership to — active, and not already linked. */
    @Transactional(readOnly = true)
    public List<InstitutionResponse> partnerable() {
        Long tenantId = AuthContext.tenantId();
        if (tenantId == null) {
            throw new HodiException("Only a seller organisation can look for finance partners.",
                    HttpStatus.BAD_REQUEST);
        }
        return repository.findPartnerableBy(tenantId).stream().map(this::toResponse).toList();
    }

    // ── writes ────────────────────────────────────────────────────────────────

    @Transactional
    public RegisteredInstitution create(CreateInstitutionRequest request) {
        String slug = normaliseSlug(request.slug(), request.name());
        if (repository.existsBySlugIgnoreCase(slug)) {
            throw new DuplicateResourceException(
                    "An institution with the handle \"" + slug + "\" already exists");
        }
        String adminEmail = request.adminEmail().trim().toLowerCase();
        if (users.existsByEmail(adminEmail)) {
            throw new DuplicateResourceException(
                    "An account with that administrator email address already exists");
        }

        LendingInstitution institution = repository.save(LendingInstitution.builder()
                .name(request.name().trim())
                .slug(slug)
                .institutionRef(uniqueRef())
                .institutionType(request.institutionType().trim().toUpperCase())
                .licenceNumber(blankToNull(request.licenceNumber()))
                .contactName(request.adminFirstName().trim() + " " + request.adminLastName().trim())
                .contactEmail(adminEmail)
                .contactPhone(blankToNull(request.adminPhone()))
                .country(blankToNull(request.country()))
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy(AuthContext.username())
                .build());

        UserGroup adminGroup = createAdminGroup(institution);
        String temporary = temporaryPassword();
        User admin = createAdmin(institution, adminGroup, request, adminEmail, temporary);

        audit.record(AppConstant.ACTION_CREATE, "LendingInstitution", institution.getId(), null,
                snapshot(institution));
        log.info("Registered institution {} ({}) with administrator {}",
                institution.getName(), institution.getSlug(), admin.getUsername());
        return new RegisteredInstitution(toResponse(institution), admin.getUsername(), temporary);
    }

    private UserGroup createAdminGroup(LendingInstitution institution) {
        UserType adminType = userTypes.findByCode("LENDER_ADMIN")
                .orElseThrow(() -> new HodiException(
                        "The LENDER_ADMIN user type is missing — the seeder has not run.",
                        HttpStatus.INTERNAL_SERVER_ERROR));

        Map<String, AppModule> modules = appModules.findAll().stream()
                .collect(Collectors.toMap(AppModule::getCode, Function.identity(), (a, b) -> a));
        // Same two filters as the seller owner group: not platform-only, and in a module that admits this
        // type. Without the second, the group would carry authorities the resolver drops at login.
        var granted = permissions.findByPlatformOnlyFalseAndStatusNot(AppConstant.STATUS_DELETED)
                .stream()
                .filter(p -> {
                    AppModule module = modules.get(p.getModuleCode());
                    return module != null && AppConstant.isLive(module.getStatus())
                            && module.allows(adminType.getCode());
                })
                .collect(Collectors.toCollection(LinkedHashSet<Permission>::new));

        return userGroups.save(UserGroup.builder()
                .name(ADMIN_GROUP_NAME)
                .description("Full control of this institution. Cannot be edited or emptied.")
                .userTypeId(adminType.getId())
                .userTypeCode(adminType.getCode())
                .userTypeName(adminType.getName())
                .institutionId(institution.getId())
                .template(false)
                .system(true)
                .permissions(granted)
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy(AuthContext.username())
                .build());
    }

    private User createAdmin(LendingInstitution institution, UserGroup group,
                             CreateInstitutionRequest request, String email, String temporary) {
        UserType adminType = userTypes.findByCode("LENDER_ADMIN").orElseThrow();
        User admin = User.builder()
                .firstName(request.adminFirstName().trim())
                .lastName(request.adminLastName().trim())
                .email(email)
                .username(deriveUsername(email))
                .phone(blankToNull(request.adminPhone()))
                .userTypeId(adminType.getId())
                .userTypeCode(adminType.getCode())
                .userTypeName(adminType.getName())
                .actorClass(adminType.getActorClass())
                .institutionId(institution.getId())
                .institutionName(institution.getName())
                .userGroupId(group.getId())
                .userGroupName(group.getName())
                .enabled(true)
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy(AuthContext.username())
                .build();
        passwords.applyTo(admin, temporary);
        admin.setMustChangePassword(true);
        return users.save(admin);
    }

    @Transactional
    public InstitutionResponse update(String hashId, UpdateInstitutionRequest request) {
        LendingInstitution institution = requireManageable(hashId);
        String before = snapshot(institution);

        boolean renamed = !institution.getName().equals(request.name().trim());
        institution.setName(request.name().trim());
        if (request.institutionType() != null && !request.institutionType().isBlank()) {
            institution.setInstitutionType(request.institutionType().trim().toUpperCase());
        }
        institution.setLicenceNumber(blankToNull(request.licenceNumber()));
        institution.setContactName(blankToNull(request.contactName()));
        institution.setContactEmail(request.contactEmail() == null || request.contactEmail().isBlank()
                ? null : request.contactEmail().trim().toLowerCase());
        institution.setContactPhone(blankToNull(request.contactPhone()));
        institution.setCountry(blankToNull(request.country()));
        institution.setStatus(AppConstant.STATUS_EDITED);
        institution.setStatusFlag(AppConstant.FLAG_EDITED);
        institution.setUpdatedBy(AuthContext.username());

        LendingInstitution saved = repository.save(institution);
        if (renamed) {
            users.renameInstitutionLabel(saved.getId(), saved.getName());
        }
        audit.record(AppConstant.ACTION_UPDATE, "LendingInstitution", saved.getId(), before,
                snapshot(saved));
        return toResponse(saved);
    }

    /**
     * Takes an institution out of use.
     *
     * <p>Their staff's sessions end, because an institution's staff read other organisations' portfolios and
     * that access has to stop when the institution does. Partnerships are left as they are rather than
     * revoked: {@code PartnershipRepository.findActiveTenantIdsForInstitution} does not consult the
     * institution's own status, but the staff cannot sign in to use them — and leaving the rows intact means
     * reactivating restores the arrangement rather than requiring every seller to approve again.
     */
    @Transactional
    public void deactivate(String hashId, String reason) {
        LendingInstitution institution = requirePlatform(hashId);
        String before = snapshot(institution);
        institution.setStatus(AppConstant.STATUS_INACTIVE);
        institution.setStatusFlag(AppConstant.FLAG_INACTIVE);
        institution.setDeactivationReason(reason);
        institution.setUpdatedBy(AuthContext.username());
        repository.save(institution);

        int sessions = 0;
        for (User staff : users.findLiveByInstitution(institution.getId())) {
            sessions += refreshTokens.revokeAllForUser(staff.getId());
        }
        audit.record(AppConstant.ACTION_DEACTIVATE, "LendingInstitution", institution.getId(),
                before, snapshot(institution));
        log.info("Deactivated institution {} and ended {} session(s)", institution.getSlug(), sessions);
    }

    @Transactional
    public void activate(String hashId) {
        LendingInstitution institution = requirePlatform(hashId);
        String before = snapshot(institution);
        institution.setStatus(AppConstant.STATUS_ACTIVE);
        institution.setStatusFlag(AppConstant.FLAG_ACTIVE);
        institution.setDeactivationReason(null);
        institution.setUpdatedBy(AuthContext.username());
        repository.save(institution);
        audit.record(AppConstant.ACTION_ACTIVATE, "LendingInstitution", institution.getId(),
                before, snapshot(institution));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private LendingInstitution require(String hashId) {
        return repository.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Institution", hashId));
    }

    /** Editable by the platform, or by that institution's own administrator. */
    private LendingInstitution requireManageable(String hashId) {
        LendingInstitution institution = require(hashId);
        UserPrincipal caller = AuthContext.require();
        if (caller.isPlatformStaff()) return institution;
        if (!institution.getId().equals(caller.getInstitutionId())) {
            throw new ResourceNotFoundException("Institution", hashId);
        }
        return institution;
    }

    /** Lifecycle changes are platform-only — nobody deactivates their own institution. */
    private LendingInstitution requirePlatform(String hashId) {
        if (!AuthContext.require().isPlatformStaff()) {
            throw new HodiException("Only platform staff can change an institution's status.",
                    HttpStatus.FORBIDDEN);
        }
        return require(hashId);
    }

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

    private String uniqueRef() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String ref = RrnGenerator.generate("HL");
            if (!repository.existsByInstitutionRef(ref)) return ref;
        }
        throw new HodiException("Could not allocate an institution reference — try again.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private String deriveUsername(String email) {
        String base = email.substring(0, email.indexOf('@')).replaceAll("[^a-zA-Z0-9._-]", "");
        if (base.isEmpty()) base = "admin";
        if (!users.existsByUsernameIgnoreCase(base)) return base;
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = base + suffix;
            if (!users.existsByUsernameIgnoreCase(candidate)) return candidate;
        }
        throw new HodiException("Could not derive a username for the administrator.",
                HttpStatus.CONFLICT);
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

    private InstitutionResponse toResponse(LendingInstitution institution) {
        return new InstitutionResponse(
                HashIdUtil.encodeId(institution.getId()),
                institution.getName(),
                institution.getSlug(),
                institution.getInstitutionRef(),
                institution.getInstitutionType(),
                institution.getLicenceNumber(),
                institution.getContactName(),
                institution.getContactEmail(),
                institution.getContactPhone(),
                institution.getCountry(),
                users.countByInstitutionIdAndStatusNot(institution.getId(), AppConstant.STATUS_DELETED),
                partnerships.findActiveTenantIdsForInstitution(institution.getId()).size(),
                institution.getStatus(),
                institution.getStatusFlag(),
                institution.getCreatedAt(),
                institution.getCreatedBy());
    }

    private static String snapshot(LendingInstitution institution) {
        return "{\"name\":\"%s\",\"slug\":\"%s\",\"type\":\"%s\",\"status\":%d}"
                .formatted(institution.getName(), institution.getSlug(),
                        institution.getInstitutionType(), institution.getStatus());
    }
}
