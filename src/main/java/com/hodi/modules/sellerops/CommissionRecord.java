package com.hodi.modules.sellerops;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * What the platform earned on one completed sale (M13).
 *
 * <p>The rate is <strong>copied onto the row</strong> rather than referenced. A rate change next quarter must
 * not silently restate what was owed last quarter, and a report that reads a live setting to explain a
 * historical figure is a report nobody can reconcile.
 */
@Entity
@Table(name = "commission_records")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CommissionRecord {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;

    @Column(name = "property_id", nullable = false) private Long propertyId;
    @Column(name = "property_ref", length = 16) private String propertyRef;
    @Column(name = "property_title", length = 255) private String propertyTitle;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "tenant_name", length = 255) private String tenantName;

    @Column(name = "sale_price", nullable = false, precision = 15, scale = 2) private BigDecimal salePrice;
    @Column(name = "rate_percent", nullable = false, precision = 6, scale = 3)
    private BigDecimal ratePercent;
    @Column(nullable = false, precision = 15, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";

    @Column(name = "sold_at", nullable = false) private OffsetDateTime soldAt;

    @Column(nullable = false, length = 16)
    @Builder.Default private String state = SellerOpsConstants.COMMISSION_DUE;
    @Column(name = "invoice_ref", length = 64) private String invoiceRef;
    @Column(name = "invoiced_at") private OffsetDateTime invoicedAt;
    @Column(name = "paid_at") private OffsetDateTime paidAt;
    @Column(name = "waived_reason", columnDefinition = "TEXT") private String waivedReason;
    @Column(columnDefinition = "TEXT") private String note;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false) private String searchText;

    /** Still owed: neither settled nor written off. */
    public boolean isOutstanding() {
        return SellerOpsConstants.COMMISSION_DUE.equals(state)
                || SellerOpsConstants.COMMISSION_INVOICED.equals(state);
    }
}
