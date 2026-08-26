package com.hodi.modules.leads;

import com.hodi.common.AppConstant;
import com.hodi.modules.operations.CalendarService;
import com.hodi.modules.operations.OperationsConstants;
import com.hodi.common.PagedResponse;
import com.hodi.common.RefGenerator;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.leads.LeadDtos.*;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.security.TenantScope;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Viewings: a buyer asks to come, a seller answers (M4, BRD FR041–FR044).
 *
 * <h2>Three answers, and the middle one is the useful one</h2>
 *
 * <p>Confirm, offer a different time, or decline. The middle answer is not a separate state: it confirms the
 * viewing at a time the seller chose, and the buyer's screen shows both what they asked for and what was
 * agreed. A "RESCHEDULED" state would be a state nobody can act on differently from CONFIRMED, and the two
 * timestamps already carry the difference.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SiteVisitService {

    private static final String REFERENCE_PREFIX = "SV";
    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEEE d MMMM 'at' h:mma");

    private final SiteVisitRepository repository;
    private final CalendarService calendar;
    private final PropertyRepository properties;
    private final UserRepository users;
    private final AuditService audit;
    private final LeadNotifier notifier;

    // ── the buyer's side ──────────────────────────────────────────────────────

    @Transactional
    public VisitResponse request(RequestVisitRequest request) {
        Long userId = AuthContext.requireUserId();
        User buyer = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        Property property = properties.findLiveByReference(EnquiryService.trim(request.propertyReference()))
                .orElseThrow(() -> new ResourceNotFoundException("Listing", request.propertyReference()));

        if (request.requestedAt().isBefore(OffsetDateTime.now())) {
            throw new HodiException("Choose a time in the future.", HttpStatus.BAD_REQUEST);
        }

        SiteVisit visit = repository.save(SiteVisit.builder()
                .reference(nextReference())
                .tenantId(property.getTenantId())
                .tenantName(property.getTenantName())
                .propertyId(property.getId())
                .propertyReference(property.getReference())
                .propertyTitle(property.getTitle())
                .userId(userId)
                .buyerName(buyer.fullName())
                .buyerEmail(buyer.getEmail())
                .buyerPhone(EnquiryService.blankTo(request.contactPhone(), buyer.getPhone()))
                .requestedAt(request.requestedAt())
                .partySize(request.partySize())
                .buyerNote(EnquiryService.blankToNull(request.note()))
                .createdBy(buyer.getUsername())
                .updatedBy(buyer.getUsername())
                .build());

        audit.record(AppConstant.AUDIT_VISIT_REQUESTED, "SiteVisit", visit.getId(), null,
                visit.getReference() + " on " + property.getReference());
        notifier.toSeller(property.getTenantId(),
                "Viewing requested: " + property.getTitle(),
                buyer.fullName() + " has asked to view " + property.getTitle() + " on "
                        + when(request.requestedAt()) + ".",
                "/app/viewings?ref=" + visit.getReference());

        return toResponse(visit);
    }

    @Transactional(readOnly = true)
    public PagedResponse<VisitResponse> mine(VisitListRequest request) {
        var page = repository.findMine(AuthContext.requireUserId(),
                request.toPageable(Sort.by(Sort.Direction.DESC, "requestedAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public long myCount() {
        return repository.countByUserId(AuthContext.requireUserId());
    }

    /** The buyer calling it off. Distinct from the seller declining, and recorded as such. */
    @Transactional
    public VisitResponse cancel(String reference, CompleteVisitRequest request) {
        SiteVisit visit = repository
                .findMineByReference(EnquiryService.trim(reference), AuthContext.requireUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Viewing", reference));
        if (!visit.isCancellable()) {
            throw new HodiException("That viewing can no longer be cancelled.", HttpStatus.CONFLICT);
        }

        visit.setState(AppConstant.VISIT_CANCELLED);
        visit.setOutcomeNote(EnquiryService.blankToNull(request == null ? null : request.outcomeNote()));
        visit.setUpdatedBy(AuthContext.username());
        repository.save(visit);

        notifier.toSeller(visit.getTenantId(),
                "Viewing cancelled: " + visit.getPropertyTitle(),
                visit.getBuyerName() + " has cancelled the viewing of " + visit.getPropertyTitle() + ".",
                "/app/viewings?ref=" + visit.getReference());
        return toResponse(visit);
    }

    // ── the seller's side ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<VisitResponse> list(VisitListRequest request) {
        Specification<SiteVisit> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("state", EnquiryService.blankToNull(request.getState())),
                SearchSpecs.eq("propertyReference",
                        EnquiryService.blankToNull(request.getPropertyReference())),
                upcoming(request.getUpcoming()),
                TenantScope.restrict("tenantId"));

        // Upcoming sorts forwards — a diary reads earliest first — and everything else newest first.
        Sort sort = Boolean.TRUE.equals(request.getUpcoming())
                ? Sort.by(Sort.Direction.ASC, "slotAt")
                : Sort.by(Sort.Direction.DESC, "requestedAt");
        return PagedResponse.from(repository.findAll(spec, request.toPageable(sort)), this::toResponse);
    }

    /**
     * Confirm, move or decline.
     *
     * <p>Moving is a confirmation at a different time rather than its own state — see the class note. The
     * buyer is told which of the two happened by comparing the times, which the response carries both of.
     */
    @Transactional
    public VisitResponse decide(String reference, DecideVisitRequest request) {
        SiteVisit visit = loadForSeller(reference);
        if (!AppConstant.VISIT_REQUESTED.equals(visit.getState())
                && !AppConstant.VISIT_CONFIRMED.equals(visit.getState())) {
            throw new HodiException("That viewing has already been settled.", HttpStatus.CONFLICT);
        }

        String decision = EnquiryService.trim(request.decision()).toUpperCase();
        OffsetDateTime now = OffsetDateTime.now();
        String line;

        switch (decision) {
            case "CONFIRM" -> {
                OffsetDateTime slot = request.slotAt() != null ? request.slotAt() : visit.getRequestedAt();
                if (slot.isBefore(now)) {
                    throw new HodiException("Confirm a time in the future.", HttpStatus.BAD_REQUEST);
                }
                visit.setState(AppConstant.VISIT_CONFIRMED);
                visit.setSlotAt(slot);
                line = slot.equals(visit.getRequestedAt())
                        ? visit.getTenantName() + " confirmed your viewing of " + visit.getPropertyTitle()
                                + " on " + when(slot) + "."
                        : visit.getTenantName() + " has offered " + when(slot) + " instead for "
                                + visit.getPropertyTitle() + ".";
            }
            case "DECLINE" -> {
                visit.setState(AppConstant.VISIT_DECLINED);
                line = visit.getTenantName() + " cannot show " + visit.getPropertyTitle()
                        + " at that time.";
            }
            default -> throw new HodiException("Say whether you are confirming or declining.",
                    HttpStatus.BAD_REQUEST);
        }

        visit.setSellerNote(EnquiryService.blankToNull(request.note()));
        visit.setDecidedByUserId(AuthContext.userId());
        visit.setDecidedAt(now);
        visit.setUpdatedBy(AuthContext.username());
        repository.save(visit);

        audit.record(AppConstant.AUDIT_VISIT_DECIDED, "SiteVisit", visit.getId(), null,
                visit.getReference() + " " + visit.getState());
        /*
         * Into the diary (M12).
         *
         * On confirmation there is a time, so there is an event; on a decline there is not, and `project`
         * removes any entry a previous confirmation left. The calendar never throws back into this method —
         * a viewing must not fail to be confirmed because a diary row could not be written.
         */
        calendar.project(OperationsConstants.SOURCE_SITE_VISIT, visit.getId(), visit.getReference(),
                "Viewing — " + visit.getPropertyTitle(),
                visit.getBuyerName() + " · " + visit.getBuyerPhone(),
                visit.getPropertyTitle(),
                AppConstant.VISIT_CONFIRMED.equals(visit.getState()) ? visit.getSlotAt() : null,
                visit.getTenantId(), visit.getTenantName(),
                visit.getDecidedByUserId(), null);
        notifier.toBuyer(visit.getUserId(), "About your viewing of " + visit.getPropertyTitle(), line,
                "/account/viewings?ref=" + visit.getReference());
        return toResponse(visit);
    }

    /** What actually happened. The seller's own note, never shown to the buyer. */
    @Transactional
    public VisitResponse complete(String reference, CompleteVisitRequest request) {
        SiteVisit visit = loadForSeller(reference);
        if (!AppConstant.VISIT_CONFIRMED.equals(visit.getState())) {
            throw new HodiException("Only a confirmed viewing can be marked as done.",
                    HttpStatus.CONFLICT);
        }
        visit.setState(AppConstant.VISIT_COMPLETED);
        visit.setOutcomeNote(EnquiryService.blankToNull(request == null ? null : request.outcomeNote()));
        visit.setUpdatedBy(AuthContext.username());
        calendar.closeProjection(OperationsConstants.SOURCE_SITE_VISIT, visit.getId(), false);
        return toResponse(repository.save(visit));
    }

    @Transactional(readOnly = true)
    public long pendingForCaller() {
        Long tenantId = TenantScope.ownTenantId();
        return tenantId == null ? 0 : repository.countPending(tenantId);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private SiteVisit loadForSeller(String reference) {
        SiteVisit visit = repository.findByReference(EnquiryService.trim(reference))
                .orElseThrow(() -> new ResourceNotFoundException("Viewing", reference));
        TenantScope.assertAllowed(visit.getTenantId());
        return visit;
    }

    private Specification<SiteVisit> upcoming(Boolean upcoming) {
        if (!Boolean.TRUE.equals(upcoming)) return null;
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("state"), AppConstant.VISIT_CONFIRMED),
                cb.greaterThan(root.get("slotAt"), OffsetDateTime.now()));
    }

    private static String when(OffsetDateTime at) {
        return at == null ? "" : WHEN.format(at);
    }

    private VisitResponse toResponse(SiteVisit v) {
        return new VisitResponse(
                v.getReference(), v.getPropertyReference(), v.getPropertyTitle(), v.getTenantName(),
                v.getBuyerName(), v.getBuyerEmail(), v.getBuyerPhone(), v.getRequestedAt(), v.getSlotAt(),
                v.getPartySize(), v.getBuyerNote(), v.getState(), v.getSellerNote(), v.getOutcomeNote(),
                v.getDecidedAt(), v.getCreatedAt());
    }

    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = RefGenerator.getInstance().generate(REFERENCE_PREFIX);
            if (!repository.existsByReference(reference)) return reference;
        }
        throw new HodiException("Could not allocate a reference. Try again.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
