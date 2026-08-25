package com.hodi.modules.valuations;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.RefGenerator;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Valuation (M5, plan §3.5).
 *
 * <h2>Three parties, one row</h2>
 *
 * <p>A requester raises a job, the platform assigns a valuer, the valuer answers. Who may see what is
 * {@link ValuationScope}'s single job — and the interesting case is the valuer, who sees the work assigned
 * to them and nothing else on the platform.
 *
 * <h2>The PI rule is enforced at assignment</h2>
 *
 * <p>FR041: a valuer may only take work their professional indemnity cover would meet. Checked here rather
 * than trusted from a screen, on both the automatic and the manual path — a platform administrator choosing
 * a valuer by name is exactly the case where somebody would otherwise bypass it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValuationService {

    private static final String REFERENCE_PREFIX = "VL";

    private static final Set<String> PURPOSES = Set.of(
            AppConstant.VALUATION_FOR_SALE, AppConstant.VALUATION_FOR_MORTGAGE,
            AppConstant.VALUATION_FOR_INSURANCE, AppConstant.VALUATION_FOR_PROBATE,
            AppConstant.VALUATION_FOR_AUCTION);

    private static final Set<String> METHODOLOGIES = Set.of(
            "COMPARABLE", "INVESTMENT", "COST", "RESIDUAL", "PROFITS");

    private final ValuationRequestRepository requests;
    private final ValuationReportRepository reports;
    private final ValuerProfileRepository valuers;
    private final PropertyRepository properties;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record ReportResponse(
            BigDecimal marketValue,
            BigDecimal forcedSaleValue,
            BigDecimal insuranceValue,
            String currency,
            String methodology,
            LocalDate inspectedOn,
            String conditionNote,
            String assumptions,
            String comparables,
            String documentReference,
            OffsetDateTime submittedAt) {}

    public record ValuationResponse(
            String reference,
            String propertyReference,
            String propertyTitle,
            BigDecimal propertyPrice,
            String county,
            String requestedBy,
            String purpose,
            String state,
            String valuerName,
            String valuerReference,
            OffsetDateTime assignedAt,
            String assignmentMethod,
            LocalDate dueOn,
            BigDecimal feeAmount,
            String currency,
            String requesterNote,
            String declinedReason,
            String cancelledReason,
            OffsetDateTime completedAt,
            /** Present once the valuer has answered. Null before that, for everybody. */
            ReportResponse report,
            OffsetDateTime createdAt) {}

    public record RaiseRequest(
            @NotBlank(message = "Which listing is this about?") String propertyReference,
            String purpose,
            LocalDate dueOn,
            BigDecimal feeAmount,
            String note) {}

    public record AssignRequest(
            /** A valuer's reference for a manual choice; absent asks the panel to choose. */
            String valuerReference,
            LocalDate dueOn,
            BigDecimal feeAmount) {}

    public record DeclineRequest(@NotBlank(message = "Say why") String reason) {}

    public record SubmitReportRequest(
            @NotNull(message = "A market value is required")
            @DecimalMin(value = "1", message = "A market value has to be more than nothing")
            BigDecimal marketValue,
            BigDecimal forcedSaleValue,
            BigDecimal insuranceValue,
            String methodology,
            LocalDate inspectedOn,
            String conditionNote,
            String assumptions,
            String comparables,
            String documentReference) {}

    @Getter
    @Setter
    public static class ValuationListRequest extends PagedDataRequest {
        private String state;
        private String purpose;
        private String propertyReference;
        /** {@code true} for the platform's queue: raised, nobody assigned. */
        private Boolean unassigned;
    }

    // ── raising ───────────────────────────────────────────────────────────────

    /**
     * Raises a job against a live listing.
     *
     * <p>The requester is whichever organisation the caller belongs to — never a parameter. A platform
     * administrator has neither, and is refused: the platform runs the panel, it does not commission
     * valuations on somebody else's behalf.
     */
    @Transactional
    public ValuationResponse raise(RaiseRequest request) {
        UserPrincipal caller = AuthContext.require();
        if (caller.getTenantId() == null && caller.getInstitutionId() == null) {
            throw new HodiException(
                    "A valuation is raised by the seller or the lender who needs it.", HttpStatus.FORBIDDEN);
        }

        Property property = properties.findLiveByReference(trim(request.propertyReference()))
                .orElseThrow(() -> new ResourceNotFoundException("Listing", request.propertyReference()));

        ValuationRequest job = requests.save(ValuationRequest.builder()
                .reference(nextReference())
                .propertyId(property.getId())
                .propertyReference(property.getReference())
                .propertyTitle(property.getTitle())
                .propertyPrice(property.getPrice())
                .county(property.getCounty())
                .tenantId(caller.getTenantId())
                // The requester's own name, from the principal — not the listing's seller, which is a
                // different organisation whenever a lender commissions the valuation.
                .tenantName(caller.getTenantId() == null ? null : caller.getTenantName())
                .institutionId(caller.getInstitutionId())
                .institutionName(caller.getInstitutionId() == null ? null : caller.getInstitutionName())
                .purpose(purpose(request.purpose()))
                .dueOn(request.dueOn())
                .feeAmount(request.feeAmount())
                .currency(property.getCurrency())
                .requesterNote(blankToNull(request.note()))
                .createdBy(caller.getUsername())
                .updatedBy(caller.getUsername())
                .build());

        return toResponse(job);
    }

    // ── assignment ────────────────────────────────────────────────────────────

    /**
     * Puts a valuer on a job.
     *
     * <p>Named, or chosen by the panel. Either way the professional-indemnity rule is checked here — a
     * manual choice is exactly where somebody would otherwise route around it, and "the administrator picked
     * them" is not a defence when a claim exceeds the cover.
     */
    @Transactional
    public ValuationResponse assign(String reference, AssignRequest request) {
        ValuationRequest job = load(reference);
        if (!job.isUnassigned()) {
            throw new HodiException("That valuation already has a valuer.", HttpStatus.CONFLICT);
        }

        boolean manual = request != null && request.valuerReference() != null
                && !request.valuerReference().isBlank();
        ValuerProfile valuer = manual
                ? valuers.findByReference(request.valuerReference().trim())
                        .orElseThrow(() -> new ResourceNotFoundException("Valuer", request.valuerReference()))
                : chooseByPanel(job);

        assertAssignable(valuer, job, manual);

        job.setValuerProfileId(valuer.getId());
        job.setValuerName(valuer.getFullName());
        job.setAssignedAt(OffsetDateTime.now());
        job.setAssignedByUserId(AuthContext.userId());
        job.setAssignmentMethod(manual ? AppConstant.ASSIGN_MANUAL : AppConstant.ASSIGN_ROUND_ROBIN);
        job.setState(AppConstant.VALUATION_ASSIGNED);
        if (request != null && request.dueOn() != null) job.setDueOn(request.dueOn());
        if (request != null && request.feeAmount() != null) job.setFeeAmount(request.feeAmount());
        job.setUpdatedBy(AuthContext.username());
        requests.save(job);

        valuer.setOpenAssignments(valuer.getOpenAssignments() + 1);
        valuer.setLastAssignedAt(OffsetDateTime.now());
        valuers.save(valuer);

        audit.record(AppConstant.AUDIT_VALUATION_ASSIGN, "ValuationRequest", job.getId(), null,
                job.getReference() + " → " + valuer.getReference() + " (" + job.getAssignmentMethod() + ")");
        return toResponse(job);
    }

    /**
     * The round robin: least loaded, longest waiting, who can actually take it.
     *
     * <p>The database orders by load; the two rules that need the job — cover for its value, and willingness
     * to travel to its county — are applied here, because both compare a valuer against *this* job rather
     * than describing the valuer.
     */
    private ValuerProfile chooseByPanel(ValuationRequest job) {
        return valuers.findAvailable().stream()
                .filter(ValuerProfile::isAvailable)
                .filter(v -> v.coversValue(job.getPropertyPrice()))
                .filter(v -> v.covers(job.getCounty()))
                .findFirst()
                .orElseThrow(() -> new HodiException(
                        "No valuer on the panel is available for a property of this value in "
                                + (job.getCounty() == null ? "that county" : job.getCounty())
                                + ". Assign one by name, or widen the panel.",
                        HttpStatus.CONFLICT));
    }

    /** The rules, said out loud, so a refusal names the one that failed. */
    private void assertAssignable(ValuerProfile valuer, ValuationRequest job, boolean manual) {
        if (!valuer.isOnPanel()) {
            throw new HodiException(valuer.getFullName() + " is not on the panel.", HttpStatus.CONFLICT);
        }
        if (!AppConstant.isLive(valuer.getStatus())) {
            throw new HodiException(valuer.getFullName() + " is not active.", HttpStatus.CONFLICT);
        }
        if (!valuer.hasCurrentPi()) {
            throw new HodiException(
                    valuer.getFullName() + " has no current professional indemnity cover on file.",
                    HttpStatus.CONFLICT);
        }
        if (!valuer.coversValue(job.getPropertyPrice())) {
            throw new HodiException(
                    valuer.getFullName() + "'s indemnity cover is below this property's value, so they "
                            + "cannot be assigned to it.", HttpStatus.CONFLICT);
        }
        // County is advisory on a manual assignment: an administrator who knows the valuer will travel
        // should not be blocked by a list the valuer wrote for themselves. It is a hard filter for the
        // automatic path, where nobody is exercising that judgement.
        if (!manual && !valuer.covers(job.getCounty())) {
            throw new HodiException(valuer.getFullName() + " does not cover " + job.getCounty() + ".",
                    HttpStatus.CONFLICT);
        }
    }

    // ── the valuer's own work ─────────────────────────────────────────────────

    /** Taken on. The job stops being something the valuer might hand back without saying so. */
    @Transactional
    public ValuationResponse accept(String reference) {
        ValuationRequest job = loadForValuer(reference);
        if (!AppConstant.VALUATION_ASSIGNED.equals(job.getState())) {
            throw new HodiException("That job is not waiting on you.", HttpStatus.CONFLICT);
        }
        job.setState(AppConstant.VALUATION_IN_PROGRESS);
        job.setUpdatedBy(AuthContext.username());
        return toResponse(requests.save(job));
    }

    /**
     * Handed back.
     *
     * <p>Returns to the queue rather than dying: the requester still needs a figure, and the platform can
     * assign somebody else. The valuer's own load comes back down, so the round robin does not keep skipping
     * them for work they never did.
     */
    @Transactional
    public ValuationResponse decline(String reference, DeclineRequest request) {
        ValuationRequest job = loadForValuer(reference);
        if (AppConstant.VALUATION_COMPLETED.equals(job.getState())
                || AppConstant.VALUATION_CANCELLED.equals(job.getState())) {
            throw new HodiException("That job is already settled.", HttpStatus.CONFLICT);
        }

        releaseValuer(job);
        job.setDeclinedReason(request.reason().trim());
        job.setValuerProfileId(null);
        job.setValuerName(null);
        job.setAssignedAt(null);
        job.setAssignmentMethod(null);
        job.setState(AppConstant.VALUATION_REQUESTED);
        job.setUpdatedBy(AuthContext.username());
        return toResponse(requests.save(job));
    }

    /**
     * The answer.
     *
     * <p>Written once. A valuation that could be edited after a lender relied on it is not a valuation, so a
     * second submission is refused rather than overwriting — a corrected figure is a new job.
     */
    @Transactional
    public ValuationResponse submitReport(String reference, SubmitReportRequest request) {
        ValuationRequest job = loadForValuer(reference);
        if (!AppConstant.VALUATION_IN_PROGRESS.equals(job.getState())
                && !AppConstant.VALUATION_ASSIGNED.equals(job.getState())) {
            throw new HodiException("That job is not open for a report.", HttpStatus.CONFLICT);
        }
        reports.findByRequestId(job.getId()).ifPresent(existing -> {
            throw new HodiException(
                    "A report has already been submitted for this job. A corrected figure is a new "
                            + "valuation, not an edit of this one.", HttpStatus.CONFLICT);
        });

        if (request.forcedSaleValue() != null
                && request.forcedSaleValue().compareTo(request.marketValue()) > 0) {
            throw new HodiException("A forced-sale value cannot exceed the market value.",
                    HttpStatus.BAD_REQUEST);
        }

        reports.save(ValuationReport.builder()
                .requestId(job.getId())
                .marketValue(request.marketValue())
                .forcedSaleValue(request.forcedSaleValue())
                .insuranceValue(request.insuranceValue())
                .currency(job.getCurrency())
                .methodology(methodology(request.methodology()))
                .inspectedOn(request.inspectedOn())
                .conditionNote(blankToNull(request.conditionNote()))
                .assumptions(blankToNull(request.assumptions()))
                .comparables(blankToNull(request.comparables()))
                .documentReference(blankToNull(request.documentReference()))
                .submittedByUserId(AuthContext.userId())
                .createdBy(AuthContext.username())
                .updatedBy(AuthContext.username())
                .build());

        releaseValuer(job);
        job.setState(AppConstant.VALUATION_COMPLETED);
        job.setCompletedAt(OffsetDateTime.now());
        job.setUpdatedBy(AuthContext.username());
        requests.save(job);

        audit.record(AppConstant.AUDIT_VALUATION_REPORT, "ValuationRequest", job.getId(), null,
                job.getReference() + " valued at " + request.marketValue() + " " + job.getCurrency());
        return toResponse(job);
    }

    // ── reads ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<ValuationResponse> list(ValuationListRequest request) {
        Specification<ValuationRequest> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("state", blankToNull(request.getState())),
                SearchSpecs.eq("purpose", blankToNull(request.getPurpose())),
                SearchSpecs.eq("propertyReference", blankToNull(request.getPropertyReference())),
                unassignedIs(request.getUnassigned()),
                ValuationScope.restrict(myValuerProfileId().orElse(null)));

        var page = requests.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public ValuationResponse find(String reference) {
        return toResponse(load(reference));
    }

    @Transactional(readOnly = true)
    public long unassignedCount() {
        return requests.countUnassigned();
    }

    // ── cancellation ──────────────────────────────────────────────────────────

    /** The requester changing their mind. Only they, or the platform, and only before it is answered. */
    @Transactional
    public ValuationResponse cancel(String reference, DeclineRequest request) {
        ValuationRequest job = load(reference);
        if (!job.isOpen()) {
            throw new HodiException("That valuation is already settled.", HttpStatus.CONFLICT);
        }
        releaseValuer(job);
        job.setState(AppConstant.VALUATION_CANCELLED);
        job.setCancelledReason(request == null ? null : blankToNull(request.reason()));
        job.setUpdatedBy(AuthContext.username());
        return toResponse(requests.save(job));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** Loaded, then checked against the four rules. */
    private ValuationRequest load(String reference) {
        ValuationRequest job = requests.findByReference(trim(reference))
                .orElseThrow(() -> new ResourceNotFoundException("Valuation", reference));
        ValuationScope.assertVisible(job, myValuerProfileId().orElse(null));
        return job;
    }

    /** The valuer's own path: the job must be theirs, not merely visible to them. */
    private ValuationRequest loadForValuer(String reference) {
        Long mine = myValuerProfileId()
                .orElseThrow(() -> new HodiException("You are not on the valuation panel.",
                        HttpStatus.FORBIDDEN));
        ValuationRequest job = requests.findByReference(trim(reference))
                .orElseThrow(() -> new ResourceNotFoundException("Valuation", reference));
        if (!mine.equals(job.getValuerProfileId())) {
            throw new HodiException("That job is not assigned to you.", HttpStatus.FORBIDDEN);
        }
        return job;
    }

    /** The caller's own panel row, if they have one. */
    private Optional<Long> myValuerProfileId() {
        Long profileId = AuthContext.current().map(UserPrincipal::getProfileId).orElse(null);
        if (profileId == null) return Optional.empty();
        return valuers.findByProfileId(profileId).map(ValuerProfile::getId);
    }

    /**
     * Takes a job off a valuer's load.
     *
     * <p>Called on every path that ends their involvement — declined, cancelled, completed — so the round
     * robin's ordering stays true. A counter that only ever went up would quietly retire the platform's
     * busiest valuers.
     */
    private void releaseValuer(ValuationRequest job) {
        if (job.getValuerProfileId() == null) return;
        valuers.findById(job.getValuerProfileId()).ifPresent(valuer -> {
            valuer.setOpenAssignments(Math.max(0, valuer.getOpenAssignments() - 1));
            if (AppConstant.VALUATION_IN_PROGRESS.equals(job.getState())
                    || AppConstant.VALUATION_ASSIGNED.equals(job.getState())) {
                // Only a completed job counts as completed. This runs before the state moves, so the check
                // is on where it is coming from.
                if (job.getCompletedAt() != null) {
                    valuer.setCompletedCount(valuer.getCompletedCount() + 1);
                }
            }
            valuers.save(valuer);
        });
    }

    private Specification<ValuationRequest> unassignedIs(Boolean unassigned) {
        if (!Boolean.TRUE.equals(unassigned)) return null;
        return (root, query, cb) -> cb.equal(root.get("state"), AppConstant.VALUATION_REQUESTED);
    }

    private ValuationResponse toResponse(ValuationRequest job) {
        ReportResponse report = reports.findByRequestId(job.getId())
                .map(r -> new ReportResponse(r.getMarketValue(), r.getForcedSaleValue(),
                        r.getInsuranceValue(), r.getCurrency(), r.getMethodology(), r.getInspectedOn(),
                        r.getConditionNote(), r.getAssumptions(), r.getComparables(),
                        r.getDocumentReference(), r.getSubmittedAt()))
                .orElse(null);

        String valuerReference = job.getValuerProfileId() == null ? null
                : valuers.findById(job.getValuerProfileId()).map(ValuerProfile::getReference).orElse(null);

        return new ValuationResponse(
                job.getReference(), job.getPropertyReference(), job.getPropertyTitle(),
                job.getPropertyPrice(), job.getCounty(),
                job.getTenantName() != null ? job.getTenantName() : job.getInstitutionName(),
                job.getPurpose(), job.getState(), job.getValuerName(), valuerReference,
                job.getAssignedAt(), job.getAssignmentMethod(), job.getDueOn(), job.getFeeAmount(),
                job.getCurrency(), job.getRequesterNote(), job.getDeclinedReason(),
                job.getCancelledReason(), job.getCompletedAt(), report, job.getCreatedAt());
    }

    private static String purpose(String requested) {
        String value = requested == null ? "" : requested.trim().toUpperCase();
        return PURPOSES.contains(value) ? value : AppConstant.VALUATION_FOR_SALE;
    }

    private static String methodology(String requested) {
        String value = requested == null ? "" : requested.trim().toUpperCase();
        return METHODOLOGIES.contains(value) ? value : null;
    }

    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = RefGenerator.getInstance().generate(REFERENCE_PREFIX);
            if (!requests.existsByReference(reference)) return reference;
        }
        throw new HodiException("Could not allocate a reference. Try again.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
