package com.hodi.modules.properties;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One property, offered by one seller.
 *
 * <p>The first row in this schema that is not about access. Everything before it decides who may act; this is
 * what they act on.
 *
 * <h2>Two lifecycles, deliberately</h2>
 *
 * <p>{@link #listingState} is where the listing stands in the world — a draft, waiting for approval, live,
 * sold, withdrawn. {@code status} is the soft-delete lifecycle every row in this schema carries. They answer
 * different questions: a withdrawn listing is a live row somebody may publish again, and an archived one is
 * gone from every list whatever its listing state says.
 *
 * <p>Only {@code LIVE} is public, and the marketplace query is served by a partial index on exactly that — so
 * a draft cannot appear by somebody forgetting a predicate, because there is no index to serve it from.
 *
 * <h2>Money</h2>
 *
 * <p>{@link BigDecimal} against NUMERIC, never a double: a price is money, and money in binary floating point
 * is money that does not add up. The currency travels with the amount, because a seller's currency is their
 * own column on {@code tenants} rather than the platform's assumption.
 */
@Entity
@Table(name = "properties")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Property {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "tenant_name", length = 255) private String tenantName;

    /** Human-quotable, and what a buyer reads down the phone. Twelve characters, from RrnGenerator. */
    @Column(nullable = false, unique = true, length = 16) private String reference;

    @Column(nullable = false, length = 255) private String title;
    @Column(columnDefinition = "TEXT") private String description;

    @Column(name = "property_type", nullable = false, length = 32) private String propertyType;
    @Column(name = "listing_type", nullable = false, length = 16)
    @Builder.Default private String listingType = AppConstant.LISTING_TYPE_SALE;
    @Column(length = 16) private String tenure;

    @Column(nullable = false, precision = 15, scale = 2) private BigDecimal price;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";
    @Column(name = "service_charge", precision = 15, scale = 2) private BigDecimal serviceCharge;
    @Column(name = "price_negotiable", nullable = false)
    @Builder.Default private boolean priceNegotiable = false;

    private Short bedrooms;
    private Short bathrooms;
    @Column(name = "parking_spaces") private Short parkingSpaces;
    @Column(name = "floor_area_sqm", precision = 10, scale = 2) private BigDecimal floorAreaSqm;
    @Column(name = "plot_area_acres", precision = 10, scale = 3) private BigDecimal plotAreaAcres;
    @Column(name = "year_built") private Short yearBuilt;

    @Column(length = 64) private String county;
    @Column(length = 64) private String town;
    @Column(length = 128) private String estate;
    @Column(name = "address_line", columnDefinition = "TEXT") private String addressLine;
    @Column(precision = 9, scale = 6) private BigDecimal latitude;
    @Column(precision = 9, scale = 6) private BigDecimal longitude;

    @Column(name = "green_certified", nullable = false)
    @Builder.Default private boolean greenCertified = false;
    @Column(name = "green_certification", length = 64) private String greenCertification;
    @Column(name = "energy_rating", length = 8) private String energyRating;
    @Column(name = "has_solar", nullable = false) @Builder.Default private boolean hasSolar = false;
    @Column(name = "has_borehole", nullable = false) @Builder.Default private boolean hasBorehole = false;
    @Column(name = "rainwater_harvesting", nullable = false)
    @Builder.Default private boolean rainwaterHarvesting = false;

    @Column(name = "listing_state", nullable = false, length = 16)
    @Builder.Default private String listingState = AppConstant.LISTING_DRAFT;
    @Column(name = "published_at") private OffsetDateTime publishedAt;
    @Column(name = "sold_at") private OffsetDateTime soldAt;
    @Column(name = "withdrawn_at") private OffsetDateTime withdrawnAt;
    @Column(name = "withdrawn_reason", columnDefinition = "TEXT") private String withdrawnReason;

    /** Label cache for the card. One writer: {@code PropertyMediaService}. */
    @Column(name = "primary_image_key", length = 512) private String primaryImageKey;

    @Column(nullable = false) @Builder.Default private Integer status = 1;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;
    @Column(name = "deactivation_reason", columnDefinition = "TEXT") private String deactivationReason;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;

    // ── whose property this is (M9, BRD FR161) ────────────────────────────────
    // Null on a seller organisation's listing, and honestly so: the question "is this the agent's own or a
    // client's" has no answer when there is no agent. Set for every listing an agent creates.

    @Column(name = "agent_profile_id") private Long agentProfileId;
    @Column(name = "listing_ownership", length = 16) private String listingOwnership;
    /** The client's own details. On the private listing record only — never on the public one. */
    @Column(name = "client_owner_name", length = 160) private String clientOwnerName;
    @Column(name = "client_owner_phone", length = 32) private String clientOwnerPhone;

    // ── paid placement (M13) ──────────────────────────────────────────────────
    // Copied from the running promotion so marketplace search sorts on a column instead of joining. One
    // writer: PromotionService. Zero means nobody has paid for placement on this listing.
    @Column(name = "promotion_boost", nullable = false) @Builder.Default private Integer promotionBoost = 0;
    @Column(name = "promoted_until") private OffsetDateTime promotedUntil;

    /*
     * ── The development this listing belongs to, when it belongs to one ──────────────────────────────
     *
     * Null on every listing that existed before developments arrived, and null on every ordinary resale house
     * afterwards — which is why the migration that added these columns needed no backfill.
     *
     * A listing with a unitTypeId is a typology: "the two-beds at Highrise", one row standing for however many
     * units of that kind exist. It is not shown in the marketplace search list — the development's own card is
     * — but it is a real listing with real enquiries, offers, viewings and photographs, because making six
     * tables polymorphic to avoid it would have been the larger change.
     *
     * The four cached figures below are written by DevelopmentInventoryService and by nothing else.
     */
    @Column(name = "development_id") private Long developmentId;
    @Column(name = "unit_type_id") private Long unitTypeId;
    @Column(name = "development_name", length = 255) private String developmentName;
    @Column(name = "units_available") private Integer unitsAvailable;
    @Column(name = "units_total") private Integer unitsTotal;
    /** PLANNED, UNDER_CONSTRUCTION, COMPLETE or HANDED_OVER. Null for a listing with no build behind it. */
    @Column(name = "construction_status", length = 24) private String constructionStatus;

    /** Part of a development — a typology listing rather than a single house. */
    public boolean isUnitTypeListing() { return unitTypeId != null; }

    public boolean isDraft() {
        return AppConstant.LISTING_DRAFT.equals(listingState);
    }

    public boolean isPending() {
        return AppConstant.LISTING_PENDING.equals(listingState);
    }

    public boolean isLive() {
        return AppConstant.LISTING_LIVE.equals(listingState);
    }

    /**
     * Whether a seller may still edit it without asking anybody.
     *
     * <p>A draft or a withdrawn listing, yes — nobody is reading it. A live one is different: somebody may be
     * enquiring about the price on screen, so an edit takes it back through the queue. That rule lives in the
     * service; this is the question it asks.
     */
    public boolean isFreelyEditable() {
        return isDraft() || AppConstant.LISTING_WITHDRAWN.equals(listingState);
    }
}
