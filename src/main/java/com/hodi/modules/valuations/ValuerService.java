package com.hodi.modules.valuations;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.RefGenerator;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.profiles.UserProfileRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * The panel (M5, plan §3.5).
 *
 * <h2>Onboarding a valuer creates a person, a profile and a panel row</h2>
 *
 * <p>The same shape as onboarding a seller's owner: a user, a {@code VALUER} profile carrying the actor
 * class, and the domain row. The profile is what authorisation resolves from and what
 * {@link ValuationScope} matches assignments against; the panel row is what the round robin reads.
 *
 * <p>A valuer belongs to no organisation. That is not an omission — it is what makes their visibility
 * assignment-scoped rather than organisation-scoped, and it means every other module's lists are closed to
 * them by construction, because {@code TenantScope.visibleIds()} is empty.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValuerService {

    private static final String REFERENCE_PREFIX = "VP";

    /** The group every valuer holds. Seeded by name, the same way the buyer group is. */
    public static final String VALUER_GROUP_NAME = "Valuer";

    private final ValuerProfileRepository repository;
    private final ValuationRequestRepository requests;
    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final UserTypeRepository userTypes;
    private final UserGroupRepository userGroups;
    private final com.hodi.modules.profiles.UserProfileService userProfiles;
    private final PasswordService passwords;
    private final ValuationNotifier notifier;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record ValuerResponse(
            String reference,
            String fullName,
            String email,
            String phone,
            String firmName,
            String registrationNumber,
            String registrationBody,
            LocalDate registeredUntil,
            String piInsurer,
            String piPolicyNumber,
            BigDecimal piSumAssured,
            LocalDate piExpiresOn,
            String counties,
            String specialisations,
            boolean onPanel,
            String panelNote,
            /** Whether they could be assigned work right now, and — when not — the reason. */
            boolean available,
            String unavailableReason,
            int openAssignments,
            int completedCount,
            OffsetDateTime lastAssignedAt,
            Integer status,
            String statusFlag,
            OffsetDateTime createdAt) {}

    public record OnboardValuerRequest(
            @NotBlank(message = "A first name is required") @Size(max = 64) String firstName,
            @NotBlank(message = "A last name is required") @Size(max = 64) String lastName,
            @NotBlank(message = "An email address is required")
            @Email(message = "That does not look like an email address")
            @Size(max = 128) String email,
            @Size(max = 32) String phone,
            @Size(max = 255) String firmName,
            @Size(max = 64) String registrationNumber,
            @Size(max = 64) String registrationBody,
            LocalDate registeredUntil,
            @Size(max = 160) String piInsurer,
            @Size(max = 64) String piPolicyNumber,
            BigDecimal piSumAssured,
            LocalDate piExpiresOn,
            String counties,
            String specialisations) {}

    public record UpdateValuerRequest(
            @Size(max = 255) String firmName,
            @Size(max = 64) String registrationNumber,
            @Size(max = 64) String registrationBody,
            LocalDate registeredUntil,
            @Size(max = 160) String piInsurer,
            @Size(max = 64) String piPolicyNumber,
            BigDecimal piSumAssured,
            LocalDate piExpiresOn,
            String counties,
            String specialisations) {}

    public record PanelRequest(String note) {}

    /** What onboarding returns: the panel row, and the valuer's one-time credential. */
    public record OnboardedValuer(ValuerResponse valuer, String username, String temporaryPassword) {}

    @Getter
    @Setter
    public static class ValuerListRequest extends PagedDataRequest {
        /** {@code true} for the panel; {@code false} for those suspended from it. */
        private Boolean onPanel;
        private String county;
        /** {@code true} for those who could be assigned work today. */
        private Boolean available;
    }

    // ── the panel ─────────────────────────────────────────────────────────────

    /**
     * The panel.
     *
     * <p>Scoped, not merely permissioned. {@code VALUER_PANEL_VIEW} gets a caller to this endpoint, and a
     * valuer holds it — but a valuer sees only their own row. Without that they would read every colleague's
     * indemnity insurer, policy number, sum assured and workload, which is commercially sensitive between
     * competing firms and none of their business. The permission is the door; this is the room.
     */
    @Transactional(readOnly = true)
    public PagedResponse<ValuerResponse> list(ValuerListRequest request) {
        Specification<ValuerProfile> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                onPanelIs(request.getOnPanel()),
                coversCounty(request.getCounty()),
                availableIs(request.getAvailable()),
                onlyMineIfValuer());

        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.ASC, "fullName")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public ValuerResponse find(String reference) {
        return toResponse(load(reference));
    }

    /** The signed-in valuer's own panel row — their standing, their load, their cover's expiry. */
    @Transactional(readOnly = true)
    public ValuerResponse me() {
        Long profileId = AuthContext.require().getProfileId();
        return toResponse(repository.findByProfileId(profileId)
                .orElseThrow(() -> new HodiException("You are not on the valuation panel.",
                        HttpStatus.FORBIDDEN)));
    }

    @Transactional
    public OnboardedValuer onboard(OnboardValuerRequest request) {
        String email = request.email().trim().toLowerCase();
        if (users.existsByEmail(email)) {
            throw new HodiException("Somebody already uses that email address.", HttpStatus.CONFLICT);
        }

        UserType type = userTypes.findByCode("VALUER")
                .orElseThrow(() -> new HodiException(
                        "The VALUER user type is missing — the seeder has not run.",
                        HttpStatus.INTERNAL_SERVER_ERROR));
        UserGroup group = userGroups.findGlobalByName(VALUER_GROUP_NAME)
                .orElseThrow(() -> new HodiException(
                        "The Valuer group is missing — the seeder has not run.",
                        HttpStatus.INTERNAL_SERVER_ERROR));

        requireCover(request.piSumAssured());
        String temporary = temporaryPassword();
        User valuer = User.builder()
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .email(email)
                .username(deriveUsername(email))
                .phone(blankToNull(request.phone()))
                .enabled(true)
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy(AuthContext.username())
                .build();
        passwords.applyTo(valuer, temporary);
        // They chose nothing: the platform issued this credential, so it has to be replaced on first use.
        valuer.setMustChangePassword(true);
        User savedUser = users.save(valuer);

        // No organisation, by definition — see the class note.
        UserProfile profile = userProfiles.provisionFirst(
                savedUser.getId(), type, group, null, null, null, null);

        ValuerProfile panel = repository.save(ValuerProfile.builder()
                .userId(savedUser.getId())
                .profileId(profile.getId())
                .reference(nextReference())
                .fullName(savedUser.fullName())
                .firmName(blankToNull(request.firmName()))
                .registrationNumber(blankToNull(request.registrationNumber()))
                .registrationBody(blankToNull(request.registrationBody()))
                .registeredUntil(request.registeredUntil())
                .piInsurer(blankToNull(request.piInsurer()))
                .piPolicyNumber(blankToNull(request.piPolicyNumber()))
                .piSumAssured(request.piSumAssured())
                .piExpiresOn(request.piExpiresOn())
                .counties(normaliseCounties(request.counties()))
                .specialisations(blankToNull(request.specialisations()))
                .createdBy(AuthContext.username())
                .updatedBy(AuthContext.username())
                .build());

        audit.record(AppConstant.ACTION_CREATE, "ValuerProfile", panel.getId(), null,
                panel.getReference() + " " + panel.getFullName());
        // Emailed as well as shown: the administrator on the screen is not the person who needs it.
        notifier.welcome(savedUser, savedUser.getUsername(), temporary);
        return new OnboardedValuer(toResponse(panel), savedUser.getUsername(), temporary);
    }

    /** A county as a token of the CSV, never a substring — the same rule the entity applies. */
    private Specification<ValuerProfile> coversCounty(String county) {
        if (county == null || county.isBlank()) return null;
        String token = county.trim().toUpperCase();
        return (root, query, cb) -> cb.or(
                cb.isNull(root.get("counties")),
                cb.equal(root.get("counties"), ""),
                cb.like(cb.concat(cb.concat(cb.literal(","), cb.upper(root.get("counties"))), cb.literal(",")),
                        "%," + token + ",%"));
    }

    /** The four rules of {@link ValuerProfile#isAvailable}, as a query. */
    private Specification<ValuerProfile> availableIs(Boolean available) {
        if (!Boolean.TRUE.equals(available)) return null;
        LocalDate today = LocalDate.now();
        return (root, query, cb) -> cb.and(
                cb.isTrue(root.get("onPanel")),
                root.get("status").in(AppConstant.STATUS_ACTIVE, AppConstant.STATUS_EDITED),
                cb.greaterThan(root.get("piSumAssured"), BigDecimal.ZERO),
                cb.or(cb.isNull(root.get("piExpiresOn")), cb.greaterThanOrEqualTo(root.get("piExpiresOn"), today)),
                cb.or(cb.isNull(root.get("registeredUntil")), cb.greaterThanOrEqualTo(root.get("registeredUntil"), today)));
    }

    /** Cover is the assignment ceiling; a valuer without a figure cannot be assigned anything. */
    private static void requireCover(BigDecimal sumAssured) {
        if (sumAssured == null || sumAssured.signum() <= 0) {
            throw new HodiException("Enter the professional indemnity cover: it is what decides which jobs "
                    + "they may be assigned.", HttpStatus.BAD_REQUEST);
        }
    }

    @Transactional
    public ValuerResponse update(String reference, UpdateValuerRequest request) {
        ValuerProfile panel = load(reference);
        requireCover(request.piSumAssured());
        String before = snapshot(panel);
        panel.setFirmName(blankToNull(request.firmName()));
        panel.setRegistrationNumber(blankToNull(request.registrationNumber()));
        panel.setRegistrationBody(blankToNull(request.registrationBody()));
        panel.setRegisteredUntil(request.registeredUntil());
        panel.setPiInsurer(blankToNull(request.piInsurer()));
        panel.setPiPolicyNumber(blankToNull(request.piPolicyNumber()));
        panel.setPiSumAssured(request.piSumAssured());
        panel.setPiExpiresOn(request.piExpiresOn());
        panel.setCounties(normaliseCounties(request.counties()));
        panel.setSpecialisations(blankToNull(request.specialisations()));
        panel.setUpdatedBy(AuthContext.username());
        panel.setStatus(AppConstant.STATUS_EDITED);
        panel.setStatusFlag(AppConstant.FLAG_EDITED);
        ValuerProfile saved = repository.save(panel);
        // Cover and registration decide what may be assigned, so a change to them is one an auditor asks about.
        audit.record(AppConstant.ACTION_UPDATE, "ValuerProfile", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    private static String snapshot(ValuerProfile v) {
        return v.getReference() + " cover " + v.getPiSumAssured() + " to " + v.getPiExpiresOn()
                + " registered to " + v.getRegisteredUntil() + " counties " + v.getCounties()
                + (v.isOnPanel() ? " on panel" : " off panel");
    }

    /**
     * Suspends or restores panel membership.
     *
     * <p>Separate from the row's lifecycle: a valuer off the panel for a quarter is a live row that must not
     * be assigned work, which is a different thing from an archived one — and their open jobs stay theirs.
     * Taking those away would leave a requester waiting on somebody who has stopped looking.
     */
    @Transactional
    public ValuerResponse setOnPanel(String reference, boolean onPanel, PanelRequest request) {
        ValuerProfile panel = load(reference);
        String before = snapshot(panel);
        panel.setOnPanel(onPanel);
        panel.setPanelNote(request == null ? null : blankToNull(request.note()));
        panel.setUpdatedBy(AuthContext.username());

        long open = requests.countOpenForValuer(panel.getId());
        if (!onPanel && open > 0) {
            log.info("Valuer {} suspended from the panel with {} job(s) still open — they keep them",
                    panel.getReference(), open);
        }
        ValuerProfile saved = repository.save(panel);
        audit.record(AppConstant.ACTION_UPDATE, "ValuerProfile", saved.getId(), before,
                snapshot(saved) + (saved.getPanelNote() == null ? "" : " — " + saved.getPanelNote()));
        return toResponse(saved);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private ValuerProfile load(String reference) {
        return repository.findByReference(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Valuer", reference));
    }

    /**
     * A valuer sees themselves; everybody else who reaches this endpoint sees the panel.
     *
     * <p>Keyed on the actor class rather than on "has no organisation", because a buyer also has none — and
     * a buyer cannot reach this module at all, but a rule that would have been wrong for them is a rule
     * worth writing correctly.
     */
    private Specification<ValuerProfile> onlyMineIfValuer() {
        var caller = AuthContext.require();
        if (!caller.isValuer()) return null;
        Long profileId = caller.getProfileId();
        if (profileId == null) return (root, query, cb) -> cb.disjunction();
        return (root, query, cb) -> cb.equal(root.get("profileId"), profileId);
    }

    private Specification<ValuerProfile> onPanelIs(Boolean onPanel) {
        if (onPanel == null) return null;
        return (root, query, cb) -> cb.equal(root.get("onPanel"), onPanel);
    }

    /** Upper-cased and de-spaced, because the matcher compares tokens rather than doing a LIKE. */
    private static String normaliseCounties(String counties) {
        if (counties == null || counties.isBlank()) return null;
        return java.util.Arrays.stream(counties.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .distinct()
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }

    private ValuerResponse toResponse(ValuerProfile v) {
        User person = users.findById(v.getUserId()).orElse(null);
        return new ValuerResponse(
                v.getReference(), v.getFullName(),
                person == null ? null : person.getEmail(),
                person == null ? null : person.getPhone(),
                v.getFirmName(), v.getRegistrationNumber(), v.getRegistrationBody(),
                v.getRegisteredUntil(), v.getPiInsurer(), v.getPiPolicyNumber(), v.getPiSumAssured(),
                v.getPiExpiresOn(), v.getCounties(), v.getSpecialisations(), v.isOnPanel(),
                v.getPanelNote(), v.isAvailable(), unavailableReason(v), v.getOpenAssignments(),
                v.getCompletedCount(), v.getLastAssignedAt(), v.getStatus(), v.getStatusFlag(),
                v.getCreatedAt());
    }

    /**
     * Why a valuer cannot take work.
     *
     * <p>Answered on the row rather than left for somebody to work out from four fields. The commonest
     * reason by far is a lapsed indemnity policy, and a panel screen that showed "unavailable" without
     * saying which of the four rules failed would generate a support call every time.
     */
    private static String unavailableReason(ValuerProfile v) {
        if (v.isAvailable()) return null;
        if (!AppConstant.isLive(v.getStatus())) return "Their account is not active.";
        if (!v.isOnPanel()) return "Suspended from the panel.";
        if (v.getPiSumAssured() == null || v.getPiSumAssured().signum() <= 0) {
            return "No professional indemnity cover on file.";
        }
        if (v.getPiExpiresOn() != null && v.getPiExpiresOn().isBefore(LocalDate.now())) {
            return "Their indemnity cover expired on " + v.getPiExpiresOn() + ".";
        }
        if (v.getRegisteredUntil() != null && v.getRegisteredUntil().isBefore(LocalDate.now())) {
            return "Their registration lapsed on " + v.getRegisteredUntil() + ".";
        }
        return "Not available.";
    }

    private String deriveUsername(String email) {
        String base = email.substring(0, email.indexOf('@')).replaceAll("[^a-zA-Z0-9._-]", "");
        String candidate = base.isEmpty() ? "valuer" : base;
        String username = candidate;
        int suffix = 1;
        while (users.existsByUsernameIgnoreCase(username)) {
            username = candidate + suffix++;
        }
        return username;
    }

    /**
     * A one-time credential.
     *
     * <p>Deliberately the same shape as the seller owner's, and deliberately its own copy rather than a
     * shared utility: the two are the same today by coincidence, and a shared generator would make changing
     * one of them change the other. Ten letters, a symbol and a digit, first letter capitalised, which
     * satisfies every rule the password policy can be configured to enforce.
     */
    private static String temporaryPassword() {
        final String alphabet = "abcdefghijkmnpqrstuvwxyz23456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder(14);
        for (int i = 0; i < 10; i++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        sb.append('#').append(random.nextInt(10));
        sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
        return sb.toString();
    }

    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = RefGenerator.getInstance().generate(REFERENCE_PREFIX);
            if (!repository.existsByReference(reference)) return reference;
        }
        throw new HodiException("Could not allocate a reference. Try again.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
