package com.hodi.modules.kyc;

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
import com.hodi.modules.tenants.Tenant;
import com.hodi.modules.tenants.TenantRepository;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * KYC (plan §3.3): what a seller must produce, what they produced, and what Compliance made of it.
 *
 * <h2>The gate this makes live</h2>
 *
 * <p>{@code EffectivePermissionResolver} has dropped {@code PROPERTIES_CREATE} and its siblings for any
 * profile whose {@code kyc_status} is not cleared since Phase 0a — inert until now, because nothing wrote
 * anything but {@code NOT_REQUIRED}. Approving a pack writes {@code APPROVED} onto every live seller profile
 * of that organisation; rejecting writes {@code REJECTED}. The gate is the same choke point the module
 * matrix uses, so a screen that forgets to hide a button still cannot reach the endpoint.
 *
 * <p><strong>Submitting changes nothing about permissions</strong> — see {@link #submit()}. And note what is
 * still open: nothing yet makes KYC <em>mandatory</em>. A seller who never opens a pack stays at
 * {@code NOT_REQUIRED} and can list. Deciding when clearance becomes a precondition — at onboarding, per
 * seller type, above a listing count — is a policy question for M8's registration slice, and the machinery
 * to enforce whatever it answers is already here.
 *
 * <h2>Requirements are a version, not a list</h2>
 *
 * <p>A submission freezes {@link KycSubmission#getRequirementVersion()} when it is created. Compliance
 * editing the catalogue tomorrow changes what new packs must contain and nothing about how this one is read.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KycService {

    private static final String REFERENCE_PREFIX = "KY";

    /** The permission that gets Compliance into every document of a pack it is judging. */
    static final String REVIEW_PERMISSION = "KYC_REVIEW";

    private final KycSubmissionRepository submissions;
    private final KycDocumentRepository packDocuments;
    private final KycRequirementRepository requirements;
    private final VaultDocumentRepository vaultDocuments;
    private final DocumentService vault;
    private final UserProfileRepository profiles;
    private final TenantRepository tenants;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /**
     * @param uploaded    the document supplied for this requirement, or null
     * @param verdict     Compliance's answer to that document; {@code PENDING} until they look
     */
    public record RequirementLine(
            String documentCode,
            String documentName,
            String description,
            boolean required,
            Short validityMonths,
            String documentReference,
            String originalName,
            String contentType,
            Long sizeBytes,
            LocalDate issuedOn,
            LocalDate expiresOn,
            boolean expired,
            boolean uploaded,
            String verdict,
            String verdictNote) {}

    public record SubmissionResponse(
            String reference,
            String entityType,
            int requirementVersion,
            String state,
            String tenantName,
            OffsetDateTime submittedAt,
            OffsetDateTime decidedAt,
            String decisionNote,
            /** What the profile's own status says now — the thing the permission gate actually reads. */
            String profileKycStatus,
            boolean editable,
            /** Everything the seller must produce, with whatever they have produced against it. */
            List<RequirementLine> requirements,
            int suppliedCount,
            int requiredCount,
            boolean complete,
            OffsetDateTime createdAt) {}

    public record DecisionRequest(String decision, String note) {}

    public record VerdictRequest(String documentCode, String verdict, String note) {}

    @Getter
    @Setter
    public static class SubmissionListRequest extends PagedDataRequest {
        private String state;
        private String entityType;
    }

    // ── the seller's side ─────────────────────────────────────────────────────

    /**
     * The seller's own pack.
     *
     * <p>The live one if there is one; otherwise the most recent, whatever it was decided. Only a seller who
     * has never had a pack gets a new one created — and it is created rather than returned empty because the
     * screen that shows it is also the screen that uploads to it, and a pack that sprang into being on first
     * upload would make the first upload a different code path from the rest.
     *
     * <p>The "otherwise the most recent" half matters: without it, an <em>approved</em> seller opening this
     * page would be handed a fresh empty draft, which reads as "start again" and quietly replaces the
     * evidence of their clearance with a checklist. Starting a new pack is {@link #renew()}, and it is a
     * decision somebody makes rather than a side effect of looking.
     */
    @Transactional
    public SubmissionResponse mine() {
        UserPrincipal caller = AuthContext.require();
        UserProfile profile = profiles.findById(caller.getProfileId())
                .orElseThrow(() -> new ResourceNotFoundException("Profile", caller.getProfileId()));

        KycSubmission submission = submissions.findLiveForProfile(profile.getId())
                .or(() -> submissions.findLatestForProfile(profile.getId()))
                .orElseGet(() -> openPack(profile, caller));
        return toResponse(submission, profile);
    }

    /**
     * Starts a fresh pack after one has been decided.
     *
     * <p>For a renewal, or after a rejection. Refused while one is live — the partial unique index says the
     * same, and this is what turns it into a sentence.
     */
    @Transactional
    public SubmissionResponse renew() {
        UserPrincipal caller = AuthContext.require();
        UserProfile profile = profiles.findById(caller.getProfileId())
                .orElseThrow(() -> new ResourceNotFoundException("Profile", caller.getProfileId()));

        submissions.findLiveForProfile(profile.getId()).ifPresent(live -> {
            throw new HodiException(
                    AppConstant.KYC_SUB_SUBMITTED.equals(live.getState())
                            ? "Your current pack is with Compliance. Wait for their answer."
                            : "You already have a pack open. Finish that one first.",
                    HttpStatus.CONFLICT);
        });
        return toResponse(openPack(profile, caller), profile);
    }

    /**
     * Adds or replaces the document for one requirement.
     *
     * <p>Replacing is the same call: a seller told their CR12 was out of date uploads a new one against the
     * same code, and the pack row is re-pointed. The superseded vault document is left in place — Compliance
     * may have judged against it, and a decision whose evidence has vanished is not a decision anybody can
     * defend.
     */
    @Transactional
    public SubmissionResponse upload(String documentCode, MultipartFile file,
                                     LocalDate issuedOn, LocalDate expiresOn) {
        UserPrincipal caller = AuthContext.require();
        UserProfile profile = profiles.findById(caller.getProfileId())
                .orElseThrow(() -> new ResourceNotFoundException("Profile", caller.getProfileId()));
        KycSubmission submission = submissions.findLiveForProfile(profile.getId())
                .orElseGet(() -> openPack(profile, caller));

        if (!submission.isEditable()) {
            throw new HodiException(
                    "That pack is with Compliance. You will be told if anything else is needed.",
                    HttpStatus.CONFLICT);
        }

        KycRequirement requirement = requirements
                .findFor(submission.getEntityType(), submission.getRequirementVersion()).stream()
                .filter(r -> r.getDocumentCode().equals(documentCode))
                .findFirst()
                .orElseThrow(() -> new HodiException(
                        "There is no '" + documentCode + "' on your checklist.", HttpStatus.BAD_REQUEST));

        // Where the expiry comes from when the uploader does not say: the requirement's own validity window.
        LocalDate expires = expiresOn;
        if (expires == null && requirement.getValidityMonths() != null && issuedOn != null) {
            expires = issuedOn.plusMonths(requirement.getValidityMonths());
        }

        VaultDocument document = vault.store(file, "kyc", requirement.getDocumentCode(),
                requirement.getDocumentName(), profile.getTenantId(), profile.getUserId(),
                issuedOn, expires, REVIEW_PERMISSION);

        KycDocument line = packDocuments
                .findBySubmissionIdAndDocumentCode(submission.getId(), requirement.getDocumentCode())
                .orElseGet(() -> KycDocument.builder()
                        .submissionId(submission.getId())
                        .documentCode(requirement.getDocumentCode())
                        .build());
        line.setDocumentId(document.getId());
        // A replaced document is un-judged. Carrying the old verdict forward would attribute Compliance's
        // opinion of one file to a different one.
        line.setVerdict(AppConstant.VERDICT_PENDING);
        line.setVerdictNote(null);
        line.setVerifiedAt(null);
        line.setVerifiedByUserId(null);
        line.setUpdatedAt(OffsetDateTime.now());
        packDocuments.save(line);

        return toResponse(submission, profile);
    }

    /** Hands the pack to Compliance. Refused until every required document is there. */
    @Transactional
    public SubmissionResponse submit() {
        UserPrincipal caller = AuthContext.require();
        UserProfile profile = profiles.findById(caller.getProfileId())
                .orElseThrow(() -> new ResourceNotFoundException("Profile", caller.getProfileId()));
        KycSubmission submission = submissions.findLiveForProfile(profile.getId())
                .orElseThrow(() -> new HodiException("There is nothing to submit yet.",
                        HttpStatus.BAD_REQUEST));

        SubmissionResponse view = toResponse(submission, profile);
        if (!view.complete()) {
            List<String> missing = view.requirements().stream()
                    .filter(r -> r.required() && !r.uploaded())
                    .map(RequirementLine::documentName)
                    .toList();
            throw new HodiException("Still needed: " + String.join(", ", missing) + ".",
                    HttpStatus.BAD_REQUEST);
        }

        submission.setState(AppConstant.KYC_SUB_SUBMITTED);
        submission.setSubmittedAt(OffsetDateTime.now());
        submission.setUpdatedBy(caller.getUsername());
        submissions.save(submission);

        /*
         * The profile's kyc_status is deliberately NOT moved to PENDING here.
         *
         * PENDING fails EffectivePermissionResolver's gate, so writing it on submission would take a
         * seller's listing permissions away the moment they started cooperating — punishing the act the
         * platform is asking for, and giving them a reason not to. The gate moves on a *decision*: APPROVED
         * opens it, REJECTED closes it. Until then nothing has been learned that justifies taking anything
         * away.
         *
         * The seller's own screen reads the submission's state, not this field, so "with Compliance" is
         * still what they see.
         */
        audit.record(AppConstant.AUDIT_KYC_SUBMITTED, "KycSubmission", submission.getId(), null,
                submission.getReference() + " " + submission.getEntityType());
        return toResponse(submission, profiles.findById(profile.getId()).orElse(profile));
    }

    // ── Compliance's side ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<SubmissionResponse> list(SubmissionListRequest request) {
        Specification<KycSubmission> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("state", blankToNull(request.getState())),
                SearchSpecs.eq("entityType", blankToNull(request.getEntityType())));

        var page = submissions.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "submittedAt")));
        return PagedResponse.from(page, s -> toResponse(s, profiles.findById(s.getProfileId()).orElse(null)));
    }

    @Transactional(readOnly = true)
    public SubmissionResponse find(String reference) {
        KycSubmission submission = load(reference);
        return toResponse(submission, profiles.findById(submission.getProfileId()).orElse(null));
    }

    @Transactional(readOnly = true)
    public long waitingCount() {
        return submissions.countWaiting();
    }

    /** Compliance's verdict on one document within a pack. */
    @Transactional
    public SubmissionResponse setVerdict(String reference, VerdictRequest request) {
        KycSubmission submission = load(reference);
        KycDocument line = packDocuments
                .findBySubmissionIdAndDocumentCode(submission.getId(), request.documentCode())
                .orElseThrow(() -> new HodiException("Nothing has been supplied for that.",
                        HttpStatus.BAD_REQUEST));

        String verdict = request.verdict() == null ? "" : request.verdict().trim().toUpperCase();
        if (!AppConstant.VERDICT_ACCEPTED.equals(verdict) && !AppConstant.VERDICT_REJECTED.equals(verdict)) {
            throw new HodiException("Say whether you accept or reject it.", HttpStatus.BAD_REQUEST);
        }

        line.setVerdict(verdict);
        line.setVerdictNote(blankToNull(request.note()));
        line.setVerifiedAt(OffsetDateTime.now());
        line.setVerifiedByUserId(AuthContext.userId());
        line.setUpdatedAt(OffsetDateTime.now());
        packDocuments.save(line);

        return toResponse(submission, profiles.findById(submission.getProfileId()).orElse(null));
    }

    /**
     * Approve, reject, or ask for more.
     *
     * <p>The decision is the point at which the permission gate moves. Approving writes {@code APPROVED}
     * onto every live seller profile of the organisation — not only the one that submitted — because the
     * pack is about the organisation and a colleague who never touched it should be able to list a property
     * the moment Compliance says the organisation may.
     */
    @Transactional
    public SubmissionResponse decide(String reference, DecisionRequest request) {
        KycSubmission submission = load(reference);
        if (!AppConstant.KYC_SUB_SUBMITTED.equals(submission.getState())) {
            throw new HodiException("That pack is not with you for a decision.", HttpStatus.CONFLICT);
        }

        String decision = request.decision() == null ? "" : request.decision().trim().toUpperCase();
        String profileStatus;
        switch (decision) {
            case "APPROVE" -> {
                submission.setState(AppConstant.KYC_SUB_APPROVED);
                profileStatus = AppConstant.KYC_APPROVED;
            }
            case "REJECT" -> {
                submission.setState(AppConstant.KYC_SUB_REJECTED);
                profileStatus = AppConstant.KYC_REJECTED;
            }
            case "MORE_INFO" -> {
                // Back to the seller and editable again. The profile is left exactly as it was: being asked
                // for another document is not a refusal, and nothing has been decided to act on.
                submission.setState(AppConstant.KYC_SUB_MORE_INFO);
                profileStatus = null;
            }
            default -> throw new HodiException(
                    "Say whether you are approving, rejecting or asking for more.", HttpStatus.BAD_REQUEST);
        }

        submission.setDecisionNote(blankToNull(request.note()));
        // Stamped on every branch: the table's CHECK wants a decided_at for anything past SUBMITTED, and
        // asking for more information is a decision even though it is not a verdict.
        submission.setDecidedAt(OffsetDateTime.now());
        submission.setDecidedByUserId(AuthContext.userId());
        submission.setUpdatedBy(AuthContext.username());
        submissions.save(submission);

        if (profileStatus != null) applyStatusToOrganisation(submission, profileStatus);

        audit.record(AppConstant.AUDIT_KYC_DECIDED, "KycSubmission", submission.getId(), null,
                submission.getReference() + " " + submission.getState()
                        + (request.note() == null ? "" : " — " + request.note()));
        return toResponse(submission, profiles.findById(submission.getProfileId()).orElse(null));
    }

    // ── the catalogue ─────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<KycRequirement> catalogue(String entityType) {
        String type = blankToNull(entityType);
        if (type == null) return requirements.findAll();
        return requirements.findFor(type, requirements.currentVersion(type));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private KycSubmission openPack(UserProfile profile, UserPrincipal caller) {
        if (profile.getTenantId() == null) {
            throw new HodiException("KYC applies to seller organisations. This profile has none.",
                    HttpStatus.BAD_REQUEST);
        }
        Tenant tenant = tenants.findById(profile.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Organisation", profile.getTenantId()));

        String entityType = tenant.getSellerType() == null || tenant.getSellerType().isBlank()
                ? AppConstant.SELLER_COMPANY
                : tenant.getSellerType().trim().toUpperCase();

        return submissions.save(KycSubmission.builder()
                .reference(nextReference())
                .profileId(profile.getId())
                .userId(profile.getUserId())
                .tenantId(tenant.getId())
                .tenantName(tenant.getName())
                .entityType(entityType)
                .requirementVersion(requirements.currentVersion(entityType))
                .createdBy(caller.getUsername())
                .updatedBy(caller.getUsername())
                .build());
    }

    /**
     * Writes a KYC status onto every live seller profile of the organisation.
     *
     * <p>Not only the profile that submitted. The pack is about the organisation: a listing manager who never
     * saw it should be able to work the moment Compliance clears their employer, and should stop the moment
     * it does not.
     */
    private void applyStatusToOrganisation(KycSubmission submission, String status) {
        if (submission.getTenantId() == null) return;
        List<UserProfile> affected = profiles.findLiveByTenant(submission.getTenantId());
        for (UserProfile profile : affected) {
            profile.setKycStatus(status);
            profile.setKycDecidedAt(OffsetDateTime.now());
            profiles.save(profile);
        }
        log.info("KYC {} applied to {} profile(s) of tenant {}", status, affected.size(),
                submission.getTenantId());
    }

    private KycSubmission load(String reference) {
        return submissions.findByReference(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Submission", reference));
    }

    private SubmissionResponse toResponse(KycSubmission submission, UserProfile profile) {
        List<KycRequirement> checklist =
                requirements.findFor(submission.getEntityType(), submission.getRequirementVersion());

        Map<String, KycDocument> supplied = new HashMap<>();
        for (KycDocument line : packDocuments.findBySubmissionId(submission.getId())) {
            supplied.put(line.getDocumentCode(), line);
        }

        List<RequirementLine> lines = new ArrayList<>(checklist.size());
        int suppliedCount = 0;
        int requiredCount = 0;
        boolean complete = true;

        for (KycRequirement requirement : checklist) {
            KycDocument line = supplied.get(requirement.getDocumentCode());
            VaultDocument document = line == null
                    ? null
                    : vaultDocuments.findById(line.getDocumentId()).orElse(null);

            if (requirement.isRequired()) requiredCount++;
            if (document != null) suppliedCount++;
            if (requirement.isRequired() && document == null) complete = false;

            lines.add(new RequirementLine(
                    requirement.getDocumentCode(),
                    requirement.getDocumentName(),
                    requirement.getDescription(),
                    requirement.isRequired(),
                    requirement.getValidityMonths(),
                    document == null ? null : document.getReference(),
                    document == null ? null : document.getOriginalName(),
                    document == null ? null : document.getContentType(),
                    document == null ? null : document.getSizeBytes(),
                    document == null ? null : document.getIssuedOn(),
                    document == null ? null : document.getExpiresOn(),
                    document != null && document.isExpired(),
                    document != null,
                    line == null ? AppConstant.VERDICT_PENDING : line.getVerdict(),
                    line == null ? null : line.getVerdictNote()));
        }

        return new SubmissionResponse(
                submission.getReference(),
                submission.getEntityType(),
                submission.getRequirementVersion(),
                submission.getState(),
                submission.getTenantName(),
                submission.getSubmittedAt(),
                submission.getDecidedAt(),
                submission.getDecisionNote(),
                profile == null ? null : profile.getKycStatus(),
                submission.isEditable(),
                lines,
                suppliedCount,
                requiredCount,
                complete,
                submission.getCreatedAt());
    }

    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = RefGenerator.getInstance().generate(REFERENCE_PREFIX);
            if (!submissions.existsByReference(reference)) return reference;
        }
        throw new HodiException("Could not allocate a reference. Try again.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
