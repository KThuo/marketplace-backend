package com.hodi.modules.leads;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.leads.LeadDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * The seller's side of M4: the inbox, the diary and the offers.
 *
 * <p>Every list here is scoped by {@code TenantScope} inside the service and every single-row read asserts
 * against it, so a permission grants sight of <em>your organisation's</em> leads and never anybody else's.
 * The platform sees all of them, which is what oversight means and what lets a one-person seller be helped.
 *
 * <p>The buyer's side is {@link MyLeadController}, deliberately a separate class: the two carry different
 * authorisation models, and one file mixing {@code @PreAuthorize} methods with identity-scoped ones is how
 * somebody eventually adds the annotation to the wrong half.
 */
@RestController
@RequiredArgsConstructor
public class LeadController {

    private final EnquiryService enquiries;
    private final SiteVisitService visits;
    private final PurchaseRequestService offers;

    // ── enquiries ─────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/enquiries/list")
    @PreAuthorize("hasAuthority('ENQUIRIES_VIEW')")
    public ApiResponse<PagedResponse<EnquiryResponse>> listEnquiries(
            @ModelAttribute EnquiryListRequest request) {
        return ApiResponse.success(enquiries.list(request));
    }

    @GetMapping("/api/v1/enquiries/{reference}")
    @PreAuthorize("hasAuthority('ENQUIRIES_VIEW')")
    public ApiResponse<EnquiryResponse> findEnquiry(@PathVariable String reference) {
        return ApiResponse.success(enquiries.find(reference));
    }

    @PostMapping("/api/v1/enquiries/{reference}/reply")
    @PreAuthorize("hasAuthority('ENQUIRIES_REPLY')")
    @RequestAction("REPLY TO ENQUIRY")
    public ApiResponse<EnquiryResponse> reply(@PathVariable String reference,
                                              @Valid @RequestBody ReplyRequest request) {
        return ApiResponse.success("Reply sent", enquiries.reply(reference, request));
    }

    @PostMapping("/api/v1/enquiries/{reference}/assign")
    @PreAuthorize("hasAuthority('ENQUIRIES_ASSIGN')")
    @RequestAction("ASSIGN ENQUIRY")
    public ApiResponse<EnquiryResponse> assign(@PathVariable String reference,
                                               @RequestBody AssignRequest request) {
        return ApiResponse.success("Assigned", enquiries.assign(reference, request));
    }

    @PostMapping("/api/v1/enquiries/{reference}/close")
    @PreAuthorize("hasAuthority('ENQUIRIES_CLOSE')")
    @RequestAction("CLOSE ENQUIRY")
    public ApiResponse<EnquiryResponse> close(@PathVariable String reference,
                                              @RequestBody(required = false) CloseRequest request) {
        return ApiResponse.success("Closed", enquiries.close(reference, request));
    }

    // ── viewings ──────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/viewings/list")
    @PreAuthorize("hasAuthority('SITE_VISITS_VIEW')")
    public ApiResponse<PagedResponse<VisitResponse>> listVisits(
            @ModelAttribute VisitListRequest request) {
        return ApiResponse.success(visits.list(request));
    }

    @PostMapping("/api/v1/viewings/{reference}/decide")
    @PreAuthorize("hasAuthority('SITE_VISITS_DECIDE')")
    @RequestAction("DECIDE VIEWING")
    public ApiResponse<VisitResponse> decideVisit(@PathVariable String reference,
                                                  @Valid @RequestBody DecideVisitRequest request) {
        return ApiResponse.success("Recorded", visits.decide(reference, request));
    }

    @PostMapping("/api/v1/viewings/{reference}/complete")
    @PreAuthorize("hasAuthority('SITE_VISITS_COMPLETE')")
    @RequestAction("COMPLETE VIEWING")
    public ApiResponse<VisitResponse> completeVisit(
            @PathVariable String reference,
            @RequestBody(required = false) CompleteVisitRequest request) {
        return ApiResponse.success("Marked as done", visits.complete(reference, request));
    }

    // ── offers ────────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/offers/list")
    @PreAuthorize("hasAuthority('PURCHASE_REQUESTS_VIEW')")
    public ApiResponse<PagedResponse<OfferResponse>> listOffers(
            @ModelAttribute OfferListRequest request) {
        return ApiResponse.success(offers.list(request));
    }

    @PostMapping("/api/v1/offers/{reference}/decide")
    @PreAuthorize("hasAuthority('PURCHASE_REQUESTS_DECIDE')")
    @RequestAction("DECIDE OFFER")
    public ApiResponse<OfferResponse> decideOffer(@PathVariable String reference,
                                                  @Valid @RequestBody DecideOfferRequest request) {
        return ApiResponse.success("Recorded", offers.decide(reference, request));
    }

    // ── the shell's badges ────────────────────────────────────────────────────

    /**
     * Three counts in one call.
     *
     * <p>Gated on the enquiries permission alone, and each figure is zero for a caller whose organisation
     * has none — a seller with sight of one of the three should not be refused the whole call because they
     * lack the other two.
     */
    @GetMapping("/api/v1/leads/counts")
    @PreAuthorize("hasAnyAuthority('ENQUIRIES_VIEW', 'SITE_VISITS_VIEW', 'PURCHASE_REQUESTS_VIEW')")
    public ApiResponse<LeadCounts> counts() {
        return ApiResponse.success(new LeadCounts(
                enquiries.awaitingForCaller(),
                visits.pendingForCaller(),
                offers.liveForCaller()));
    }
}
