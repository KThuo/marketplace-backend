package com.hodi.modules.buyerportal;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.logging.RequestAction;
import com.hodi.modules.buyerportal.SavedListingService.SaveListingRequest;
import com.hodi.modules.buyerportal.SavedListingService.SavedListingResponse;
import com.hodi.modules.buyerportal.SearchAlertService.AlertResponse;
import com.hodi.modules.buyerportal.SearchAlertService.SaveAlertRequest;
import com.hodi.modules.finance.AffordabilityService;
import com.hodi.modules.leads.EnquiryService;
import com.hodi.modules.leads.PurchaseRequestService;
import com.hodi.modules.leads.SiteVisitService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * A person's own things: their shortlist, their saved searches and their affordability checks.
 *
 * <p>Their enquiries, viewings and offers live in {@code MyLeadController} — same {@code /me} prefix, same
 * identity-scoped rule, but a different module owns them.
 *
 * <h2>No {@code @PreAuthorize}, deliberately</h2>
 *
 * <p>Everything under {@code /api/v1/me} is scoped by the signed-in identity inside the service — the row
 * filter <em>is</em> the authorisation, and there is no endpoint here that takes a user id. Adding a
 * permission would suggest there is something to grant, and the only thing it could grant is the ability to
 * read your own shortlist, which every authenticated caller already has by being themselves.
 *
 * <p>Not gated on {@code BUYER_PORTAL_ACCESS} either. A seller's listing manager browsing the marketplace on
 * their lunch break is house-hunting like anybody else, and a heart that refuses for staff would be a rule
 * with nothing behind it.
 *
 * <h2>Listings by reference; alerts by id</h2>
 *
 * <p>The client reached these listings through the public marketplace, where the HashId salt is fixed for
 * everybody — so an id it is holding would not decode here, on a path where the caller's own salt applies.
 * References are salt-free and are already the marketplace's own handle. Alerts are the caller's own rows,
 * never encoded under the public salt, so they keep ordinary ids.
 */
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class BuyerPortalController {

    private final SavedListingService saved;
    private final SearchAlertService alerts;
    private final AffordabilityService affordability;
    private final EnquiryService enquiries;
    private final SiteVisitService visits;
    private final PurchaseRequestService offers;

    // ── shortlist ─────────────────────────────────────────────────────────────

    @GetMapping("/saved-listings")
    public ApiResponse<PagedResponse<SavedListingResponse>> savedListings(
            @ModelAttribute PagedDataRequest request) {
        return ApiResponse.success(saved.mine(request));
    }

    /** Every saved reference, so the marketplace can draw its hearts in one call rather than per card. */
    @GetMapping("/saved-listings/references")
    public ApiResponse<List<String>> savedReferences() {
        return ApiResponse.success(saved.myReferences());
    }

    @PostMapping("/saved-listings")
    @RequestAction("SAVE LISTING")
    public ApiResponse<SavedListingResponse> save(@RequestBody SaveListingRequest request) {
        return ApiResponse.success("Saved", saved.save(request));
    }

    @DeleteMapping("/saved-listings/{reference}")
    @RequestAction("UNSAVE LISTING")
    public ApiResponse<Void> unsave(@PathVariable String reference) {
        saved.remove(reference);
        return ApiResponse.success("Removed", null);
    }

    // ── saved searches ────────────────────────────────────────────────────────

    @GetMapping("/search-alerts")
    public ApiResponse<PagedResponse<AlertResponse>> searchAlerts(
            @ModelAttribute PagedDataRequest request) {
        return ApiResponse.success(alerts.mine(request));
    }

    @PostMapping("/search-alerts")
    @RequestAction("CREATE SEARCH ALERT")
    public ApiResponse<AlertResponse> createAlert(@Valid @RequestBody SaveAlertRequest request) {
        return ApiResponse.success("Search saved", alerts.create(request));
    }

    @PutMapping("/search-alerts/{hashId}")
    @RequestAction("UPDATE SEARCH ALERT")
    public ApiResponse<AlertResponse> updateAlert(@PathVariable String hashId,
                                                  @Valid @RequestBody SaveAlertRequest request) {
        return ApiResponse.success("Search updated", alerts.update(hashId, request));
    }

    @PostMapping("/search-alerts/{hashId}/pause")
    @RequestAction("PAUSE SEARCH ALERT")
    public ApiResponse<AlertResponse> pauseAlert(@PathVariable String hashId) {
        return ApiResponse.success("Paused", alerts.setRunning(hashId, false));
    }

    @PostMapping("/search-alerts/{hashId}/resume")
    @RequestAction("RESUME SEARCH ALERT")
    public ApiResponse<AlertResponse> resumeAlert(@PathVariable String hashId) {
        return ApiResponse.success("Running again", alerts.setRunning(hashId, true));
    }

    @DeleteMapping("/search-alerts/{hashId}")
    @RequestAction("DELETE SEARCH ALERT")
    public ApiResponse<Void> deleteAlert(@PathVariable String hashId) {
        alerts.delete(hashId);
        return ApiResponse.success("Deleted", null);
    }

    // ── the account page's figures ────────────────────────────────────────────

    /** Every count the overview draws, in one call rather than one request per number. */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Long>> summary() {
        return ApiResponse.success(Map.of(
                "savedListings", saved.myCount(),
                "searchAlerts", alerts.myRunningCount(),
                "affordabilityChecks", affordability.myCount(),
                "enquiries", enquiries.myCount(),
                "viewings", visits.myCount(),
                "offers", offers.myCount()));
    }
}
