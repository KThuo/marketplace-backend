package com.hodi.modules.auctions;

import com.hodi.common.dto.PagedDataRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Request and response shapes for M6.
 *
 * <h2>The reserve is the reason there are two lot records</h2>
 *
 * <p>{@link LotResponse} carries the reserve price; {@link PublicLot} does not have the field at all. A
 * reserve leaking to bidders would tell them exactly what to stop at, which is the one number an auction
 * depends on nobody knowing. Two records rather than one with a field blanked, for the reason the listing
 * split exists: a response that sometimes carries a reserve is one somebody will eventually forget to blank.
 */
public final class AuctionDtos {

    private AuctionDtos() {}

    // ── auctioneers ───────────────────────────────────────────────────────────

    public record AuctioneerResponse(
            String reference,
            String name,
            String firmName,
            String licenceNumber,
            LocalDate licenceExpiresOn,
            /** Whether the platform may publish a sale under them today. */
            boolean licensed,
            String contactName,
            String contactEmail,
            String contactPhone,
            String counties,
            Integer status,
            String statusFlag,
            OffsetDateTime createdAt) {}

    public record SaveAuctioneerRequest(
            @NotBlank(message = "A name is required") @Size(max = 255) String name,
            @Size(max = 255) String firmName,
            @Size(max = 64) String licenceNumber,
            LocalDate licenceExpiresOn,
            @Size(max = 160) String contactName,
            @Size(max = 128) String contactEmail,
            @Size(max = 32) String contactPhone,
            String counties) {}

    // ── lots, the inside view ─────────────────────────────────────────────────

    public record LotResponse(
            String reference,
            String lotNumber,
            String title,
            String description,
            String propertyType,
            String county,
            String town,
            String estate,
            String addressLine,
            BigDecimal latitude,
            BigDecimal longitude,
            String titleNumber,
            BigDecimal plotAreaAcres,
            Short bedrooms,
            BigDecimal guidePrice,
            /** Never on {@link PublicLot}. */
            BigDecimal reservePrice,
            String currency,
            BigDecimal depositRequired,
            OffsetDateTime auctionDate,
            String venue,
            /** Where the room is, which is not where the property is. */
            BigDecimal venueLatitude,
            BigDecimal venueLongitude,
            String viewingNotes,
            String terms,
            String auctioneerReference,
            String auctioneerName,
            String broughtBy,
            String state,
            OffsetDateTime publishedAt,
            BigDecimal soldPrice,
            OffsetDateTime soldAt,
            String outcomeNote,
            String primaryImageUrl,
            long approvedBidders,
            OffsetDateTime createdAt) {}

    public record SaveLotRequest(
            @Size(max = 16) String lotNumber,
            @NotBlank(message = "Give the lot a title") @Size(max = 255) String title,
            String description,
            @NotBlank(message = "What kind of property is it?") String propertyType,
            String county,
            String town,
            String estate,
            String addressLine,
            BigDecimal latitude,
            BigDecimal longitude,
            @Size(max = 64) String titleNumber,
            BigDecimal plotAreaAcres,
            Short bedrooms,
            BigDecimal guidePrice,
            BigDecimal reservePrice,
            BigDecimal depositRequired,
            OffsetDateTime auctionDate,
            @Size(max = 255) String venue,
            BigDecimal venueLatitude,
            BigDecimal venueLongitude,
            String viewingNotes,
            String terms,
            /** The auctioneer's reference. Required before the lot can be published, not before it exists. */
            String auctioneerReference) {}

    public record ResultRequest(
            @NotBlank(message = "Say what happened") String outcome,
            BigDecimal soldPrice,
            String note) {}

    @Getter
    @Setter
    public static class LotListRequest extends PagedDataRequest {
        private String state;
        private String county;
        private String propertyType;
    }

    // ── lots, the public view ─────────────────────────────────────────────────

    /**
     * What a bidder sees.
     *
     * <p>No reserve, no principal's identity, no lifecycle. The address <em>is</em> here, unlike a
     * marketplace listing — a bidder has to find the property to inspect it, and a public auction notice
     * carries the address anyway.
     */
    public record PublicLot(
            String reference,
            String lotNumber,
            String title,
            String description,
            String propertyType,
            String county,
            String town,
            String estate,
            String addressLine,
            BigDecimal latitude,
            BigDecimal longitude,
            String titleNumber,
            BigDecimal plotAreaAcres,
            Short bedrooms,
            BigDecimal guidePrice,
            String currency,
            BigDecimal depositRequired,
            OffsetDateTime auctionDate,
            String venue,
            BigDecimal venueLatitude,
            BigDecimal venueLongitude,
            String viewingNotes,
            String terms,
            String auctioneerName,
            String primaryImageUrl) {}

    public record AuctionFacets(
            List<String> counties,
            List<PropertyTypeCount> propertyTypes,
            long upcomingCount) {}

    public record PropertyTypeCount(String value, long count) {}

    @Getter
    @Setter
    public static class PublicLotSearchRequest extends PagedDataRequest {
        private String county;
        private String propertyType;
        private BigDecimal maxGuide;
    }

    // ── bidder registration ───────────────────────────────────────────────────

    public record RegistrationResponse(
            String reference,
            String lotReference,
            String lotTitle,
            OffsetDateTime auctionDate,
            String bidderName,
            String bidderEmail,
            String bidderPhone,
            String idNumber,
            String depositReference,
            boolean depositConfirmed,
            String state,
            String decisionNote,
            OffsetDateTime decidedAt,
            OffsetDateTime createdAt) {}

    public record RegisterRequest(
            @NotBlank(message = "Which lot?") String lotReference,
            @Size(max = 64) String idNumber,
            @Size(max = 64) String depositReference,
            @Size(max = 32) String contactPhone) {}

    public record BidderDecisionRequest(
            @NotBlank(message = "Say what you are doing") String decision,
            Boolean depositConfirmed,
            String note) {}

    @Getter
    @Setter
    public static class RegistrationListRequest extends PagedDataRequest {
        private String lotReference;
        private String state;
    }

    /** The counts an auction dashboard draws. */
    public record AuctionCounts(long scheduled, long awaitingBidderDecision) {}
}
