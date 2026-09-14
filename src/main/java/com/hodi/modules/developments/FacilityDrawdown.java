package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One disbursement against a development's facility.
 *
 * <p>The other half of "funds consumed": the ledger says what the project spent, this says what the bank
 * put in. Drawn and undrawn are then sums over dated rows rather than a figure somebody remembers to update.
 * Voided with a reason, never edited, like every money row in this schema.
 */
@Entity
@Table(name = "facility_drawdowns")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class FacilityDrawdown {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 16) private String reference;
    @Column(name = "development_id", nullable = false) private Long developmentId;

    @Column(nullable = false, precision = 15, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";
    @Column(name = "drawn_on", nullable = false) @Builder.Default private LocalDate drawnOn = LocalDate.now();
    /** The bank's disbursement advice or the drawdown request number. */
    @Column(name = "reference_no", length = 64) private String referenceNo;
    @Column(columnDefinition = "TEXT") private String notes;
    @Column(name = "document_id") private Long documentId;

    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "institution_id") private Long institutionId;

    @Column(name = "voided_at") private OffsetDateTime voidedAt;
    @Column(name = "voided_by", length = 64) private String voidedBy;
    @Column(name = "void_reason", columnDefinition = "TEXT") private String voidReason;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    public boolean isVoided() { return status != null && status == AppConstant.STATUS_INACTIVE; }

    public void voidWith(String by, String reason) {
        this.status = AppConstant.STATUS_INACTIVE;
        this.statusFlag = AppConstant.FLAG_INACTIVE;
        this.voidedAt = OffsetDateTime.now();
        this.voidedBy = by;
        this.voidReason = reason;
        this.updatedBy = by;
    }
}
