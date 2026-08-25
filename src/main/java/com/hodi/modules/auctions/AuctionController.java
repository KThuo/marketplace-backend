package com.hodi.modules.auctions;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.logging.RequestAction;
import com.hodi.modules.auctions.AuctionDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * Auction, from the inside.
 *
 * <p>Note what is <em>not</em> here: any endpoint that reaches a {@code Property}. The auction surface and
 * the marketplace share a database and nothing else, which is how UC006's isolation is kept — see
 * {@link AuctionLot}.
 */
@RestController
@RequiredArgsConstructor
public class AuctionController {

    private final AuctionService auctions;
    private final AuctioneerService auctioneers;
    private final BidderRegistrationService bidders;

    // ── lots ──────────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/auctions/list")
    @PreAuthorize("hasAuthority('AUCTIONS_VIEW')")
    public ApiResponse<PagedResponse<LotResponse>> list(@ModelAttribute LotListRequest request) {
        return ApiResponse.success(auctions.list(request));
    }

    @GetMapping("/api/v1/auctions/{reference}")
    @PreAuthorize("hasAuthority('AUCTIONS_VIEW')")
    public ApiResponse<LotResponse> find(@PathVariable String reference) {
        return ApiResponse.success(auctions.find(reference));
    }

    @PostMapping("/api/v1/auctions/create")
    @PreAuthorize("hasAuthority('AUCTIONS_CREATE')")
    @RequestAction("CREATE AUCTION LOT")
    public ApiResponse<LotResponse> create(@Valid @RequestBody SaveLotRequest request) {
        return ApiResponse.success("Lot created", auctions.create(request));
    }

    @PostMapping("/api/v1/auctions/{reference}/update")
    @PreAuthorize("hasAuthority('AUCTIONS_UPDATE')")
    @RequestAction("UPDATE AUCTION LOT")
    public ApiResponse<LotResponse> update(@PathVariable String reference,
                                           @Valid @RequestBody SaveLotRequest request) {
        return ApiResponse.success("Saved", auctions.update(reference, request));
    }

    @PostMapping("/api/v1/auctions/{reference}/publish")
    @PreAuthorize("hasAuthority('AUCTIONS_PUBLISH')")
    @RequestAction("PUBLISH AUCTION LOT")
    public ApiResponse<LotResponse> publish(@PathVariable String reference) {
        return ApiResponse.success("In the catalogue", auctions.setPublished(reference, true));
    }

    @PostMapping("/api/v1/auctions/{reference}/withdraw")
    @PreAuthorize("hasAuthority('AUCTIONS_PUBLISH')")
    @RequestAction("WITHDRAW AUCTION LOT")
    public ApiResponse<LotResponse> withdraw(@PathVariable String reference) {
        return ApiResponse.success("Out of the catalogue", auctions.setPublished(reference, false));
    }

    @PostMapping("/api/v1/auctions/{reference}/result")
    @PreAuthorize("hasAuthority('AUCTIONS_RESULT')")
    @RequestAction("RECORD AUCTION RESULT")
    public ApiResponse<LotResponse> result(@PathVariable String reference,
                                           @Valid @RequestBody ResultRequest request) {
        return ApiResponse.success("Recorded", auctions.recordResult(reference, request));
    }

    @PostMapping("/api/v1/auctions/{reference}/photo")
    @PreAuthorize("hasAuthority('AUCTIONS_UPDATE')")
    @RequestAction("SET AUCTION PHOTO")
    public ApiResponse<LotResponse> photo(@PathVariable String reference,
                                          @RequestParam("file") MultipartFile file) {
        return ApiResponse.success("Photograph set", auctions.setPhoto(reference, file));
    }

    // ── bidder registrations ──────────────────────────────────────────────────

    @GetMapping("/api/v1/auctions/registrations/list")
    @PreAuthorize("hasAuthority('AUCTIONS_VIEW')")
    public ApiResponse<PagedResponse<RegistrationResponse>> registrations(
            @ModelAttribute RegistrationListRequest request) {
        return ApiResponse.success(bidders.list(request));
    }

    @PostMapping("/api/v1/auctions/registrations/{reference}/decide")
    @PreAuthorize("hasAuthority('AUCTIONS_BIDDERS')")
    @RequestAction("DECIDE BIDDER")
    public ApiResponse<RegistrationResponse> decideBidder(
            @PathVariable String reference, @Valid @RequestBody BidderDecisionRequest request) {
        return ApiResponse.success("Recorded", bidders.decide(reference, request));
    }

    // ── auctioneers ───────────────────────────────────────────────────────────

    @GetMapping("/api/v1/auctioneers/list")
    @PreAuthorize("hasAuthority('AUCTIONEERS_VIEW')")
    public ApiResponse<PagedResponse<AuctioneerResponse>> auctioneerList(
            @ModelAttribute PagedDataRequest request) {
        return ApiResponse.success(auctioneers.list(request));
    }

    @PostMapping("/api/v1/auctioneers/create")
    @PreAuthorize("hasAuthority('AUCTIONEERS_MANAGE')")
    @RequestAction("CREATE AUCTIONEER")
    public ApiResponse<AuctioneerResponse> createAuctioneer(
            @Valid @RequestBody SaveAuctioneerRequest request) {
        return ApiResponse.success("Auctioneer added", auctioneers.create(request));
    }

    @PostMapping("/api/v1/auctioneers/{reference}/update")
    @PreAuthorize("hasAuthority('AUCTIONEERS_MANAGE')")
    @RequestAction("UPDATE AUCTIONEER")
    public ApiResponse<AuctioneerResponse> updateAuctioneer(
            @PathVariable String reference, @Valid @RequestBody SaveAuctioneerRequest request) {
        return ApiResponse.success("Saved", auctioneers.update(reference, request));
    }

    @PostMapping("/api/v1/auctioneers/{reference}/deactivate")
    @PreAuthorize("hasAuthority('AUCTIONEERS_MANAGE')")
    @RequestAction("DEACTIVATE AUCTIONEER")
    public ApiResponse<AuctioneerResponse> deactivateAuctioneer(
            @PathVariable String reference, @RequestBody(required = false) ReasonRequest request) {
        return ApiResponse.success("Deactivated",
                auctioneers.setActive(reference, false, request == null ? null : request.reason()));
    }

    @PostMapping("/api/v1/auctioneers/{reference}/activate")
    @PreAuthorize("hasAuthority('AUCTIONEERS_MANAGE')")
    @RequestAction("ACTIVATE AUCTIONEER")
    public ApiResponse<AuctioneerResponse> activateAuctioneer(@PathVariable String reference) {
        return ApiResponse.success("Activated", auctioneers.setActive(reference, true, null));
    }

    public record ReasonRequest(String reason) {}
}
