package com.hodi.modules.auctions;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A lot going to auction (M6, BRD UC006).
 *
 * <p><strong>Not a {@code Property}.</strong> There is no foreign key to one and there must not be: UC006
 * requires that auction stock never appears in buyer search, and the way to guarantee that is for a lot not
 * to live in the table the marketplace reads. A flag would have meant every public query on {@code
 * properties} remembering to exclude it — including the queries nobody has written yet.
 *
 * <p>It is also honestly a different thing: a guide price rather than an asking price, a reserve nobody
 * outside the room may see, a date, a venue, a licensed auctioneer, and usually the bank exercising a power
 * of sale rather than an owner who wants to sell.
 */
@Entity
@Table(name = "auction_lots")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuctionLot {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;
    @Column(name = "lot_number", length = 16) private String lotNumber;

    @Column(nullable = false, length = 255) private String title;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(name = "property_type", nullable = false, length = 32) private String propertyType;

    @Column(length = 64) private String county;
    @Column(length = 64) private String town;
    @Column(length = 128) private String estate;
    /** Public here, unlike a marketplace listing: a bidder must be able to find it, and a notice carries it. */
    @Column(name = "address_line", columnDefinition = "TEXT") private String addressLine;
    @Column(precision = 9, scale = 6) private BigDecimal latitude;
    @Column(precision = 9, scale = 6) private BigDecimal longitude;

    @Column(name = "title_number", length = 64) private String titleNumber;
    @Column(name = "plot_area_acres", precision = 10, scale = 3) private BigDecimal plotAreaAcres;
    private Short bedrooms;

    @Column(name = "guide_price", precision = 15, scale = 2) private BigDecimal guidePrice;
    /** Never leaves the building. The public response record does not carry this field at all. */
    @Column(name = "reserve_price", precision = 15, scale = 2) private BigDecimal reservePrice;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";
    @Column(name = "deposit_required", precision = 15, scale = 2) private BigDecimal depositRequired;

    @Column(name = "auction_date") private OffsetDateTime auctionDate;
    @Column(length = 255) private String venue;
    @Column(name = "viewing_notes", columnDefinition = "TEXT") private String viewingNotes;
    @Column(columnDefinition = "TEXT") private String terms;

    @Column(name = "auctioneer_id") private Long auctioneerId;
    @Column(name = "auctioneer_name", length = 255) private String auctioneerName;

    @Column(name = "institution_id") private Long institutionId;
    @Column(name = "institution_name", length = 255) private String institutionName;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "tenant_name", length = 255) private String tenantName;

    @Column(nullable = false, length = 16)
    @Builder.Default private String state = AppConstant.LOT_DRAFT;
    @Column(name = "published_at") private OffsetDateTime publishedAt;
    @Column(name = "sold_price", precision = 15, scale = 2) private BigDecimal soldPrice;
    @Column(name = "sold_at") private OffsetDateTime soldAt;
    @Column(name = "outcome_note", columnDefinition = "TEXT") private String outcomeNote;

    @Column(name = "primary_image_key", length = 512) private String primaryImageKey;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;

    /** In the public catalogue. The only state a member of the public ever sees. */
    public boolean isPublic() {
        return AppConstant.LOT_SCHEDULED.equals(state) && AppConstant.isLive(status);
    }

    /** Still ahead. A lot whose date has passed is not one anybody should be registering to bid on. */
    public boolean isUpcoming() {
        return isPublic() && auctionDate != null && auctionDate.isAfter(OffsetDateTime.now());
    }
}
