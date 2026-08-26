package com.hodi.modules.sellerops;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One listing holding one package for a window (M13).
 *
 * <p>The price, the placement and the boost are copied from the package rather than referenced. A package
 * repriced next month must not restate what somebody bought last month — the same reasoning as the
 * commission rate.
 */
@Entity
@Table(name = "listing_promotions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ListingPromotion {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;

    @Column(name = "property_id", nullable = false) private Long propertyId;
    @Column(name = "property_ref", length = 16) private String propertyRef;
    @Column(name = "property_title", length = 255) private String propertyTitle;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "tenant_name", length = 255) private String tenantName;

    @Column(name = "package_id", nullable = false) private Long packageId;
    @Column(name = "package_name", length = 120) private String packageName;
    @Column(nullable = false, length = 24) private String placement;
    @Column(nullable = false) @Builder.Default private Integer boost = 10;
    @Column(nullable = false, precision = 15, scale = 2) private BigDecimal price;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";
    @Column(name = "duration_days", nullable = false) private Integer durationDays;

    @Column(nullable = false, length = 16)
    @Builder.Default private String state = SellerOpsConstants.PROMO_REQUESTED;
    @Column(name = "starts_at") private OffsetDateTime startsAt;
    @Column(name = "ends_at") private OffsetDateTime endsAt;
    @Column(name = "activated_by_user_id") private Long activatedByUserId;
    @Column(name = "cancelled_reason", columnDefinition = "TEXT") private String cancelledReason;
    @Column(columnDefinition = "TEXT") private String note;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false) private String searchText;

    public boolean isRunning() {
        return SellerOpsConstants.PROMO_ACTIVE.equals(state)
                && endsAt != null && endsAt.isAfter(OffsetDateTime.now());
    }
}
