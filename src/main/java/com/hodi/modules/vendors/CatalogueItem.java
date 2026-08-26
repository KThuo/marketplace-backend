package com.hodi.modules.vendors;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One thing a vendor offers (M10).
 *
 * <p>The price is three fields because a price is three different things depending on the trade: a flat fee,
 * a "from" figure, or a sentence a number cannot carry ("1.5% of the purchase price, minimum KES 35,000").
 * Forcing all of them into one number is how a marketplace displays prices nobody will honour.
 */
@Entity
@Table(name = "catalogue_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CatalogueItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;
    @Column(name = "vendor_id", nullable = false) private Long vendorId;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "vendor_name", length = 255) private String vendorName;

    @Column(name = "category_id", nullable = false) private Long categoryId;
    @Column(name = "category_name", length = 120) private String categoryName;
    /** Cached beside the name, so a list does not resolve it per row. See the migration. */
    @Column(name = "category_code", length = 32) private String categoryCode;

    @Column(nullable = false, length = 255) private String title;
    @Column(columnDefinition = "TEXT") private String description;

    @Column(precision = 15, scale = 2) private BigDecimal price;
    /** Whether {@link #price} is a starting figure rather than the figure. */
    @Column(name = "price_from", nullable = false) @Builder.Default private boolean priceFrom = false;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";
    @Column(length = 32) private String unit;
    @Column(name = "price_note", columnDefinition = "TEXT") private String priceNote;

    @Column(name = "lead_time_days") private Short leadTimeDays;
    @Column(columnDefinition = "TEXT") private String counties;
    @Column(name = "image_key", length = 512) private String imageKey;

    @Column(nullable = false, length = 16) @Builder.Default private String state = VendorState.ITEM_DRAFT;
    @Column(name = "published_at") private OffsetDateTime publishedAt;
    @Column(name = "withdrawn_at") private OffsetDateTime withdrawnAt;
    @Column(name = "withdrawn_reason", columnDefinition = "TEXT") private String withdrawnReason;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false) private String searchText;

    public boolean isLive() {
        return VendorState.ITEM_LIVE.equals(state);
    }

    /** Editable without asking anybody: nobody is reading a draft or a withdrawn item. */
    public boolean isFreelyEditable() {
        return VendorState.ITEM_DRAFT.equals(state) || VendorState.ITEM_WITHDRAWN.equals(state);
    }

    /** Whether it says anything about price at all — what the publication CHECK insists on. */
    public boolean hasPricing() {
        return price != null || (priceNote != null && !priceNote.isBlank());
    }
}
