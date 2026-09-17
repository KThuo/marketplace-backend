package com.hodi.modules.sellers;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.exception.DuplicateResourceException;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.kyc.DocumentService;
import com.hodi.modules.kyc.KycRequirement;
import com.hodi.modules.kyc.KycRequirementRepository;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.profiles.UserProfileService;
import com.hodi.modules.sellers.SellerDtos.*;
import com.hodi.modules.sellers.identity.IdentityCheckProvider;
import com.hodi.modules.tenants.TenantService;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A seller applies to sell through the bank, and the bank decides.
 *
 * <h2>Why the account exists before the decision</h2>
 *
 * <p>Because the application is finished while signed in. Registering takes a name, a way to reach them
 * and a password; everything else — the organisation, the documents — is asked afterwards, which is what
 * "send creds, then allow the rest" means.
 *
 * <p>It grants nothing. {@code tenantId} on the profile is null, so there is no organisation to put a
 * listing in, and the profile's {@code kyc_status} is {@code PENDING}, which
 * {@code EffectivePermissionResolver} reads to withhold every listing verb. The same reasoning is already
 * written on {@code AGENT_SELF_REGISTRATION_ENABLED} and holds identically here.
 *
 * <h2>The two paths through identity</h2>
 *
 * <p>An applicant with a Co-op account is somebody the bank has already identified: it opened that account,
 * which means it has run AML and confirmed the person against the registry. So a validated account both
 * supplies the details and stands in for those checks, and the rows say so — {@code SKIPPED_COOP_VERIFIED}
 * rather than no row at all, because a reviewer seeing no AML line cannot tell whether it passed, failed or
 * was never attempted.
 *
 * <p>An applicant without one types their details and is screened. Both providers are stubs today and
 * report themselves unconfigured, so what is recorded is {@code PENDING_INTEGRATION} and what the applicant
 * is shown says the check is not connected yet. A stub that returned {@code PASS} would be
 * indistinguishable from a real pass the day the integration lands.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SellerApplicationService {

    private final SellerApplicationRepository repository;
    private final SellerIdentityCheckRepository checks;
    private final SellerApplicationDocumentRepository documents;
    private final KycRequirementRepository requirements;
    private final DocumentService vault;
    private final UserRepository users;
    private final UserProfileRepository profiles;
    private final UserProfileService userProfiles;
    private final UserTypeRepository userTypes;
    private final UserGroupRepository userGroups;
    private final PasswordService passwords;
    private final TenantService tenants;
    private final AuditService audit;
    private final List<IdentityCheckProvider> providers;

    private Map<String, IdentityCheckProvider> byCode() {
        return providers.stream().collect(Collectors.toMap(
                IdentityCheckProvider::code, Function.identity(), (a, b) -> a));
    }

    // ── applying ──────────────────────────────────────────────────────────────

    /**
     * Creates the account and the application in one transaction.
     *
     * <p>Told plainly when the address is taken, for the reason the agent path already gives: a buyer
     * account is a private fact and confirming one exists is an enumeration oracle, but a seller
     * application is a business relationship with somebody who will be publicly listed. They will ring the
     * bank either way, and leaving them staring at an application that was never created is worse than the
     * disclosure.
     */
    @Transactional
    public ApplyOutcome apply(ApplyRequest request) {
        String email = request.email().trim().toLowerCase();
        if (users.existsByEmail(email)) {
            throw new DuplicateResourceException(
                    "An account already exists for that email address. Sign in and apply from there, or "
                            + "use a different address.");
        }

        UserType sellerOwner = userTypes.findByCode("SELLER_OWNER")
                .orElseThrow(() -> new HodiException(
                        "The SELLER_OWNER user type is missing — the seeder has not run.",
                        HttpStatus.INTERNAL_SERVER_ERROR));

        User user = User.builder()
                .firstName(request.firstName().trim())
                .lastName(request.lastName().trim())
                .email(email)
                .username(deriveUsername(email))
                .phone(request.phone().trim())
                .enabled(true)
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy("seller-application")
                .build();
        passwords.applyTo(user, request.password());
        User saved = users.save(user);

        /*
         * No group and no organisation.
         *
         * They hold the SELLER_OWNER type so the KYC module admits them — that module's audience is what
         * decides whether they can reach the screen that collects their own documents — and no group, so
         * they resolve no permissions at all until approval attaches them to their organisation's owner
         * group. Granting first and gating with KYC is the agent's approach; here there is no group to
         * grant from yet, so the two differ and this is the stricter of them.
         */
        UserProfile profile = userProfiles.provisionFirst(
                saved.getId(), sellerOwner, null, null, null, null, null);
        profile.setKycStatus(AppConstant.KYC_PENDING);
        profiles.save(profile);

        boolean claimsCoop = Boolean.TRUE.equals(request.hasCoopAccount())
                && request.coopAccountNumber() != null && !request.coopAccountNumber().isBlank();

        SellerApplication application = repository.save(SellerApplication.builder()
                .reference(RrnGenerator.generate("SA"))
                .userId(saved.getId())
                .profileId(profile.getId())
                .fullName(saved.fullName())
                .email(email)
                .phone(saved.getPhone())
                .identitySource(SellerState.SOURCE_SELF)
                .coopAccountNumber(claimsCoop ? request.coopAccountNumber().trim() : null)
                .state(SellerState.DRAFT)
                .createdBy("seller-application")
                .build());

        if (claimsCoop) {
            runCoopValidation(application);
        } else {
            runScreening(application);
        }

        audit.record(AppConstant.ACTION_CREATE, "SellerApplication", application.getId(), null,
                "applied as " + application.getFullName());
        log.info("Seller application {} received from {}", application.getReference(), email);

        return new ApplyOutcome(application.getReference(), application.getState(), saved.getUsername(),
                "Your application has started. Sign in to finish it — you will be able to list property "
                        + "once the bank has approved you.");
    }

    // ── the checks ────────────────────────────────────────────────────────────

    /**
     * Validates a Co-op account and, when it can, takes the holder's details from it.
     *
     * <p>A verified account moves {@code identity_source} to {@code COOP_ACCOUNT} and stands the AML and
     * IPRS checks down, recording why. While the provider is a stub nothing is verified, so the applicant
     * falls back to typing their details and is screened like anybody else — which is the behaviour the
     * day the integration is switched on too, for anybody whose account fails to validate.
     */
    @Transactional
    public SellerApplication runCoopValidation(SellerApplication application) {
        IdentityCheckProvider provider = byCode().get(SellerState.CHECK_COOP);
        if (provider == null) return application;

        var result = provider.run(requestFor(application));
        record(application, SellerState.CHECK_COOP, result);

        boolean verified = SellerState.VERDICT_PASS.equals(result.verdict());
        application.setCoopAccountVerified(verified);
        if (verified) {
            application.setIdentitySource(SellerState.SOURCE_COOP);
            var person = result.person();
            if (person != null) {
                if (person.fullName() != null) application.setFullName(person.fullName());
                if (person.phone() != null) application.setPhone(person.phone());
                if (person.idNumber() != null) application.setIdNumber(person.idNumber());
                if (person.kraPin() != null) application.setKraPin(person.kraPin());
            }
            // The bank ran both to open the account. Recorded rather than omitted.
            standDown(application, SellerState.CHECK_AML);
            standDown(application, SellerState.CHECK_IPRS);
        } else {
            runScreening(application);
        }
        return repository.save(application);
    }

    /** AML and IPRS, for an applicant the bank has not already identified. */
    private void runScreening(SellerApplication application) {
        for (String code : List.of(SellerState.CHECK_AML, SellerState.CHECK_IPRS)) {
            IdentityCheckProvider provider = byCode().get(code);
            if (provider == null) continue;
            record(application, code, provider.run(requestFor(application)));
        }
    }

    private void standDown(SellerApplication application, String code) {
        checks.save(SellerIdentityCheck.builder()
                .applicationId(application.getId())
                .checkCode(code)
                .verdict(SellerState.VERDICT_SKIPPED_COOP)
                .detail("Not run: the bank identified this person to open their Co-op account.")
                .createdBy("seller-application")
                .build());
    }

    private void record(SellerApplication application, String code,
                        IdentityCheckProvider.CheckResult result) {
        checks.save(SellerIdentityCheck.builder()
                .applicationId(application.getId())
                .checkCode(code)
                .verdict(result.verdict())
                .providerRef(result.providerRef())
                .detail(result.detail())
                .createdBy("seller-application")
                .build());
    }

    private IdentityCheckProvider.CheckRequest requestFor(SellerApplication a) {
        return new IdentityCheckProvider.CheckRequest(a.getFullName(), a.getEmail(), a.getPhone(),
                a.getIdNumber(), a.getKraPin(), a.getCoopAccountNumber());
    }

    // ── the applicant's own ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public SellerApplicationResponse mine() {
        return toResponse(requireMine());
    }

    @Transactional
    public SellerApplicationResponse save(SaveApplicationRequest request) {
        SellerApplication application = requireMine();
        if (!application.isOpenToApplicant()) {
            throw new HodiException(
                    "This application is with the bank. You will be able to change it if they ask for "
                            + "anything further.", HttpStatus.CONFLICT);
        }
        application.setIdNumber(blankToNull(request.idNumber()));
        application.setKraPin(blankToNull(request.kraPin()));
        application.setOrganisationName(blankToNull(request.organisationName()));
        application.setSellerType(sellerType(request.sellerType()));
        application.setRegistrationNumber(blankToNull(request.registrationNumber()));
        application.setCounty(blankToNull(request.county()));
        application.setTown(blankToNull(request.town()));
        application.setAddressLine(blankToNull(request.addressLine()));
        application.setUpdatedBy(AuthContext.username());
        return toResponse(repository.save(application));
    }

    /**
     * Hands it to the bank.
     *
     * <p>What is checked here is that the application is answerable, not that it is good — deciding whether
     * a CR12 is convincing is the reviewer's job, and a gate that tried would be refusing on the bank's
     * behalf without the bank's judgement.
     */
    @Transactional
    public SellerApplicationResponse submit() {
        SellerApplication application = requireMine();
        if (!application.isOpenToApplicant()) {
            throw new HodiException("That has already been submitted.", HttpStatus.CONFLICT);
        }
        List<String> missing = outstanding(application);
        if (!missing.isEmpty()) {
            throw new HodiException("Still needed: " + String.join(", ", missing), HttpStatus.BAD_REQUEST);
        }

        application.setState(SellerState.SUBMITTED);
        application.setSubmittedAt(OffsetDateTime.now());
        application.setUpdatedBy(AuthContext.username());
        SellerApplication saved = repository.save(application);

        audit.record(AppConstant.ACTION_UPDATE, "SellerApplication", saved.getId(), null,
                "submitted for review");
        log.info("Seller application {} submitted", saved.getReference());
        return toResponse(saved);
    }

    /** What is still missing. Named rather than counted, so the screen can say which. */
    /**
     * One of the six, or nothing.
     *
     * <p>Refused rather than stored as typed. An unrecognised type resolves to an empty checklist, which
     * {@link #outstanding} then reads as "nothing missing" — so the hole this closes is not a cosmetic one:
     * it let an application through to a reviewer carrying no evidence whatsoever, and looking complete.
     */
    private static String sellerType(String requested) {
        String value = blankToNull(requested);
        if (value == null) return null;
        String type = value.trim().toUpperCase();
        if (!SellerState.SELLER_TYPES.contains(type)) {
            throw new HodiException("That is not a kind of seller on this platform.",
                    HttpStatus.BAD_REQUEST);
        }
        return type;
    }

    private List<String> outstanding(SellerApplication a) {
        List<String> missing = new ArrayList<>();
        if (isBlank(a.getIdNumber())) missing.add("an ID number");
        if (isBlank(a.getSellerType())) missing.add("the kind of seller you are");
        /*
         * A type with no checklist behind it is as good as no type.
         *
         * `checklistFor` returns nothing when the requirement catalogue has no current version for the
         * seller type, and the loop below would then find nothing outstanding — an application submittable
         * with no documents at all. Saying so is better than the silence that produced it.
         */
        else if (checklistFor(a).isEmpty()) {
            missing.add("the documents for a " + a.getSellerType().toLowerCase()
                    + " — ask the bank, the checklist for that kind of seller is not set up");
        }
        if (isBlank(a.getOrganisationName())) missing.add("a trading name");
        if (isBlank(a.getCounty())) missing.add("a county");
        /*
         * And every required document. This is the half of point 4 that matters: an application submitted
         * without them puts a reviewer in front of a decision they cannot make, and the polite version of
         * that is a queue of send-backs asking for what the form should have insisted on.
         */
        for (RequiredDocument required : checklistFor(a)) {
            if (required.required() && !required.uploaded()) missing.add(required.name());
        }
        return missing;
    }

    // ── documents ─────────────────────────────────────────────────────────────

    /**
     * The checklist for the kind of seller they said they are, with what they have answered.
     *
     * <p>Read from {@code kyc_requirement_configs} rather than a list of its own — ID, KRA PIN and CR12
     * are already in there, already versioned per seller type, and already what Compliance reviews against
     * once the seller exists. A second catalogue here would be the one that falls behind.
     *
     * <p>Empty before they have chosen a seller type, which is why that question comes first.
     */
    @Transactional(readOnly = true)
    public List<RequiredDocument> requiredDocuments() {
        SellerApplication application = requireMine();
        return checklistFor(application);
    }

    private List<RequiredDocument> checklistFor(SellerApplication application) {
        String entityType = application.getSellerType() == null || application.getSellerType().isBlank()
                ? null : application.getSellerType().trim().toUpperCase();
        if (entityType == null) return List.of();

        Integer version = requirements.currentVersion(entityType);
        if (version == null) return List.of();

        Map<String, SellerApplicationDocument> held =
                documents.findLiveFor(application.getId()).stream()
                        .collect(Collectors.toMap(SellerApplicationDocument::getDocumentCode,
                                Function.identity(), (a, b) -> a));

        List<RequiredDocument> out = new ArrayList<>();
        for (KycRequirement requirement : requirements.findFor(entityType, version)) {
            SellerApplicationDocument document = held.get(requirement.getDocumentCode());
            out.add(new RequiredDocument(
                    requirement.getDocumentCode(),
                    requirement.getDocumentName(),
                    requirement.getDescription(),
                    requirement.isRequired(),
                    document != null,
                    document == null ? null : document.getOriginalName(),
                    document == null ? null : document.getUploadedAt()));
        }
        return out;
    }

    /**
     * Stores one, replacing whatever answered that line before.
     *
     * <p>Through {@code DocumentService.store} with a null tenant — its own signature calls that "a
     * document about a person", which is exactly an applicant. That is what keeps the checksum, the ACL
     * and the audited read; a second writer into the vault would have none of them.
     *
     * <p>Replacing rather than accumulating: a checklist with two answers to one question is a reviewer
     * asking which counts. The old row is archived rather than deleted, so the trail keeps what was sent
     * first.
     */
    @Transactional
    public List<RequiredDocument> uploadDocument(String documentCode, MultipartFile file) {
        SellerApplication application = requireMine();
        if (!application.isOpenToApplicant()) {
            throw new HodiException("This application is with the bank.", HttpStatus.CONFLICT);
        }
        String code = documentCode == null ? "" : documentCode.trim().toUpperCase();
        boolean known = checklistFor(application).stream()
                .anyMatch(r -> r.code().equals(code));
        if (!known) {
            throw new HodiException("That is not a document this application asks for.",
                    HttpStatus.BAD_REQUEST);
        }

        var stored = vault.store(file, "seller-applications", code, code,
                null, application.getUserId(), null, null, "SELLERS_VIEW");

        /*
         * Flushed, not merely saved.
         *
         * The partial unique index counts rows Postgres can see, and a save left in the persistence
         * context is not one of them — Hibernate would order the INSERT before the UPDATE and the index
         * would refuse a replacement that is perfectly legal. Flushing the archive first is what makes
         * "replace" mean replace.
         */
        documents.findLiveLine(application.getId(), code).ifPresent(previous -> {
            previous.setStatus(AppConstant.STATUS_DELETED);
            previous.setStatusFlag(AppConstant.FLAG_DELETED);
            previous.setUpdatedBy(AuthContext.username());
            documents.saveAndFlush(previous);
        });

        documents.save(SellerApplicationDocument.builder()
                .applicationId(application.getId())
                .documentId(stored.getId())
                .documentCode(code)
                .originalName(stored.getOriginalName())
                .createdBy(AuthContext.username())
                .build());

        audit.record(AppConstant.ACTION_CREATE, "SellerApplicationDocument", stored.getId(), null,
                code + " uploaded for " + application.getReference());
        return checklistFor(application);
    }

    /** One line of the checklist, and whether it has been answered. */
    public record RequiredDocument(String code, String name, String description, boolean required,
                                   boolean uploaded, String fileName,
                                   java.time.OffsetDateTime uploadedAt) {}

    // ── the bank's side ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<SellerApplicationResponse> list(SellerApplicationListRequest request) {
        Specification<SellerApplication> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("state", blankToNull(request.getState())));
        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "id")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public SellerApplicationResponse find(String reference) {
        return toResponse(require(reference));
    }

    @Transactional(readOnly = true)
    public long waitingCount() {
        return repository.countWaiting();
    }

    /**
     * The bank's decision.
     *
     * <p>Approval is what creates the organisation — that is the moment a seller exists, and doing it here
     * rather than at registration is what makes "cannot list until approved" structural rather than a
     * permission somebody could grant by mistake.
     */
    @Transactional
    public SellerApplicationResponse decide(String reference, DecisionRequest request) {
        UserPrincipal caller = AuthContext.require();
        SellerApplication application = require(reference);
        if (!application.isAwaitingDecision()) {
            throw new HodiException("Only a submitted application can be decided.", HttpStatus.CONFLICT);
        }

        String decision = normalise(request.decision());
        if (!SellerState.APPROVED.equals(decision)
                && (request.note() == null || request.note().isBlank())) {
            // Refusing or asking for more without saying what is a dead end for the applicant: they cannot
            // tell whether to fix something or give up.
            throw new HodiException("Say what is wrong, or what else you need.", HttpStatus.BAD_REQUEST);
        }

        switch (decision) {
            case SellerState.APPROVED -> approve(application);
            case SellerState.MORE_INFO -> application.setState(SellerState.MORE_INFO);
            default -> refuse(application);
        }

        if (!SellerState.MORE_INFO.equals(decision)) {
            application.setDecidedAt(OffsetDateTime.now());
            application.setDecidedByUserId(caller.getUserId());
        }
        application.setDecisionNote(blankToNull(request.note()));
        application.setUpdatedBy(AuthContext.username());
        SellerApplication saved = repository.save(application);

        audit.record(AppConstant.ACTION_UPDATE, "SellerApplication", saved.getId(), null,
                decision + " by " + caller.getUsername());
        log.info("Seller application {} {} by {}", saved.getReference(), decision, caller.getUsername());
        return toResponse(saved);
    }

    private void approve(SellerApplication application) {
        UserProfile profile = profiles.findById(application.getProfileId())
                .orElseThrow(() -> new IllegalStateException(
                        "Application " + application.getReference() + " has no profile"));

        var organisation = tenants.createForSeller(application.tradingName(), application.getFullName(),
                application.getEmail(), application.getPhone(), application.getSellerType());
        UserGroup ownerGroup = organisation.ownerGroup();

        profile.setTenantId(organisation.tenant().getId());
        profile.setTenantName(organisation.tenant().getName());
        profile.setUserGroupId(ownerGroup.getId());
        profile.setUserGroupName(ownerGroup.getName());
        /*
         * Approved, not cleared-by-documents.
         *
         * The bank has read the pack and decided. Leaving the profile PENDING here would mean an approved
         * seller who still cannot list — the gate would be holding against a decision that has been made,
         * which is the one failure mode nobody would look for.
         */
        profile.setKycStatus(AppConstant.KYC_APPROVED);
        profile.setUpdatedBy(AuthContext.username());
        profiles.save(profile);

        application.setTenantId(organisation.tenant().getId());
        application.setState(SellerState.APPROVED);
    }

    private void refuse(SellerApplication application) {
        application.setState(SellerState.REJECTED);
        // The account stays and stays disabled from selling: the profile never clears the listing gate.
        // Deleting it would free the email address for a second application at a different reviewer.
    }

    private static String normalise(String raw) {
        String decision = raw == null ? "" : raw.trim().toUpperCase();
        return switch (decision) {
            case SellerState.APPROVED, SellerState.REJECTED, SellerState.MORE_INFO -> decision;
            default -> throw new HodiException(
                    "A decision is approve, reject, or ask for more.", HttpStatus.BAD_REQUEST);
        };
    }

    // ── plumbing ──────────────────────────────────────────────────────────────

    private SellerApplication requireMine() {
        Long userId = AuthContext.require().getUserId();
        return repository.findOpenFor(userId)
                .or(() -> repository.findFirstByUserIdOrderByIdDesc(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Seller application", "yours"));
    }

    private SellerApplication require(String reference) {
        return repository.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Seller application", reference));
    }

    /** The local part of the address, de-duplicated. The same rule the agent register uses. */
    private String deriveUsername(String email) {
        String base = email.substring(0, email.indexOf('@')).replaceAll("[^a-zA-Z0-9._-]", "");
        if (base.isBlank()) base = "seller";
        String candidate = base;
        for (int n = 2; users.existsByUsernameIgnoreCase(candidate) && n < 1000; n++) {
            candidate = base + n;
        }
        return candidate;
    }

    private SellerApplicationResponse toResponse(SellerApplication a) {
        List<IdentityCheckResponse> ran = checks.findByApplicationIdOrderByRanAtAsc(a.getId()).stream()
                .map(c -> new IdentityCheckResponse(c.getCheckCode(), c.getVerdict(), c.getDetail(),
                        c.getRanAt()))
                .toList();
        return new SellerApplicationResponse(
                HashIdUtil.encodeId(a.getId()),
                a.getReference(),
                a.getFullName(),
                a.getEmail(),
                a.getPhone(),
                a.getIdNumber(),
                a.getKraPin(),
                a.getIdentitySource(),
                a.getCoopAccountNumber(),
                a.isCoopAccountVerified(),
                a.getOrganisationName(),
                a.getSellerType(),
                a.getRegistrationNumber(),
                a.getCounty(),
                a.getTown(),
                a.getAddressLine(),
                a.getState(),
                a.getSubmittedAt(),
                a.getDecidedAt(),
                a.getDecisionNote(),
                HashIdUtil.encodeId(a.getTenantId()),
                ran,
                a.isOpenToApplicant() ? outstanding(a) : List.of(),
                a.getCreatedAt());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
