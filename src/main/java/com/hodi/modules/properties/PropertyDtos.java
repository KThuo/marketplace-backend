package com.hodi.modules.properties;

import com.hodi.common.dto.PagedDataRequest;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Request and response shapes for listings.
 *
 * <p>Two response shapes, not one. {@link PropertyResponse} is what a seller's own staff see — every field,
 * including the exact address and the listing's state. {@link PublicPropertyResponse} is what the marketplace
 * shows a stranger, and it is a <em>different record</em> rather than the same one with fields blanked: a
 * response that sometimes carries an address is a response somebody will eventually forget to blank.
 */
public final class PropertyDtos {

    private PropertyDtos() {}

    // ── seller side ───────────────────────────────────────────────────────────

    public record PropertyResponse(
            String id,
            String reference,
            String tenantId,
            String tenantName,
            String title,
            String description,
            String propertyType,
            String listingType,
            String tenure,
            BigDecimal price,
            String currency,
            BigDecimal serviceCharge,
            boolean priceNegotiable,
            Short bedrooms,
            Short bathrooms,
            Short parkingSpaces,
            BigDecimal floorAreaSqm,
            BigDecimal plotAreaAcres,
            Short yearBuilt,
            String county,
            String town,
            String estate,
            String addressLine,
            BigDecimal latitude,
            BigDecimal longitude,
            boolean greenCertified,
            String greenCertification,
            String energyRating,
            boolean hasSolar,
            boolean hasBorehole,
            boolean rainwaterHarvesting,
            String listingState,
            OffsetDateTime publishedAt,
            OffsetDateTime soldAt,
            OffsetDateTime withdrawnAt,
            String withdrawnReason,
            String primaryImageUrl,
            int photoCount,
            // Whose property it is (M9, FR161). Null for a seller organisation's own listing — see the
            // entity. The client's name and number are here and deliberately absent from PublicProperty:
            // they belong to somebody who never signed up to this platform.
            String listingOwnership,
            String agentReference,
            String agentName,
            String clientOwnerName,
            String clientOwnerPhone,
            Integer status,
            String statusFlag,
            OffsetDateTime createdAt,
            String createdBy) {}

    public record MediaResponse(
            String id,
            String url,
            String caption,
            int sortOrder,
            boolean primary,
            String contentType,
            Long sizeBytes) {}

    /**
     * @param price required, because a listing without one cannot be compared, filtered or financed — and
     *              "price on application" is a sales tactic that the BRD's affordability tools cannot consume
     */
    public record SavePropertyRequest(
            @NotBlank(message = "Give the listing a title")
            @Size(max = 255, message = "That title is too long")
            String title,

            String description,

            @NotBlank(message = "Choose what kind of property this is")
            String propertyType,

            String listingType,
            String tenure,

            @NotNull(message = "A price is required")
            @DecimalMin(value = "0", message = "A price cannot be negative")
            BigDecimal price,

            BigDecimal serviceCharge,
            Boolean priceNegotiable,

            Short bedrooms,
            Short bathrooms,
            Short parkingSpaces,
            BigDecimal floorAreaSqm,
            BigDecimal plotAreaAcres,
            Short yearBuilt,

            @NotBlank(message = "Which county is it in?")
            String county,
            String town,
            String estate,
            String addressLine,
            BigDecimal latitude,
            BigDecimal longitude,

            Boolean greenCertified,
            String greenCertification,
            String energyRating,
            Boolean hasSolar,
            Boolean hasBorehole,
            Boolean rainwaterHarvesting,

            /**
             * {@code SELF} or {@code CLIENT} (FR161). Required of an agent, ignored from anybody else — a
             * seller organisation listing its own stock is not answering this question.
             */
            String listingOwnership,
            String clientOwnerName,
            String clientOwnerPhone) {}

    public record SubmitRequest(String note) {}

    public record WithdrawRequest(
            @NotBlank(message = "Say why it is coming down") String reason) {}

    /** The seller's own list: their listings in every state. */
    @Getter
    @Setter
    public static class PropertyListRequest extends PagedDataRequest {
        /** {@code DRAFT}, {@code PENDING}, {@code LIVE}, {@code SOLD}, {@code WITHDRAWN}. */
        private String listingState;
        private String propertyType;
        private String county;
    }

    // ── public side ───────────────────────────────────────────────────────────

    /**
     * What a stranger sees.
     *
     * <p>No exact address and no internal state: a buyer gets the town and the estate, and arranges a viewing
     * to get the rest. The coordinates stay, because a map pin at estate precision is what makes a listing
     * findable — and they are the seller's own choice of pin, not a geocode of the door.
     */
    public record PublicPropertyResponse(
            String id,
            String reference,
            String title,
            String description,
            String propertyType,
            String listingType,
            String tenure,
            BigDecimal price,
            String currency,
            BigDecimal serviceCharge,
            boolean priceNegotiable,
            Short bedrooms,
            Short bathrooms,
            Short parkingSpaces,
            BigDecimal floorAreaSqm,
            BigDecimal plotAreaAcres,
            Short yearBuilt,
            String county,
            String town,
            String estate,
            BigDecimal latitude,
            BigDecimal longitude,
            boolean greenCertified,
            String greenCertification,
            String energyRating,
            boolean hasSolar,
            boolean hasBorehole,
            boolean rainwaterHarvesting,
            String sellerName,
            String primaryImageUrl,
            List<String> imageUrls,
            OffsetDateTime publishedAt) {}

    /**
     * The marketplace's filters — the BRD's facets.
     *
     * <p>All optional, and each one a column with an index behind it. A buyer who sets none gets every live
     * listing, newest first, which is the front page.
     */
    @Getter
    @Setter
    public static class PublicSearchRequest extends PagedDataRequest {
        private String propertyType;
        private String county;
        private String town;
        private BigDecimal minPrice;
        private BigDecimal maxPrice;
        private Short minBedrooms;
        private Short maxBedrooms;
        private Boolean greenOnly;
        /** {@code newest}, {@code price-asc}, {@code price-desc}. Anything else is newest. */
        private String sort;
    }

    /** What the marketplace offers to filter by, counted from what is actually live. */
    public record FacetsResponse(
            List<Facet> propertyTypes,
            List<Facet> counties,
            List<String> towns,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            long liveCount) {}

    public record Facet(String value, long count) {}
}
