package com.hodi.modules.agents;

import com.hodi.common.AppConstant;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.PagedResponse;
import com.hodi.common.util.SearchSpecs;
import com.hodi.common.util.RrnGenerator;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.tenants.Tenant;
import com.hodi.modules.tenants.TenantService;
import com.hodi.security.principal.AuthContext;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The agent register, and the decisions taken on it (M9, BRD FR160–FR161).
 *
 * <h2>Approval is what creates the agent</h2>
 *
 * <p>Registering produces an account and an application. Approval produces the three things that make
 * somebody able to trade: a one-person organisation to list into, an executed agreement, and a profile whose
 * KYC standing clears the listing gate.
 *
 * <p>That last one deserves saying plainly. <strong>An agent's approval is their KYC.</strong> The platform
 * has just examined a licence, an identity number and a signed acceptance of the terms; routing the same
 * person through the seller document pack afterwards would be asking for the same assurances twice, in a
 * queue built for a different question. So approval sets the profile to {@code APPROVED} directly, and the
 * existing gate in {@code EffectivePermissionResolver} does the rest without a second mechanism.
 *
 * <h2>Suspension leaves the organisation standing</h2>
 *
 * <p>Suspending an agent moves the profile back to {@code PENDING}, which drops the listing-write
 * permissions and leaves everything they have already published where it is. Deleting the organisation would
 * take away listings buyers have enquired on and clients who never did anything wrong.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentService {

    /** The global group every agent belongs to. Created by the seeder, like the buyer's. */
    public static final String AGENT_GROUP_NAME = "Property Agent";

    private final AgentProfileRepository agents;
    private final AgentAgreementRepository agreements;
    private final SignatureArtifactRepository signatures;
    private final SignatureService signatureService;
    private final UserProfileRepository profiles;
    private final PropertyRepository properties;
    private final TenantService tenants;
    private final ConfigurationService configs;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record AgentResponse(
            String reference,
            String fullName,
            String email,
            String phone,
            boolean selfEmployed,
            String agencyName,
            String tradingName,
            String idNumber,
            String licenceNumber,
            LocalDate licenceExpiresOn,
            /** Whether a named licence is still in date today. */
            boolean licenceCurrent,
            String counties,
            String bio,
            String state,
            String organisationName,
            OffsetDateTime decidedAt,
            String decisionNote,
            /** Present once approved. */
            String agreementReference,
            String signatureReference,
            int listingsCount,
            Integer status,
            String statusFlag,
            OffsetDateTime createdAt) {}

    public record AgreementResponse(
            String reference,
            String termsVersion,
            String termsSha256,
            String body,
            String bodySha256,
            LocalDate effectiveFrom,
            OffsetDateTime generatedAt) {}

    /**
     * The signature, as the platform sees it when deciding an application.
     *
     * <p>No image bytes: the picture is fetched separately so that reading it is one request that can be
     * refused, logged and reasoned about on its own. What is here is everything needed to decide whether the
     * signature is good — when, from where, against which version, and a checksum.
     */
    public record SignatureResponse(
            String reference,
            String kind,
            String typedName,
            String termsVersion,
            String termsSha256,
            /** True when the terms in force today still hash to the same value. */
            boolean matchesCurrentTerms,
            String checksumSha256,
            String encryption,
            OffsetDateTime capturedAt,
            String ipAddress,
            String userAgent) {}

    public record DecisionRequest(
            @NotBlank(message = "Say what you are doing") String decision,
            String note) {}

    public record UpdateAgentRequest(
            @Size(max = 160) String fullName,
            @Size(max = 32) String phone,
            Boolean selfEmployed,
            @Size(max = 255) String agencyName,
            @Size(max = 64) String licenceNumber,
            LocalDate licenceExpiresOn,
            String counties,
            String bio) {}

    @Getter
    @Setter
    public static class AgentListRequest extends PagedDataRequest {
        private String state;
    }

    /** What the register's header shows without loading a page of rows. */
    public record AgentCounts(long pending, long approved, long suspended) {}

    // ── reading ───────────────────────────────────────────────────────────────

    /**
     * The register.
     *
     * <p>Unscoped, deliberately: it is a directory. An agent browsing it sees who else is on the platform,
     * which is the same information a buyer could gather from the listings — and the decision fields it
     * carries are ones any agent may see about the scheme they joined. What an agent cannot see is the
     * evidence behind anybody's application, which is a separate permission.
     */
    @Transactional(readOnly = true)
    public PagedResponse<AgentResponse> list(AgentListRequest request) {
        Specification<AgentProfile> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                SearchSpecs.eq("state", blankToNull(request.getState())));
        var page = agents.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public AgentResponse find(String reference) {
        return toResponse(require(reference));
    }

    @Transactional(readOnly = true)
    public AgentCounts counts() {
        return new AgentCounts(
                agents.countByState(AgentState.PENDING),
                agents.countByState(AgentState.APPROVED),
                agents.countByState(AgentState.SUSPENDED));
    }

    /** The signed-in agent's own row. */
    @Transactional(readOnly = true)
    public AgentResponse mine() {
        return toResponse(requireMine());
    }

    @Transactional(readOnly = true)
    public AgreementResponse myAgreement() {
        AgentProfile agent = requireMine();
        return agreementOf(agent);
    }

    @Transactional(readOnly = true)
    public AgreementResponse agreementFor(String reference) {
        return agreementOf(require(reference));
    }

    @Transactional(readOnly = true)
    public SignatureResponse signatureFor(String reference) {
        AgentProfile agent = require(reference);
        if (agent.getSignatureId() == null) {
            throw new ResourceNotFoundException("Signature", reference);
        }
        SignatureArtifact artifact = signatures.findById(agent.getSignatureId())
                .orElseThrow(() -> new ResourceNotFoundException("Signature", reference));
        audit.record(AppConstant.AUDIT_SIGNATURE_READ, "SignatureArtifact", artifact.getId(), null,
                "opened the signature behind agent " + agent.getReference());
        return toSignatureResponse(artifact);
    }

    /** The drawn signature itself. A separate call, so that looking at it is its own audited act. */
    @Transactional(readOnly = true)
    public StoredImage signatureImage(String reference) {
        AgentProfile agent = require(reference);
        if (agent.getSignatureId() == null) throw new ResourceNotFoundException("Signature", reference);
        SignatureArtifact artifact = signatures.findById(agent.getSignatureId())
                .orElseThrow(() -> new ResourceNotFoundException("Signature", reference));
        byte[] bytes = signatureService.imageOf(artifact);
        audit.record(AppConstant.AUDIT_SIGNATURE_READ, "SignatureArtifact", artifact.getId(), null,
                "opened the signature image behind agent " + agent.getReference());
        return new StoredImage(bytes, artifact.getContentType());
    }

    public record StoredImage(byte[] bytes, String contentType) {}

    // ── decisions ─────────────────────────────────────────────────────────────

    /**
     * Approve, reject, suspend or reinstate.
     *
     * <p>One entry point rather than four, because they are one decision with four outcomes and the audit
     * row should say so. The state machine is small and stated here rather than inferred: pending can be
     * approved or rejected, approved can be suspended, suspended can be reinstated, and rejected is final —
     * a rejected applicant applies again rather than being un-rejected by somebody who reconsiders.
     */
    @Transactional
    public AgentResponse decide(String reference, DecisionRequest request) {
        AgentProfile agent = require(reference);
        String decision = request.decision().trim().toUpperCase();
        String before = snapshot(agent);

        switch (decision) {
            case "APPROVE" -> approve(agent, request.note());
            case "REJECT" -> reject(agent, request.note());
            case "SUSPEND" -> suspend(agent, request.note());
            case "REINSTATE" -> reinstate(agent, request.note());
            default -> throw new HodiException(
                    "Approve, reject, suspend or reinstate — \"" + request.decision() + "\" is none of those.",
                    HttpStatus.BAD_REQUEST);
        }

        agent.setDecidedAt(OffsetDateTime.now());
        agent.setDecidedByUserId(AuthContext.userId());
        agent.setDecisionNote(blankToNull(request.note()));
        agent.setUpdatedBy(AuthContext.username());
        AgentProfile saved = agents.save(agent);

        audit.record(AppConstant.AUDIT_AGENT_DECIDED, "AgentProfile", saved.getId(), before, snapshot(saved));
        log.info("Agent {} {}", saved.getReference(), decision.toLowerCase());
        return toResponse(saved);
    }

    private void approve(AgentProfile agent, String note) {
        if (!agent.isPending()) {
            throw new HodiException("Only a pending application can be approved.", HttpStatus.CONFLICT);
        }
        UserProfile profile = requireProfile(agent);

        Tenant organisation = tenants.createForAgent(
                agent.tradingName(), agent.getFullName(), agent.getEmail(), agent.getPhone());

        profile.setTenantId(organisation.getId());
        profile.setTenantName(organisation.getName());
        // Their approval is their clearance — see the class comment. Written here rather than left to the
        // seller KYC pack, which asks a different question of a different kind of applicant.
        profile.setKycStatus(AppConstant.KYC_APPROVED);
        profile.setUpdatedBy(AuthContext.username());
        profiles.save(profile);

        AgentAgreement agreement = generateAgreement(agent);

        agent.setTenantId(organisation.getId());
        agent.setAgreementId(agreement.getId());
        agent.setState(AgentState.APPROVED);
        if (note == null || note.isBlank()) {
            log.debug("Agent {} approved without a note", agent.getReference());
        }
    }

    private void reject(AgentProfile agent, String note) {
        if (!agent.isPending()) {
            throw new HodiException("Only a pending application can be rejected.", HttpStatus.CONFLICT);
        }
        if (note == null || note.isBlank()) {
            // A refusal somebody has to act on has to say what was wrong with it.
            throw new HodiException("Say why the application is being refused.", HttpStatus.BAD_REQUEST);
        }
        agent.setState(AgentState.REJECTED);
    }

    private void suspend(AgentProfile agent, String note) {
        if (!AgentState.APPROVED.equals(agent.getState())) {
            throw new HodiException("Only an approved agent can be suspended.", HttpStatus.CONFLICT);
        }
        if (note == null || note.isBlank()) {
            throw new HodiException("Say why the agent is being suspended.", HttpStatus.BAD_REQUEST);
        }
        UserProfile profile = requireProfile(agent);
        // PENDING rather than REJECTED: the gate reads both the same way, and only one of them describes
        // somebody whose standing is under review rather than refused.
        profile.setKycStatus(AppConstant.KYC_PENDING);
        profile.setUpdatedBy(AuthContext.username());
        profiles.save(profile);
        agent.setState(AgentState.SUSPENDED);
    }

    private void reinstate(AgentProfile agent, String note) {
        if (!AgentState.SUSPENDED.equals(agent.getState())) {
            throw new HodiException("Only a suspended agent can be reinstated.", HttpStatus.CONFLICT);
        }
        UserProfile profile = requireProfile(agent);
        profile.setKycStatus(AppConstant.KYC_APPROVED);
        profile.setUpdatedBy(AuthContext.username());
        profiles.save(profile);
        agent.setState(AgentState.APPROVED);
    }

    // ── the agent's own details ───────────────────────────────────────────────

    /**
     * What an agent may change about themselves.
     *
     * <p>Not their email, not their ID number, and not their state. The first is their sign-in identity, the
     * second is what the platform checked, and the third is not theirs to decide. A licence number they may
     * change — licences get renewed — and doing so does not re-open their approval, which is a judgement the
     * platform can revisit by suspending them if it wants to.
     */
    @Transactional
    public AgentResponse updateMine(UpdateAgentRequest request) {
        AgentProfile agent = requireMine();
        String before = snapshot(agent);

        if (request.fullName() != null && !request.fullName().isBlank()) {
            agent.setFullName(request.fullName().trim());
        }
        if (request.phone() != null) agent.setPhone(blankToNull(request.phone()));
        if (request.selfEmployed() != null) agent.setSelfEmployed(request.selfEmployed());
        if (request.agencyName() != null) agent.setAgencyName(blankToNull(request.agencyName()));
        if (request.licenceNumber() != null) agent.setLicenceNumber(blankToNull(request.licenceNumber()));
        if (request.licenceExpiresOn() != null) agent.setLicenceExpiresOn(request.licenceExpiresOn());
        if (request.counties() != null) agent.setCounties(blankToNull(request.counties()));
        if (request.bio() != null) agent.setBio(blankToNull(request.bio()));

        if (!agent.isSelfEmployed() && (agent.getAgencyName() == null || agent.getAgencyName().isBlank())) {
            throw new HodiException("Name the agency you work for, or say you are self-employed.",
                    HttpStatus.BAD_REQUEST);
        }

        agent.setUpdatedBy(AuthContext.username());
        AgentProfile saved = agents.save(agent);
        audit.record(AppConstant.ACTION_UPDATE, "AgentProfile", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Renders the agreement and pins it.
     *
     * <p>The terms text is taken from the signature's own record of what was accepted where it can be, and
     * only falls back to the configuration when the artifact predates the hash — because the document should
     * quote what this person signed, not what the settings say today.
     */
    private AgentAgreement generateAgreement(AgentProfile agent) {
        SignatureArtifact signature = agent.getSignatureId() == null
                ? null
                : signatures.findById(agent.getSignatureId()).orElse(null);
        SignatureService.Terms terms = signatureService.currentTerms();

        String version = signature == null ? terms.version() : signature.getTermsVersion();
        String hash = signature == null ? terms.sha256() : signature.getTermsSha256();
        String signedBy = signature == null ? agent.getFullName()
                : (signature.getTypedName() != null ? signature.getTypedName() : agent.getFullName());
        String signedAt = signature == null
                ? "—"
                : DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm")
                        .format(signature.getCapturedAt().toLocalDateTime());

        Map<String, String> values = new LinkedHashMap<>();
        // The platform's own name is configuration like every other brand value, so the template asks for it
        // rather than spelling it out. terms.text() has already had its own copy substituted.
        values.put("platformName", configs.getString(ConfigKey.COMPANY_NAME));
        values.put("agentName", agent.getFullName());
        values.put("agency", agent.isSelfEmployed() ? "Self-employed" : agent.getAgencyName());
        values.put("licence", agent.getLicenceNumber() == null ? "None recorded" : agent.getLicenceNumber());
        values.put("reference", agent.getReference());
        values.put("date", LocalDate.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy")));
        values.put("termsVersion", version);
        values.put("signedBy", signedBy);
        values.put("signedAt", signedAt);
        values.put("terms", terms.text());

        String body = render(configs.getString(ConfigKey.AGENT_AGREEMENT_TEMPLATE), values);
        return agreements.save(AgentAgreement.builder()
                .reference(RrnGenerator.generate("AG"))
                .agentProfileId(agent.getId())
                .signatureId(agent.getSignatureId())
                .termsVersion(version)
                .termsSha256(hash)
                .body(body)
                .bodySha256(SignatureService.sha256(body))
                .effectiveFrom(LocalDate.now())
                .generatedBy(AuthContext.username())
                .build());
    }

    /** {@code {{name}}} substitution, and nothing more — a template language here would be a liability. */
    private static String render(String template, Map<String, String> values) {
        String out = template == null ? "" : template;
        for (var entry : values.entrySet()) {
            out = out.replace("{{" + entry.getKey() + "}}",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return out;
    }

    private AgreementResponse agreementOf(AgentProfile agent) {
        if (agent.getAgreementId() == null) {
            throw new HodiException(
                    "There is no agreement yet — one is generated when the application is approved.",
                    HttpStatus.NOT_FOUND);
        }
        AgentAgreement agreement = agreements.findById(agent.getAgreementId())
                .orElseThrow(() -> new ResourceNotFoundException("Agreement", agent.getReference()));
        return new AgreementResponse(agreement.getReference(), agreement.getTermsVersion(),
                agreement.getTermsSha256(), agreement.getBody(), agreement.getBodySha256(),
                agreement.getEffectiveFrom(), agreement.getGeneratedAt());
    }

    AgentProfile require(String reference) {
        return agents.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Agent", reference));
    }

    /**
     * The signed-in agent's own row.
     *
     * <p>By profile first and by user second. Somebody can hold a buyer profile and an agent profile on one
     * login; looking up by the active profile is what makes "my agent details" mean the profile they are
     * actually signed in on, and the fallback covers a platform-created application before the holder has
     * ever switched onto it.
     */
    AgentProfile requireMine() {
        Long profileId = AuthContext.current().map(p -> p.getProfileId()).orElse(null);
        if (profileId != null) {
            var byProfile = agents.findByProfileId(profileId);
            if (byProfile.isPresent()) return byProfile.get();
        }
        return agents.findFirstByUserIdOrderByIdDesc(AuthContext.requireUserId())
                .orElseThrow(() -> new HodiException("You are not registered as an agent.",
                        HttpStatus.FORBIDDEN));
    }

    private UserProfile requireProfile(AgentProfile agent) {
        return profiles.findById(agent.getProfileId())
                .orElseThrow(() -> new HodiException(
                        "That agent's account profile is missing.", HttpStatus.INTERNAL_SERVER_ERROR));
    }

    AgentResponse toResponse(AgentProfile agent) {
        String agreementReference = agent.getAgreementId() == null ? null
                : agreements.findById(agent.getAgreementId()).map(AgentAgreement::getReference).orElse(null);
        String signatureReference = agent.getSignatureId() == null ? null
                : signatures.findById(agent.getSignatureId())
                        .map(SignatureArtifact::getReference).orElse(null);
        String organisation = agent.getTenantId() == null ? null
                : profiles.findById(agent.getProfileId()).map(UserProfile::getTenantName).orElse(null);

        return new AgentResponse(
                agent.getReference(), agent.getFullName(), agent.getEmail(), agent.getPhone(),
                agent.isSelfEmployed(), agent.getAgencyName(), agent.tradingName(),
                agent.getIdNumber(), agent.getLicenceNumber(), agent.getLicenceExpiresOn(),
                agent.hasCurrentLicence(), agent.getCounties(), agent.getBio(),
                agent.getState(), organisation, agent.getDecidedAt(), agent.getDecisionNote(),
                agreementReference, signatureReference,
                // Counted rather than carried on the row. A stored counter would have to be maintained by
                // every path that creates, archives or reassigns a listing, and a counter that is only
                // mostly maintained reads as a fact while being wrong.
                (int) properties.countByAgentProfileId(agent.getId()),
                agent.getStatus(), agent.getStatusFlag(), agent.getCreatedAt());
    }

    private SignatureResponse toSignatureResponse(SignatureArtifact artifact) {
        boolean matches = false;
        try {
            matches = signatureService.currentTerms().sha256().equals(artifact.getTermsSha256());
        } catch (RuntimeException e) {
            log.debug("Could not compare against the current terms: {}", e.getMessage());
        }
        return new SignatureResponse(artifact.getReference(), artifact.getSignatureKind(),
                artifact.getTypedName(), artifact.getTermsVersion(), artifact.getTermsSha256(),
                matches, artifact.getChecksumSha256(), artifact.getEncryption(),
                artifact.getCapturedAt(), artifact.getIpAddress(), artifact.getUserAgent());
    }

    private static String snapshot(AgentProfile agent) {
        return "{\"reference\":\"%s\",\"state\":\"%s\",\"tenantId\":%s,\"agreementId\":%s}".formatted(
                agent.getReference(), agent.getState(),
                String.valueOf(agent.getTenantId()), String.valueOf(agent.getAgreementId()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

}
