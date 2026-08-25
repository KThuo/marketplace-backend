package com.hodi.modules.leads;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.leads.LeadDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * The buyer's side of M4: what I asked, when I am seeing it, what I offered.
 *
 * <p>No {@code @PreAuthorize} anywhere, and that is the design rather than an omission. Every method is
 * scoped by the signed-in identity inside the service and none takes a user id, so the row filter <em>is</em>
 * the authorisation — the same rule the shortlist, the saved searches and the consent store follow.
 *
 * <p>Separate from {@link LeadController} on purpose: the two carry different authorisation models, and a
 * single file mixing permission-gated methods with identity-scoped ones is how somebody eventually adds the
 * annotation to the wrong half — or, worse, leaves it off one that needed it.
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MyLeadController {

    private final EnquiryService enquiries;
    private final SiteVisitService visits;
    private final PurchaseRequestService offers;

    // ── enquiries ─────────────────────────────────────────────────────────────

    @PostMapping("/enquiries")
    @RequestAction("RAISE ENQUIRY")
    public ApiResponse<EnquiryResponse> raise(@Valid @RequestBody RaiseEnquiryRequest request) {
        return ApiResponse.success("Sent to the seller", enquiries.raise(request));
    }

    @GetMapping("/enquiries")
    public ApiResponse<PagedResponse<EnquiryResponse>> myEnquiries(
            @ModelAttribute EnquiryListRequest request) {
        return ApiResponse.success(enquiries.mine(request));
    }

    @GetMapping("/enquiries/{reference}")
    public ApiResponse<EnquiryResponse> myEnquiry(@PathVariable String reference) {
        return ApiResponse.success(enquiries.mineByReference(reference));
    }

    @PostMapping("/enquiries/{reference}/messages")
    @RequestAction("ADD ENQUIRY MESSAGE")
    public ApiResponse<EnquiryResponse> addMessage(@PathVariable String reference,
                                                   @Valid @RequestBody ReplyRequest request) {
        return ApiResponse.success("Sent", enquiries.addBuyerMessage(reference, request));
    }

    // ── viewings ──────────────────────────────────────────────────────────────

    @PostMapping("/viewings")
    @RequestAction("REQUEST VIEWING")
    public ApiResponse<VisitResponse> requestVisit(@Valid @RequestBody RequestVisitRequest request) {
        return ApiResponse.success("Sent to the seller", visits.request(request));
    }

    @GetMapping("/viewings")
    public ApiResponse<PagedResponse<VisitResponse>> myVisits(@ModelAttribute VisitListRequest request) {
        return ApiResponse.success(visits.mine(request));
    }

    @PostMapping("/viewings/{reference}/cancel")
    @RequestAction("CANCEL VIEWING")
    public ApiResponse<VisitResponse> cancelVisit(
            @PathVariable String reference,
            @RequestBody(required = false) CompleteVisitRequest request) {
        return ApiResponse.success("Cancelled", visits.cancel(reference, request));
    }

    // ── offers ────────────────────────────────────────────────────────────────

    @PostMapping("/offers")
    @RequestAction("SUBMIT OFFER")
    public ApiResponse<OfferResponse> submitOffer(@Valid @RequestBody SubmitOfferRequest request) {
        return ApiResponse.success("Sent to the seller", offers.submit(request));
    }

    @GetMapping("/offers")
    public ApiResponse<PagedResponse<OfferResponse>> myOffers(@ModelAttribute OfferListRequest request) {
        return ApiResponse.success(offers.mine(request));
    }

    @PostMapping("/offers/{reference}/withdraw")
    @RequestAction("WITHDRAW OFFER")
    public ApiResponse<OfferResponse> withdrawOffer(@PathVariable String reference) {
        return ApiResponse.success("Withdrawn", offers.withdraw(reference));
    }
}
