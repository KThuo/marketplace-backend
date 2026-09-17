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
            /** {@code DAY}, {@code WEEK}, {@code MONTH} or {@code YEAR} on a letting; null on a sale. */
            String rentPeriod,
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
            String createdBy,
            /** What it comes with, as codes, so the form opens with them already ticked. */
            java.util.List<String> amenityCodes) {}

    public record MediaResponse(
            String id,
            String url,
            String caption,
            int sortOrder,
            boolean primary,
            /**
             * {@code PHOTO}, {@code FLOOR_PLAN}, {@code CERTIFICATE} and the rest.
             *
             * <p>A listing could not hold a floor plan at all before this — the column did not exist on
             * {@code property_media} — so the plan buyers ask for first had nowhere to go but the
             * photograph gallery, unlabelled and in the middle of the pictures.
             */
            String mediaKind,
            String contentType,
            Long sizeBytes,
            /**
             * Where this file lives: {@code OWN}, {@code TYPOLOGY} or {@code DEVELOPMENT}.
             *
             * <p>A listing in a development shows everything it inherits — the project's site photography
             * and plans as well as its typology's — because that is what a buyer sees on the page, and a
             * seller editing the listing should be looking at the same gallery rather than at a subset of
             * it with no way to tell what is missing.
             *
             * <p>But inherited is not owned. The project's photograph belongs to the project and appears on
             * every listing under it, so the editor shows it and does not offer to delete it; {@code OWN}
             * and {@code TYPOLOGY} rows are this listing's to change. Without this field the screen cannot
             * tell the two apart, and the only safe design would be to hide the inherited ones — which is
             * the complaint.
             */
            String source) {}

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

            /**
             * {@code SALE} or {@code RENT}.
             *
             * <p>{@code RENT} has been legal in the CHECK since the table was created and no screen ever
             * set it — the form hardcoded {@code SALE} — so a lettings listing could not be created at all.
             */
            String listingType,
            /**
             * How long the rent buys. Required when {@code listingType} is {@code RENT}, ignored otherwise.
             *
             * <p>Validated in the service rather than here, because the rule is conditional on another
             * field and a bean-validation annotation that cannot see its sibling would have to be either
             * always-on (breaking every sale) or advisory (which is not validation).
             */
            String rentPeriod,
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
            String clientOwnerPhone,
            /**
             * What the place comes with, as catalogue codes.
             *
             * <p>The whole set every time, not a delta: a save says what the listing has now, and working out
             * what was added and what was removed from two lists is a calculation the client should not be
             * doing. Null leaves them alone, which is how a screen that does not edit amenities saves a
             * listing without wiping them.
             */
            java.util.List<String> amenityCodes) {}

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
            /** {@code DAY}, {@code WEEK}, {@code MONTH} or {@code YEAR} on a letting; null on a sale. */
            String rentPeriod,
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
            /**
             * The plans, kept out of the photographs.
             *
             * <p>A buyer asks for the floor plan before almost anything else, and mixed into the gallery it
             * was a picture they had to find by scrolling. It is a separate list because it is answering a
             * separate question.
             */
            List<String> floorPlanUrls,
            /**
             * Whether somebody is paying for placement on this one (M13).
             *
             * <p>A boolean, not the boost. How hard a listing is being lifted is the platform's commercial
             * business; that it is being lifted at all is something a buyer is entitled to see, which is why
             * the card carries a badge rather than quietly reordering itself.
             */
            boolean promoted,
            OffsetDateTime publishedAt,
            /*
             * What makes this one row stand for many homes.
             *
             * Present only on a typology listing. `unitsTotal` above one is what tells a card to render "25
             * remaining" and to price itself "from" rather than "at" — one row for thirty identical bungalows
             * instead of thirty rows, which is the duplication worth avoiding.
             *
             * The references are for links: the project's page, and the drill-down to the individual homes.
             * Cached on the listing row, so a page of twelve cards needs no joins to build twelve links.
             */
            String developmentName,
            String developmentReference,
            String unitTypeReference,
            Integer unitsAvailable,
            Integer unitsTotal,
            /** PLANNED, UNDER_CONSTRUCTION, COMPLETE or HANDED_OVER. A group can be either. */
            String constructionStatus,
            /**
             * Present on a UNIT: the specific home's own detail — label, floor, its figures resolved against its
             * kind, its features and its state. Null on a house and on a typology card. The unit's page reads
             * this endpoint like any other listing's, which is the point.
             */
            com.hodi.modules.developments.DevelopmentUnitDtos.PublicUnitDetail unit,
            /**
             * What the place comes with, in the order somebody reading a listing cares about them.
             *
             * <p>Both levels where there are two: a unit's own and its typology's, because a buyer does not
             * distinguish them and a list showing one and not the other describes half a home.
             */
            java.util.List<PublicAmenity> amenities) {}

    /**
     * One amenity, as a buyer reads it.
     *
     * <p>Carries the icon key rather than a URL. An amenity list is read by scanning, and a column of
     * identical bullets has to be read word by word — the glyph is what makes "borehole" findable at a
     * glance. The client maps the key to its own icon set and falls back on one it does not know.
     */
    public record PublicAmenity(String code, String name, String category, String icon) {}

    /**
     * The marketplace's filters — the BRD's facets.
     *
     * <p>All optional, and each one a column with an index behind it. A buyer who sets none gets every live
     * listing, newest first, which is the front page.
     */
    @Getter
    @Setter
    public static class PublicSearchRequest extends PagedDataRequest {
        /**
         * {@code SALE} or {@code RENT}. Empty means both.
         *
         * <p>There was no such filter, and no badge either, so the marketplace was implicitly sale-only —
         * a buyer looking for somewhere to rent had no way to say so and no way to tell from a card.
         */
        private String listingType;
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
            /** How many are for sale and how many to let, so the control can say which it is offering. */
            List<Facet> listingTypes,
            List<Facet> counties,
            List<String> towns,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            long liveCount) {}

    public record Facet(String value, long count) {}
}
