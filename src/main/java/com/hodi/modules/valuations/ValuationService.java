package com.hodi.modules.valuations;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.RefGenerator;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.approvals.ChangeSet;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.leads.PurchaseRequest;
import com.hodi.modules.leads.PurchaseRequestRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.modules.kyc.DocumentService;
import com.hodi.modules.kyc.VaultDocument;
import com.hodi.modules.kyc.VaultDocumentRepository;
import com.hodi.modules.operations.CalendarService;
import com.hodi.modules.operations.OperationsConstants;
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
import org.springframework.web.multipart.MultipartFile;

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
 * <h2>A report is reviewed before it counts</h2>
 *
 * <p>The valuer's report lands the job in SUBMITTED; anyone holding {@code VALUATIONS_APPROVE} approves it to
 * COMPLETED or sends it back to IN_PROGRESS with a reason, through the approval engine. Only a COMPLETED
 * figure is one the bank may lend against. Every step is an event on the job and a row in the audit trail.
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
    private final ValuationEventRepository events;
    private final PropertyRepository properties;
    private final ApprovalService approvals;
    private final AuditService audit;
    private final ValuationNotifier notifier;
    private final com.hodi.modules.notifications.NotificationService notificationLog;
    private final CalendarService calendar;
    private final DocumentService documents;
    private final VaultDocumentRepository vaultDocuments;
    private final UnitBookingRepository bookings;
    private final PurchaseRequestRepository offers;

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
            /** The signed report in the vault, once attached: its reference and the file's own name. */
            String documentReference,
            String documentName,
            OffsetDateTime submittedAt) {}

    /** One thing that happened to the job, for its timeline. */
    public record EventResponse(String action, String state, String actor, String actorRole, String note,
                                OffsetDateTime at) {}

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
            /** When the valuer will be on site, once booked. */
            OffsetDateTime inspectionAt,
            /** The sale it was raised against, when it was. */
            String bookingId,
            String bookingReference,
            String offerReference,
            /** Present once the valuer has answered. Null before that, for everybody. */
            ReportResponse report,
            OffsetDateTime createdAt,
            OffsetDateTime submittedAt,
            String reviewedBy,
            OffsetDateTime reviewedAt,
            String reviewNote,
            /** What this caller may do to this job, decided here so the screen and the service agree. */
            boolean mayAssign,
            boolean mayAccept,
            boolean mayDecline,
            boolean mayReport,
            boolean mayApprove,
            boolean mayCancel,
            /** The valuer on it may book the inspection; and may attach the signed report to a submitted one. */
            boolean mayInspect,
            boolean mayAttachReport,
            /** How many times it has been handed back. */
            int handBacks,
            /** The timeline, oldest first. Empty on a list; filled when one job is read. */
            List<EventResponse> events) {}

    public record ReviewRequest(
            /** APPROVED, SENT_BACK or REJECTED — the approval engine's own words. */
            @NotBlank(message = "Say what you decided") String decision,
            String reason) {}

    public record RaiseRequest(
            /** The listing. May be blank when a booking or an offer names it. */
            String propertyReference,
            String purpose,
            LocalDate dueOn,
            BigDecimal feeAmount,
            String note,
            /** The sale it is for, when it is for one: a booking's id, or an offer's reference. */
            String bookingId,
            String offerReference) {

        public RaiseRequest(String propertyReference, String purpose, LocalDate dueOn, BigDecimal feeAmount,
                            String note) {
            this(propertyReference, purpose, dueOn, feeAmount, note, null, null);
        }
    }

    public record AssignRequest(
            /** A valuer's reference for a manual choice; absent asks the panel to choose. */
            String valuerReference,
            LocalDate dueOn,
            BigDecimal feeAmount) {}

    public record DeclineRequest(@NotBlank(message = "Say why") String reason) {}

    public record InspectionRequest(@NotNull(message = "When will you be on site?") OffsetDateTime at) {}

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
        /** {@code true} for reports awaiting the platform's review. */
        private Boolean awaitingReview;
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
                    "A valuation is raised by the seller or the bank who needs it.", HttpStatus.FORBIDDEN);
        }

        // Raised against a sale, or against a listing. A sale names its own home, and it must be the
        // requester's own sale — a valuation is commissioned by the party to it, not by a bystander.
        UnitBooking booking = null;
        PurchaseRequest offer = null;
        Property property;
        if (blankToNull(request.bookingId()) != null) {
            booking = bookings.findById(HashIdUtil.decodeId(request.bookingId().trim()))
                    .orElseThrow(() -> new ResourceNotFoundException("Booking", request.bookingId()));
            requireParty(caller, booking.getTenantId(), booking.getInstitutionId());
            property = properties.findById(booking.getPropertyId())
                    .orElseThrow(() -> new ResourceNotFoundException("Listing", request.bookingId()));
        } else if (blankToNull(request.offerReference()) != null) {
            offer = offers.findByReference(request.offerReference().trim())
                    .orElseThrow(() -> new ResourceNotFoundException("Offer", request.offerReference()));
            requireParty(caller, offer.getTenantId(), null);
            property = properties.findById(offer.getPropertyId())
                    .orElseThrow(() -> new ResourceNotFoundException("Listing", request.offerReference()));
        } else {
            if (blankToNull(request.propertyReference()) == null) {
                throw new HodiException("Which listing is this about?", HttpStatus.BAD_REQUEST);
            }
            property = properties.findLiveByReference(trim(request.propertyReference()))
                    .orElseThrow(() -> new ResourceNotFoundException("Listing", request.propertyReference()));
        }

        ValuationRequest job = requests.save(ValuationRequest.builder()
                .reference(nextReference())
                .bookingId(booking == null ? null : booking.getId())
                .offerId(offer == null ? null : offer.getId())
                .propertyId(property.getId())
                .propertyReference(property.getReference())
                .propertyTitle(property.getTitle())
                .propertyPrice(property.getPrice())
                .county(property.getCounty())
                .tenantId(caller.getTenantId())
                // The requester's own name, from the principal — not the listing's seller, which is a
                // different organisation whenever the bank commissions the valuation.
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

        event(job, ValuationEvent.RAISED, blankToNull(request.note()));
        audit.record(AppConstant.ACTION_CREATE, "ValuationRequest", job.getId(), null,
                job.getReference() + " on " + job.getPropertyReference() + " for " + job.getPurpose());
        notifier.platformRaised(job);
        return toResponse(job);
    }

    /** The caller's organisation is a party to the sale: the seller who made it, or the bank financing it. */
    private static void requireParty(UserPrincipal caller, Long tenantId, Long institutionId) {
        if (caller.isPlatformStaff()) return;
        boolean seller = caller.getTenantId() != null && caller.getTenantId().equals(tenantId);
        boolean bank = caller.getInstitutionId() != null && caller.getInstitutionId().equals(institutionId);
        if (!seller && !bank) {
            throw new HodiException("That sale is not yours to commission a valuation for.", HttpStatus.FORBIDDEN);
        }
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
        // The last hand-back's reason belonged to the last valuer; the event keeps it, the job does not.
        job.setDeclinedReason(null);
        job.setState(AppConstant.VALUATION_ASSIGNED);
        if (request != null && request.dueOn() != null) job.setDueOn(request.dueOn());
        if (request != null && request.feeAmount() != null) job.setFeeAmount(request.feeAmount());
        job.setUpdatedBy(AuthContext.username());
        requests.save(job);

        valuer.setOpenAssignments(valuer.getOpenAssignments() + 1);
        valuer.setLastAssignedAt(OffsetDateTime.now());
        valuers.save(valuer);

        event(job, ValuationEvent.ASSIGNED, valuer.getFullName() + (manual ? ", chosen by name" : ", chosen by the panel"));
        audit.record(AppConstant.AUDIT_VALUATION_ASSIGN, "ValuationRequest", job.getId(), null,
                job.getReference() + " → " + valuer.getReference() + " (" + job.getAssignmentMethod() + ")");
        notifier.valuerAssigned(job, valuer);
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
        ValuationRequest saved = requests.save(job);
        event(saved, ValuationEvent.ACCEPTED, null);
        audit.record(AppConstant.ACTION_UPDATE, "ValuationRequest", saved.getId(), null, saved.getReference() + " accepted");
        notifier.requesterAccepted(saved);
        return toResponse(saved);
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
        if (!AppConstant.VALUATION_ASSIGNED.equals(job.getState())
                && !AppConstant.VALUATION_IN_PROGRESS.equals(job.getState())) {
            throw new HodiException("That job is not yours to hand back any more.", HttpStatus.CONFLICT);
        }

        String who = job.getValuerName();
        releaseValuer(job);
        // The appointment was theirs; the next valuer books their own, and the diary entry goes with it.
        if (job.getInspectionAt() != null) {
            job.setInspectionAt(null);
            calendar.project(OperationsConstants.SOURCE_VALUATION, job.getId(), job.getReference(),
                    null, null, null, null, null, null, null, null);
        }
        job.setDeclinedReason(request.reason().trim());
        job.setValuerProfileId(null);
        job.setValuerName(null);
        job.setAssignedAt(null);
        job.setAssignmentMethod(null);
        job.setState(AppConstant.VALUATION_REQUESTED);
        job.setUpdatedBy(AuthContext.username());
        ValuationRequest saved = requests.save(job);
        event(saved, ValuationEvent.HANDED_BACK, request.reason().trim());
        audit.record(AppConstant.ACTION_UPDATE, "ValuationRequest", saved.getId(), who,
                saved.getReference() + " handed back: " + request.reason().trim());
        notifier.platformHandedBack(saved, who, request.reason().trim());
        return toResponse(saved);
    }

    /**
     * The answer, submitted for review.
     *
     * <p>Lands the job in SUBMITTED and puts it in front of the platform's reviewer. Written once per
     * submission: a report sent back is removed with the send-back, so the valuer submits a corrected one;
     * an approved report is never edited — a corrected figure after that is a new job.
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
                    "A report has already been submitted for this job and is awaiting review.", HttpStatus.CONFLICT);
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
                .inspectedOn(request.inspectedOn() != null ? request.inspectedOn()
                        : job.getInspectionAt() == null ? null : job.getInspectionAt().toLocalDate())
                .conditionNote(blankToNull(request.conditionNote()))
                .assumptions(blankToNull(request.assumptions()))
                .comparables(blankToNull(request.comparables()))
                .documentReference(blankToNull(request.documentReference()))
                .submittedByUserId(AuthContext.userId())
                .createdBy(AuthContext.username())
                .updatedBy(AuthContext.username())
                .build());

        job.setState(AppConstant.VALUATION_SUBMITTED);
        job.setSubmittedAt(OffsetDateTime.now());
        job.setReviewedBy(null);
        job.setReviewedAt(null);
        job.setReviewNote(null);
        job.setUpdatedBy(AuthContext.username());
        requests.save(job);

        // What the reviewer reads: the figures, how they were reached, against what was asked.
        ChangeSet.Snapshot what = ChangeSet.of()
                .put("property", "Property", job.getPropertyTitle() + " (" + job.getPropertyReference() + ")")
                .put("asking", "Asking price", money(job.getPropertyPrice(), job.getCurrency()))
                .put("marketValue", "Market value", money(request.marketValue(), job.getCurrency()))
                .put("forcedSale", "Forced-sale value", money(request.forcedSaleValue(), job.getCurrency()))
                .put("insurance", "Insurance value", money(request.insuranceValue(), job.getCurrency()))
                .put("method", "Method", methodology(request.methodology()))
                .put("inspected", "Inspected on", request.inspectedOn() == null ? null : request.inspectedOn().toString())
                .put("condition", "Condition", blankToNull(request.conditionNote()))
                .put("assumptions", "Assumptions", blankToNull(request.assumptions()))
                .put("comparables", "Comparables", blankToNull(request.comparables()))
                .put("valuer", "Valuer", job.getValuerName());
        // Scoped to nobody's organisation: the platform reviews, the requester reads the outcome.
        approvals.submitOrRestate(AppConstant.APPROVAL_ENTITY_VALUATION, job.getId(), AppConstant.APPROVAL_ACTION_REPORT,
                null, null,
                job.getReference() + " — " + job.getPropertyTitle() + " valued at " + money(request.marketValue(), job.getCurrency()),
                "A valuation report. Check the figures against the asking price, the method and the comparables "
                        + "before it becomes the figure the bank lends against.",
                null, what);

        event(job, ValuationEvent.REPORTED, "Market value " + money(request.marketValue(), job.getCurrency()));
        audit.record(AppConstant.AUDIT_VALUATION_REPORT, "ValuationRequest", job.getId(), null,
                job.getReference() + " valued at " + request.marketValue() + " " + job.getCurrency() + ", awaiting review");
        notifier.platformReported(job, money(request.marketValue(), job.getCurrency()));
        return toResponse(job);
    }

    // ── review ────────────────────────────────────────────────────────────────

    /**
     * The reviewer's decision, from the job's own page. The approval engine applies the generic rules — the
     * permission, and not the person who submitted — and calls back into {@link #applyApproval} or
     * {@link #applyRefusal}.
     */
    @Transactional
    public ValuationResponse review(String reference, ReviewRequest request) {
        ValuationRequest job = load(reference);
        if (!job.isAwaitingReview()) {
            throw new HodiException("That valuation has no report awaiting review.", HttpStatus.CONFLICT);
        }
        approvals.decideFor(AppConstant.APPROVAL_ENTITY_VALUATION, job.getId(), AppConstant.APPROVAL_ACTION_REPORT,
                new ApprovalService.DecisionRequest(request.decision(), request.reason()));
        // The page that asked is the job's own, so it gets the job in full, timeline included.
        return find(reference);
    }

    /** Approved: the figure counts, the job is done, the valuer's tally goes up. */
    @Transactional
    public void applyApproval(Long jobId, String checker, String note) {
        ValuationRequest job = requests.findById(jobId).orElseThrow();
        if (!job.isAwaitingReview()) return;
        job.setCompletedAt(OffsetDateTime.now());
        // Completed before the valuer is released, so the release counts it — the order this used to get wrong.
        releaseValuer(job);
        job.setState(AppConstant.VALUATION_COMPLETED);
        job.setReviewedBy(checker);
        job.setReviewedAt(OffsetDateTime.now());
        job.setReviewNote(blankToNull(note));
        job.setUpdatedBy(checker);
        requests.save(job);
        event(job, ValuationEvent.APPROVED, blankToNull(note));
        audit.record(AppConstant.AUDIT_VALUATION_REVIEW, "ValuationRequest", job.getId(), null,
                job.getReference() + " approved by " + checker);
        calendar.closeProjection(OperationsConstants.SOURCE_VALUATION, job.getId(), false);
        String figure = reports.findByRequestId(job.getId())
                .map(r -> money(r.getMarketValue(), r.getCurrency())).orElse("the submitted figure");
        notifier.requesterApproved(job, figure);
        log.info("Valuation {} approved by {}", job.getReference(), checker);
    }

    /**
     * Sent back or rejected: the report is removed and the job returns to the valuer as IN_PROGRESS, with
     * the reviewer's reason, for a corrected submission — or a hand-back, if they cannot stand behind it.
     */
    @Transactional
    public void applyRefusal(Long jobId, String checker, String reason) {
        ValuationRequest job = requests.findById(jobId).orElseThrow();
        if (!job.isAwaitingReview()) return;
        reports.findByRequestId(job.getId()).ifPresent(reports::delete);
        job.setState(AppConstant.VALUATION_IN_PROGRESS);
        job.setSubmittedAt(null);
        job.setReviewedBy(checker);
        job.setReviewedAt(OffsetDateTime.now());
        job.setReviewNote(blankToNull(reason));
        job.setUpdatedBy(checker);
        requests.save(job);
        event(job, ValuationEvent.SENT_BACK, blankToNull(reason));
        audit.record(AppConstant.AUDIT_VALUATION_REVIEW, "ValuationRequest", job.getId(), null,
                job.getReference() + " sent back by " + checker + (reason == null ? "" : ": " + reason));
        valuerOf(job).ifPresent(valuer -> notifier.valuerSentBack(job, valuer, blankToNull(reason)));
        log.info("Valuation {} sent back by {}", job.getReference(), checker);
    }

    private static String money(BigDecimal amount, String currency) {
        return amount == null ? null : (currency == null ? "KES" : currency) + " " + amount.toPlainString();
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
                awaitingReviewIs(request.getAwaitingReview()),
                ValuationScope.restrict(myValuerProfileId().orElse(null)));

        var page = requests.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    /** One job in full, with its timeline. */
    @Transactional(readOnly = true)
    public ValuationResponse find(String reference) {
        ValuationRequest job = load(reference);
        List<EventResponse> timeline = events.findByRequestIdOrderByCreatedAtAsc(job.getId()).stream()
                .map(e -> new EventResponse(e.getAction(), e.getState(), e.getActor(), e.getActorRole(), e.getNote(), e.getCreatedAt()))
                .toList();
        return toResponse(job, timeline);
    }

    @Transactional(readOnly = true)
    public long unassignedCount() {
        return requests.countUnassigned();
    }

    /** What the platform sent about this job, to anyone the job is visible to. */
    @Transactional(readOnly = true)
    public List<com.hodi.modules.notifications.NotificationService.LogRow> sentMessages(String reference) {
        return notificationLog.about("VALUATION", load(reference).getId());
    }

    // ── cancellation ──────────────────────────────────────────────────────────

    /** The requester changing their mind. Only they, or the platform, and only before it is answered. */
    @Transactional
    public ValuationResponse cancel(String reference, DeclineRequest request) {
        ValuationRequest job = load(reference);
        if (!job.isOpen()) {
            throw new HodiException("That valuation is already settled.", HttpStatus.CONFLICT);
        }
        if (job.isAwaitingReview()) {
            throw new HodiException("The report is in and awaiting review; decide on it rather than cancelling "
                    + "the job.", HttpStatus.CONFLICT);
        }
        Optional<ValuerProfile> valuer = valuerOf(job);
        releaseValuer(job);
        job.setState(AppConstant.VALUATION_CANCELLED);
        job.setCancelledReason(request == null ? null : blankToNull(request.reason()));
        job.setUpdatedBy(AuthContext.username());
        ValuationRequest saved = requests.save(job);
        event(saved, ValuationEvent.CANCELLED, saved.getCancelledReason());
        audit.record(AppConstant.ACTION_UPDATE, "ValuationRequest", saved.getId(), null,
                saved.getReference() + " cancelled" + (saved.getCancelledReason() == null ? "" : ": " + saved.getCancelledReason()));
        calendar.closeProjection(OperationsConstants.SOURCE_VALUATION, saved.getId(), true);
        valuer.ifPresent(v -> notifier.valuerCancelled(saved, v, saved.getCancelledReason()));
        // The requester hears of it when somebody else cancelled; their own cancellation is not news to them.
        if (AuthContext.current().map(UserPrincipal::isPlatformStaff).orElse(false)) {
            notifier.requesterCancelled(saved, saved.getCancelledReason());
        }
        return toResponse(saved);
    }

    // ── the inspection and the signed report ──────────────────────────────────

    /**
     * The valuer books when they will be on site.
     *
     * <p>A time on the job, projected into the requester's diary and told to them — a seller wants to know
     * when somebody will be walking through the house. Re-booking overwrites; the diary follows.
     */
    @Transactional
    public ValuationResponse scheduleInspection(String reference, InspectionRequest request) {
        ValuationRequest job = loadForValuer(reference);
        if (!job.isWithValuer()) {
            throw new HodiException("That job is not open for an inspection.", HttpStatus.CONFLICT);
        }
        if (request.at().isBefore(OffsetDateTime.now().minusHours(1))) {
            throw new HodiException("An inspection is booked for a time still to come.", HttpStatus.BAD_REQUEST);
        }
        job.setInspectionAt(request.at());
        job.setUpdatedBy(AuthContext.username());
        ValuationRequest saved = requests.save(job);

        UserPrincipal caller = AuthContext.require();
        calendar.project(OperationsConstants.SOURCE_VALUATION, saved.getId(), saved.getReference(),
                "Valuation inspection: " + saved.getPropertyTitle(),
                saved.getReference() + " for " + (saved.getTenantName() != null ? saved.getTenantName()
                        : saved.getInstitutionName()) + " — " + saved.getValuerName(),
                saved.getPropertyTitle() + (saved.getCounty() == null ? "" : ", " + saved.getCounty()),
                request.at(), saved.getTenantId(), saved.getTenantName(),
                caller.getUserId(), caller.getFullName());

        event(saved, ValuationEvent.INSPECTION, "On site " + request.at().toString());
        audit.record(AppConstant.ACTION_UPDATE, "ValuationRequest", saved.getId(), null,
                saved.getReference() + " inspection booked for " + request.at());
        notifier.requesterInspection(saved);
        return toResponse(saved);
    }

    /**
     * The signed report, into the vault.
     *
     * <p>Attached by the valuer to a report they have submitted and that is still awaiting review — after
     * approval nothing on the report changes, the document included. Replacing one archives nothing: the
     * newer document is the one referenced, and the vault's own audit says what was read when.
     *
     * <p>Sight of it follows the job, not an ACL: the requester, the bank and the platform read it through
     * {@link #reportDocument}, which applies {@link ValuationScope} and then reads on trust.
     */
    @Transactional
    public ValuationResponse attachReportDocument(String reference, MultipartFile file) {
        ValuationRequest job = loadForValuer(reference);
        if (!job.isAwaitingReview()) {
            throw new HodiException("The signed report is attached to a submitted report awaiting review.",
                    HttpStatus.CONFLICT);
        }
        ValuationReport report = reports.findByRequestId(job.getId())
                .orElseThrow(() -> new HodiException("Submit the figures first.", HttpStatus.CONFLICT));
        if (file == null || file.isEmpty()) {
            throw new HodiException("Choose the signed report to attach.", HttpStatus.BAD_REQUEST);
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        boolean pdf = "application/pdf".equalsIgnoreCase(file.getContentType()) || name.endsWith(".pdf");
        if (!pdf) {
            throw new HodiException("The signed report is a PDF.", HttpStatus.BAD_REQUEST);
        }

        VaultDocument stored = documents.store(file, "valuations", "VALUATION_REPORT",
                "Valuation report " + job.getReference(), job.getTenantId(), AuthContext.userId(),
                report.getInspectedOn(), null, "VALUATIONS_APPROVE");
        report.setDocumentId(stored.getId());
        report.setDocumentReference(stored.getReference());
        report.setUpdatedBy(AuthContext.username());
        report.setUpdatedAt(OffsetDateTime.now());
        reports.save(report);

        event(job, ValuationEvent.DOCUMENTED, stored.getOriginalName());
        audit.record(AppConstant.ACTION_UPDATE, "ValuationRequest", job.getId(), null,
                job.getReference() + " signed report attached: " + stored.getReference());
        return toResponse(job);
    }

    /** The signed report's bytes, for anyone the job is visible to. */
    @Transactional
    public DocumentService.Fetched reportDocument(String reference) {
        ValuationRequest job = load(reference);
        ValuationReport report = reports.findByRequestId(job.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Report", reference));
        if (report.getDocumentId() == null) {
            throw new ResourceNotFoundException("Signed report", reference);
        }
        VaultDocument document = vaultDocuments.findById(report.getDocumentId())
                .orElseThrow(() -> new ResourceNotFoundException("Signed report", reference));
        return documents.readTrusted(document, "valuation " + job.getReference());
    }

    /** One row of the timeline, in the same transaction as the change it records. */
    private void event(ValuationRequest job, String action, String note) {
        UserPrincipal caller = AuthContext.current().orElse(null);
        events.save(ValuationEvent.builder()
                .requestId(job.getId()).action(action).state(job.getState())
                .actor(caller == null ? "system" : caller.getUsername())
                .actorRole(caller == null ? null : caller.getActorClass())
                .note(note).build());
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

    /** The valuer on the job, if one is. */
    private Optional<ValuerProfile> valuerOf(ValuationRequest job) {
        return job.getValuerProfileId() == null ? Optional.empty() : valuers.findById(job.getValuerProfileId());
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
            // Only a completed job counts as completed: the approval sets completedAt before it releases the
            // valuer, and a hand-back or a cancellation never does.
            if (job.getCompletedAt() != null) {
                valuer.setCompletedCount(valuer.getCompletedCount() + 1);
            }
            valuers.save(valuer);
        });
    }

    private Specification<ValuationRequest> unassignedIs(Boolean unassigned) {
        if (!Boolean.TRUE.equals(unassigned)) return null;
        return (root, query, cb) -> cb.equal(root.get("state"), AppConstant.VALUATION_REQUESTED);
    }

    private Specification<ValuationRequest> awaitingReviewIs(Boolean awaiting) {
        if (!Boolean.TRUE.equals(awaiting)) return null;
        return (root, query, cb) -> cb.equal(root.get("state"), AppConstant.VALUATION_SUBMITTED);
    }

    private ValuationResponse toResponse(ValuationRequest job) {
        return toResponse(job, List.of());
    }

    private ValuationResponse toResponse(ValuationRequest job, List<EventResponse> timeline) {
        ReportResponse report = reports.findByRequestId(job.getId())
                .map(r -> new ReportResponse(r.getMarketValue(), r.getForcedSaleValue(),
                        r.getInsuranceValue(), r.getCurrency(), r.getMethodology(), r.getInspectedOn(),
                        r.getConditionNote(), r.getAssumptions(), r.getComparables(),
                        r.getDocumentReference(),
                        r.getDocumentId() == null ? null
                                : vaultDocuments.findById(r.getDocumentId()).map(VaultDocument::getOriginalName).orElse(null),
                        r.getSubmittedAt()))
                .orElse(null);

        String valuerReference = job.getValuerProfileId() == null ? null
                : valuers.findById(job.getValuerProfileId()).map(ValuerProfile::getReference).orElse(null);
        String bookingReference = job.getBookingId() == null ? null
                : bookings.findById(job.getBookingId()).map(UnitBooking::getReference).orElse(null);
        String offerReference = job.getOfferId() == null ? null
                : offers.findById(job.getOfferId()).map(PurchaseRequest::getReference).orElse(null);

        UserPrincipal caller = AuthContext.current().orElse(null);
        boolean platform = caller != null && caller.isPlatformStaff();
        Long mine = myValuerProfileId().orElse(null);
        boolean myJob = mine != null && mine.equals(job.getValuerProfileId());
        String state = job.getState();
        boolean mayAssign = platform && AuthContext.hasAuthority("VALUATIONS_ASSIGN") && job.isUnassigned();
        boolean mayAccept = myJob && AuthContext.hasAuthority("VALUATIONS_WORK") && AppConstant.VALUATION_ASSIGNED.equals(state);
        boolean mayDecline = myJob && AuthContext.hasAuthority("VALUATIONS_WORK")
                && (AppConstant.VALUATION_ASSIGNED.equals(state) || AppConstant.VALUATION_IN_PROGRESS.equals(state));
        boolean mayReport = mayDecline;
        boolean mayApprove = platform && AuthContext.hasAuthority("VALUATIONS_APPROVE") && job.isAwaitingReview();
        boolean mayCancel = AuthContext.hasAuthority("VALUATIONS_CANCEL") && job.isOpen() && !job.isAwaitingReview() && !myJob;
        boolean mayInspect = myJob && AuthContext.hasAuthority("VALUATIONS_WORK") && job.isWithValuer();
        boolean mayAttachReport = myJob && AuthContext.hasAuthority("VALUATIONS_WORK") && job.isAwaitingReview();
        int handBacks = (int) events.countByRequestIdAndAction(job.getId(), ValuationEvent.HANDED_BACK);

        return new ValuationResponse(
                job.getReference(), job.getPropertyReference(), job.getPropertyTitle(),
                job.getPropertyPrice(), job.getCounty(),
                job.getTenantName() != null ? job.getTenantName() : job.getInstitutionName(),
                job.getPurpose(), job.getState(), job.getValuerName(), valuerReference,
                job.getAssignedAt(), job.getAssignmentMethod(), job.getDueOn(), job.getFeeAmount(),
                job.getCurrency(), job.getRequesterNote(), job.getDeclinedReason(),
                job.getCancelledReason(), job.getCompletedAt(), job.getInspectionAt(),
                job.getBookingId() == null ? null : HashIdUtil.encodeId(job.getBookingId()), bookingReference, offerReference,
                report, job.getCreatedAt(),
                job.getSubmittedAt(), job.getReviewedBy(), job.getReviewedAt(), job.getReviewNote(),
                mayAssign, mayAccept, mayDecline, mayReport, mayApprove, mayCancel, mayInspect, mayAttachReport,
                handBacks, timeline);
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
