package com.hodi.modules.vendors;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.DuplicateResourceException;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.enums.ConfigKey;
import com.hodi.infra.storage.StorageService;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.profiles.UserProfileService;
import com.hodi.modules.tenants.Tenant;
import com.hodi.modules.tenants.TenantService;
import com.hodi.modules.usergroups.UserGroup;
import com.hodi.modules.usergroups.UserGroupRepository;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.modules.usertypes.UserType;
import com.hodi.modules.usertypes.UserTypeRepository;
import com.hodi.security.password.PasswordService;
import com.hodi.security.principal.AuthContext;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;

/**
 * The vendor register (M10, BRD FR170).
 *
 * <p>Deliberately the same shape as {@code AgentService}, because it is the same problem: somebody outside
 * the platform applies, the platform checks them, and approval creates a one-person organisation for them to
 * publish into. The differences are what is checked and what they publish.
 *
 * <p>Approval sets the profile's KYC standing to {@code APPROVED} for the same reason it does for an agent:
 * the platform has just looked at a business registration and a KRA PIN, and the seller document pack asks a
 * different question of a different kind of applicant. Suspension moves it back to {@code PENDING}, which
 * drops the catalogue-write permissions while leaving everything already published where it is.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VendorService {

    /** The global group every vendor belongs to. Created by the seeder, like the agent's. */
    public static final String VENDOR_GROUP_NAME = "Vendor";

    private final VendorProfileRepository vendors;
    private final VendorCategoryService categories;
    private final CatalogueItemRepository items;
    private final UserRepository users;
    private final UserTypeRepository userTypes;
    private final UserGroupRepository userGroups;
    private final UserProfileRepository profiles;
    private final UserProfileService userProfiles;
    private final PasswordService passwords;
    private final TenantService tenants;
    private final ConfigurationService configs;
    private final StorageService storage;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record VendorResponse(
            String reference,
            String businessName,
            String contactName,
            String email,
            String phone,
            String categoryCode,
            String categoryName,
            String registrationNumber,
            String kraPin,
            String counties,
            String about,
            String website,
            String logoUrl,
            String state,
            String organisationName,
            OffsetDateTime decidedAt,
            String decisionNote,
            /** Published items only — a draft is not something a directory should count. */
            long liveItems,
            Integer status,
            String statusFlag,
            OffsetDateTime createdAt) {}

    public record RegisterVendorRequest(
            @NotBlank(message = "A business name is required") @Size(max = 255) String businessName,
            @NotBlank(message = "A contact name is required") @Size(max = 160) String contactName,
            @NotBlank(message = "An email address is required") @Email @Size(max = 128) String email,
            @NotBlank(message = "A phone number is required") @Size(max = 32) String phone,
            @NotBlank(message = "Choose a password") String password,
            @NotBlank(message = "Choose what you offer") String categoryCode,
            @Size(max = 64) String registrationNumber,
            @Size(max = 32) String kraPin,
            String counties,
            String about,
            @Size(max = 255) String website) {}

    public record UpdateVendorRequest(
            @Size(max = 255) String businessName,
            @Size(max = 160) String contactName,
            @Size(max = 32) String phone,
            String categoryCode,
            @Size(max = 64) String registrationNumber,
            @Size(max = 32) String kraPin,
            String counties,
            String about,
            @Size(max = 255) String website) {}

    public record DecisionRequest(
            @NotBlank(message = "Say what you are doing") String decision,
            String note) {}

    public record RegistrationOutcome(String reference, String state, String message) {}

    @Getter
    @Setter
    public static class VendorListRequest extends PagedDataRequest {
        private String state;
        private String categoryCode;
    }

    public record VendorCounts(long pending, long approved, long suspended) {}

    // ── applying ──────────────────────────────────────────────────────────────

    /**
     * A business applying to be listed.
     *
     * <p>Public, and safe for the three reasons the agent application is safe: it lands PENDING, the
     * profile's KYC standing fails the gate, and there is no organisation to publish into until approval
     * creates one. What a vendor could do with an unapproved account is read the directory.
     */
    @Transactional
    public RegistrationOutcome register(RegisterVendorRequest request) {
        if (!configs.getBoolean(ConfigKey.VENDOR_SELF_REGISTRATION_ENABLED)) {
            throw new HodiException("Vendor applications are closed at the moment.", HttpStatus.FORBIDDEN);
        }
        VendorCategory category = categories.requireUsable(request.categoryCode());

        String email = request.email().trim().toLowerCase();
        if (users.existsByEmail(email)) {
            throw new DuplicateResourceException(
                    "An account already exists for that email address. Sign in and apply from there, or use "
                            + "a different address.");
        }

        UserType vendorType = userTypes.findByCode("VENDOR")
                .orElseThrow(() -> new HodiException(
                        "The VENDOR user type is missing — the seeder has not run.",
                        HttpStatus.INTERNAL_SERVER_ERROR));
        UserGroup vendorGroup = userGroups.findGlobalByName(VENDOR_GROUP_NAME)
                .orElseThrow(() -> new HodiException(
                        "The Vendor group is missing — the seeder has not run.",
                        HttpStatus.INTERNAL_SERVER_ERROR));

        String[] names = splitName(request.contactName());
        User user = User.builder()
                .firstName(names[0])
                .lastName(names[1])
                .email(email)
                .username(deriveUsername(email))
                .phone(request.phone().trim())
                .enabled(true)
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy("vendor-application")
                .build();
        passwords.applyTo(user, request.password());
        User saved = users.save(user);

        UserProfile profile = userProfiles.provisionFirst(
                saved.getId(), vendorType, vendorGroup, null, null, null, null);
        profile.setKycStatus(AppConstant.KYC_PENDING);

        VendorProfile vendor = vendors.save(VendorProfile.builder()
                .reference(RrnGenerator.generate("VN"))
                .userId(saved.getId())
                .profileId(profile.getId())
                .businessName(request.businessName().trim())
                .contactName(request.contactName().trim())
                .email(email)
                .phone(saved.getPhone())
                .categoryId(category.getId())
                .categoryName(category.getName())
                .categoryCode(category.getCode())
                .registrationNumber(blankToNull(request.registrationNumber()))
                .kraPin(blankToNull(request.kraPin()))
                .counties(blankToNull(request.counties()))
                .about(blankToNull(request.about()))
                .website(blankToNull(request.website()))
                .state(VendorState.PENDING)
                .createdBy("vendor-application")
                .build());

        audit.record(AppConstant.AUDIT_VENDOR_REGISTER, "VendorProfile", vendor.getId(), null,
                "applied as " + vendor.getBusinessName() + " under " + category.getName());
        log.info("Vendor application {} received from {}", vendor.getReference(), email);

        return new RegistrationOutcome(vendor.getReference(), vendor.getState(),
                "Your application has been received. You can sign in now, and you will be able to publish "
                        + "your services once the platform has approved it.");
    }

    // ── reading ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<VendorResponse> list(VendorListRequest request) {
        Long categoryId = request.getCategoryCode() == null || request.getCategoryCode().isBlank()
                ? null
                : categories.require(request.getCategoryCode()).getId();
        Specification<VendorProfile> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                SearchSpecs.eq("state", blankToNull(request.getState())),
                SearchSpecs.eq("categoryId", categoryId));
        var page = vendors.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public VendorResponse find(String reference) {
        return toResponse(require(reference));
    }

    @Transactional(readOnly = true)
    public VendorCounts counts() {
        return new VendorCounts(
                vendors.countByState(VendorState.PENDING),
                vendors.countByState(VendorState.APPROVED),
                vendors.countByState(VendorState.SUSPENDED));
    }

    @Transactional(readOnly = true)
    public VendorResponse mine() {
        return toResponse(requireMine());
    }

    // ── decisions ─────────────────────────────────────────────────────────────

    @Transactional
    public VendorResponse decide(String reference, DecisionRequest request) {
        VendorProfile vendor = require(reference);
        String decision = request.decision().trim().toUpperCase();
        String before = snapshot(vendor);

        switch (decision) {
            case "APPROVE" -> approve(vendor);
            case "REJECT" -> reject(vendor, request.note());
            case "SUSPEND" -> suspend(vendor, request.note());
            case "REINSTATE" -> reinstate(vendor);
            default -> throw new HodiException(
                    "Approve, reject, suspend or reinstate — \"" + request.decision() + "\" is none of those.",
                    HttpStatus.BAD_REQUEST);
        }

        vendor.setDecidedAt(OffsetDateTime.now());
        vendor.setDecidedByUserId(AuthContext.userId());
        vendor.setDecisionNote(blankToNull(request.note()));
        vendor.setUpdatedBy(AuthContext.username());
        VendorProfile saved = vendors.save(vendor);

        audit.record(AppConstant.AUDIT_VENDOR_DECIDED, "VendorProfile", saved.getId(), before,
                snapshot(saved));
        log.info("Vendor {} {}", saved.getReference(), decision.toLowerCase());
        return toResponse(saved);
    }

    private void approve(VendorProfile vendor) {
        if (!vendor.isPending()) {
            throw new HodiException("Only a pending application can be approved.", HttpStatus.CONFLICT);
        }
        UserProfile profile = requireProfile(vendor);
        Tenant organisation = tenants.createForVendor(
                vendor.getBusinessName(), vendor.getContactName(), vendor.getEmail(), vendor.getPhone());

        profile.setTenantId(organisation.getId());
        profile.setTenantName(organisation.getName());
        profile.setKycStatus(AppConstant.KYC_APPROVED);
        profile.setUpdatedBy(AuthContext.username());
        profiles.save(profile);

        vendor.setTenantId(organisation.getId());
        vendor.setState(VendorState.APPROVED);
    }

    private void reject(VendorProfile vendor, String note) {
        if (!vendor.isPending()) {
            throw new HodiException("Only a pending application can be rejected.", HttpStatus.CONFLICT);
        }
        if (note == null || note.isBlank()) {
            throw new HodiException("Say why the application is being refused.", HttpStatus.BAD_REQUEST);
        }
        vendor.setState(VendorState.REJECTED);
    }

    /**
     * Suspension takes the catalogue down as well as the permissions.
     *
     * <p>Different from an agent, and the difference is the point: an agent's listings are somebody's house,
     * with buyers mid-enquiry on it, and taking them down would punish the wrong people. A vendor's
     * catalogue is that vendor's own prices — leaving them on a public directory while the platform has
     * suspended them is the platform continuing to recommend somebody it has just stopped trusting.
     */
    private void suspend(VendorProfile vendor, String note) {
        if (!VendorState.APPROVED.equals(vendor.getState())) {
            throw new HodiException("Only an approved vendor can be suspended.", HttpStatus.CONFLICT);
        }
        if (note == null || note.isBlank()) {
            throw new HodiException("Say why the vendor is being suspended.", HttpStatus.BAD_REQUEST);
        }
        UserProfile profile = requireProfile(vendor);
        profile.setKycStatus(AppConstant.KYC_PENDING);
        profile.setUpdatedBy(AuthContext.username());
        profiles.save(profile);

        int taken = 0;
        for (CatalogueItem item : items.findLiveForVendor(vendor.getId())) {
            item.setState(VendorState.ITEM_WITHDRAWN);
            item.setWithdrawnAt(OffsetDateTime.now());
            item.setWithdrawnReason("The vendor was suspended: " + note.trim());
            item.setUpdatedBy(AuthContext.username());
            items.save(item);
            taken++;
        }
        if (taken > 0) log.info("Withdrew {} catalogue item(s) with vendor {}", taken, vendor.getReference());

        vendor.setState(VendorState.SUSPENDED);
    }

    /**
     * Reinstatement does not put the catalogue back.
     *
     * <p>The items are theirs to publish again, and each one goes through approval as it did the first time.
     * Restoring them automatically would republish prices that may be months stale, without anybody — the
     * vendor included — having looked at them.
     */
    private void reinstate(VendorProfile vendor) {
        if (!VendorState.SUSPENDED.equals(vendor.getState())) {
            throw new HodiException("Only a suspended vendor can be reinstated.", HttpStatus.CONFLICT);
        }
        UserProfile profile = requireProfile(vendor);
        profile.setKycStatus(AppConstant.KYC_APPROVED);
        profile.setUpdatedBy(AuthContext.username());
        profiles.save(profile);
        vendor.setState(VendorState.APPROVED);
    }

    // ── the vendor's own details ──────────────────────────────────────────────

    @Transactional
    public VendorResponse updateMine(UpdateVendorRequest request) {
        VendorProfile vendor = requireMine();
        String before = snapshot(vendor);

        if (request.businessName() != null && !request.businessName().isBlank()) {
            vendor.setBusinessName(request.businessName().trim());
        }
        if (request.contactName() != null && !request.contactName().isBlank()) {
            vendor.setContactName(request.contactName().trim());
        }
        if (request.phone() != null) vendor.setPhone(blankToNull(request.phone()));
        if (request.categoryCode() != null && !request.categoryCode().isBlank()) {
            VendorCategory category = categories.requireUsable(request.categoryCode());
            vendor.setCategoryId(category.getId());
            vendor.setCategoryName(category.getName());
            vendor.setCategoryCode(category.getCode());
        }
        if (request.registrationNumber() != null) {
            vendor.setRegistrationNumber(blankToNull(request.registrationNumber()));
        }
        if (request.kraPin() != null) vendor.setKraPin(blankToNull(request.kraPin()));
        if (request.counties() != null) vendor.setCounties(blankToNull(request.counties()));
        if (request.about() != null) vendor.setAbout(blankToNull(request.about()));
        if (request.website() != null) vendor.setWebsite(blankToNull(request.website()));

        vendor.setUpdatedBy(AuthContext.username());
        VendorProfile saved = vendors.save(vendor);
        audit.record(AppConstant.ACTION_UPDATE, "VendorProfile", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public VendorResponse uploadLogo(MultipartFile file) {
        VendorProfile vendor = requireMine();
        StorageService.Stored stored = storage.store(file, "vendors");
        vendor.setLogoKey(stored.key());
        vendor.setUpdatedBy(AuthContext.username());
        VendorProfile saved = vendors.save(vendor);
        audit.record(AppConstant.ACTION_UPDATE, "VendorProfile", saved.getId(), null, "logo replaced");
        return toResponse(saved);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    VendorProfile require(String reference) {
        return vendors.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor", reference));
    }

    /** The signed-in vendor's own row: by active profile first, then by person. */
    VendorProfile requireMine() {
        Long profileId = AuthContext.current().map(p -> p.getProfileId()).orElse(null);
        if (profileId != null) {
            var byProfile = vendors.findByProfileId(profileId);
            if (byProfile.isPresent()) return byProfile.get();
        }
        return vendors.findFirstByUserIdOrderByIdDesc(AuthContext.requireUserId())
                .orElseThrow(() -> new HodiException("You are not registered as a vendor.",
                        HttpStatus.FORBIDDEN));
    }

    /** A vendor's reference by id, for the rows that carry the id and display the reference. */
    String referenceOf(Long vendorId) {
        return vendorId == null ? null
                : vendors.findById(vendorId).map(VendorProfile::getReference).orElse(null);
    }

    private UserProfile requireProfile(VendorProfile vendor) {
        return profiles.findById(vendor.getProfileId())
                .orElseThrow(() -> new HodiException("That vendor's account profile is missing.",
                        HttpStatus.INTERNAL_SERVER_ERROR));
    }

    VendorResponse toResponse(VendorProfile v) {
        return new VendorResponse(
                v.getReference(), v.getBusinessName(), v.getContactName(), v.getEmail(), v.getPhone(),
                v.getCategoryCode(), v.getCategoryName(), v.getRegistrationNumber(), v.getKraPin(),
                v.getCounties(), v.getAbout(), v.getWebsite(), storage.urlFor(v.getLogoKey()),
                v.getState(),
                v.getTenantId() == null ? null
                        : profiles.findById(v.getProfileId()).map(UserProfile::getTenantName).orElse(null),
                v.getDecidedAt(), v.getDecisionNote(),
                items.countByVendorIdAndStateAndStatusNot(
                        v.getId(), VendorState.ITEM_LIVE, AppConstant.STATUS_DELETED),
                v.getStatus(), v.getStatusFlag(), v.getCreatedAt());
    }

    private String deriveUsername(String email) {
        String base = email.substring(0, email.indexOf('@')).replaceAll("[^a-zA-Z0-9._-]", "");
        if (base.isEmpty()) base = "vendor";
        if (!users.existsByUsernameIgnoreCase(base)) return base;
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = base + suffix;
            if (!users.existsByUsernameIgnoreCase(candidate)) return candidate;
        }
        throw new HodiException("Could not derive a username.", HttpStatus.CONFLICT);
    }

    /** A contact is given as one name; the user record wants two. A single word is a first name. */
    private static String[] splitName(String full) {
        String trimmed = full.trim().replaceAll("\\s+", " ");
        int space = trimmed.indexOf(' ');
        return space < 0
                ? new String[]{trimmed, trimmed}
                : new String[]{trimmed.substring(0, space), trimmed.substring(space + 1)};
    }

    private static String snapshot(VendorProfile v) {
        return "{\"reference\":\"%s\",\"state\":\"%s\",\"category\":\"%s\",\"tenantId\":%s}".formatted(
                v.getReference(), v.getState(), v.getCategoryName(), String.valueOf(v.getTenantId()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
