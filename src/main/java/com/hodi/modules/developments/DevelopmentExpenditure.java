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
 * One cost event on a development.
 *
 * <h2>A ledger, not a figure</h2>
 *
 * <p>The phase used to carry a typed {@code spent_amount}, overwritten in place. That answers "how much" and
 * nothing else. A dated line answers when, on what, to whom, and against which piece of paper — and the
 * phase's figure becomes the sum of its lines, recounted by {@code DevelopmentInventoryService} so there is
 * one writer.
 *
 * <h2>Committed and spent are both lines</h2>
 *
 * <p>A signed contract is money spoken for before it leaves; a paid certificate is money gone. Both matter to
 * "how much of the budget is left", so both are lines with a {@link #kind}, and the phase carries both sums.
 *
 * <h2>Never edited, only voided</h2>
 *
 * <p>A cost report the bank has read must not quietly change. A wrong line is voided with a reason and stays,
 * exactly as a payment is; the views sum recorded lines only.
 */
@Entity
@Table(name = "development_expenditures")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DevelopmentExpenditure {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 16) private String reference;

    @Column(name = "development_id", nullable = false) private Long developmentId;
    /** Null for a project-level cost — land, finance costs, marketing rarely belong to one phase. */
    @Column(name = "phase_id") private Long phaseId;
    @Column(name = "category_id", nullable = false) private Long categoryId;

    /** {@code AppConstant.COST_COMMITTED} or {@code COST_SPENT}. */
    @Column(nullable = false, length = 16) private String kind;
    @Column(nullable = false, precision = 15, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";
    /** When the cost was incurred, not when it was keyed in. The spend curve is drawn from this. */
    @Column(name = "incurred_on", nullable = false) @Builder.Default private LocalDate incurredOn = LocalDate.now();

    @Column(length = 160) private String payee;
    /** The invoice, certificate or voucher number. */
    @Column(name = "reference_no", length = 64) private String referenceNo;
    @Column(columnDefinition = "TEXT") private String notes;
    /** The evidence in the vault, where one was attached. */
    @Column(name = "document_id") private Long documentId;

    /** Who was paid, as a beneficiary; {@code payee} carries their name either way. */
    @Column(name = "beneficiary_id") private Long beneficiaryId;
    /** The payment that wrote this line, when one did. One line per payment. */
    @Column(name = "disbursement_id") private Long disbursementId;
    /** MANUAL, typed in; DISBURSEMENT, written by a payment that succeeded. */
    @Column(name = "entry_kind", nullable = false, length = 16) @Builder.Default private String entryKind = "MANUAL";

    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "institution_id") private Long institutionId;

    @Column(name = "voided_at") private OffsetDateTime voidedAt;
    @Column(name = "voided_by", length = 64) private String voidedBy;
    @Column(name = "void_reason", columnDefinition = "TEXT") private String voidReason;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @Column(name = "search_text", insertable = false, updatable = false) private String searchText;

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
