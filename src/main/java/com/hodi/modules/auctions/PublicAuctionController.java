package com.hodi.modules.auctions;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.auctions.AuctionDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * The auction catalogue, and registering to bid on it.
 *
 * <p>A separate path from the marketplace, reading a separate service over a separate table. UC006 asks that
 * auction stock never appear in buyer search; the guarantee here is structural rather than a filter anybody
 * has to remember.
 *
 * <p>Registering needs a session — the endpoints under {@code /me} — while browsing needs nothing. A bidder
 * has to be somebody before an auctioneer can approve them; a passer-by looking at a catalogue does not.
 */
@RestController
@RequiredArgsConstructor
public class PublicAuctionController {

    private final PublicAuctionService catalogue;
    private final BidderRegistrationService bidders;

    // ── the catalogue ─────────────────────────────────────────────────────────

    @GetMapping("/api/v1/public/auctions/search")
    public ApiResponse<PagedResponse<PublicLot>> search(
            @ModelAttribute PublicLotSearchRequest request) {
        return ApiResponse.success(catalogue.search(request));
    }

    @GetMapping("/api/v1/public/auctions/facets")
    public ApiResponse<AuctionFacets> facets() {
        return ApiResponse.success(catalogue.facets());
    }

    @GetMapping("/api/v1/public/auctions/{reference}")
    public ApiResponse<PublicLot> find(@PathVariable String reference) {
        return ApiResponse.success(catalogue.findByReference(reference));
    }

    // ── the bidder's own ──────────────────────────────────────────────────────

    @PostMapping("/api/v1/me/auction-registrations")
    @RequestAction("REGISTER TO BID")
    public ApiResponse<RegistrationResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success("Registered — the auctioneer will confirm", bidders.register(request));
    }

    @GetMapping("/api/v1/me/auction-registrations")
    public ApiResponse<PagedResponse<RegistrationResponse>> mine(
            @ModelAttribute RegistrationListRequest request) {
        return ApiResponse.success(bidders.mine(request));
    }

    @PostMapping("/api/v1/me/auction-registrations/{reference}/withdraw")
    @RequestAction("WITHDRAW BIDDER REGISTRATION")
    public ApiResponse<RegistrationResponse> withdraw(@PathVariable String reference) {
        return ApiResponse.success("Withdrawn", bidders.withdraw(reference));
    }
}
