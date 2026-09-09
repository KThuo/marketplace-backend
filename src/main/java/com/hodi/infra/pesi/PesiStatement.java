package com.hodi.infra.pesi;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One payment Pesi has told us about, matched or not.
 *
 * <h2>Separate from a booking payment on purpose</h2>
 *
 * <p>A statement is "money arrived"; a payment is "this booking has been paid". Most of the time they are the
 * same event, and the entire value of this table is the times they are not — a mistyped reference, a walk-in
 * payer, a deposit against a unit cancelled last week.
 *
 * <h2>refNo is the idempotency key, and the index is the guarantee</h2>
 *
 * <p>Pesi retries anything it does not get a clean answer to within thirty seconds. A timeout on our side is
 * indistinguishable from a refusal on theirs, so the same money arrives twice — and the second arrival must be
 * recognised as the first. A "have I seen this?" check inside the service loses that race exactly the way the
 * booking check does; the unique index does not.
 */
@Entity
@Table(name = "pesi_statements")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PesiStatement {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ref_no", nullable = false, length = 64) private String refNo;
    @Column(name = "trace_id", length = 64) private String traceId;
    /** Ours, returned to Pesi as the RRN. Generated once and kept, so a retry echoes the same value. */
    @Column(name = "our_reference", nullable = false, unique = true, length = 16) private String ourReference;

    @Column(name = "trans_type", nullable = false, length = 48) private String transType;
    /** Which of our accounts it landed in, resolved from {@code accountIdentifier}. Null when we do not know it. */
    @Column(name = "payment_account_id") private Long paymentAccountId;
    @Column(name = "account_identifier", length = 64) private String accountIdentifier;

    /** What the payer typed. The reason this table exists is that this is often wrong. */
    @Column(length = 64) private String reference;

    @Column(nullable = false, precision = 15, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";
    @Column(name = "phone_no", length = 32) private String phoneNo;
    @Column(name = "customer_name", length = 160) private String customerName;
    @Column(name = "paid_at") private OffsetDateTime paidAt;

    /** The payload as it arrived. JSONB, so a reconciliation query can reach a field the columns do not. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_payload", columnDefinition = "jsonb") private String rawPayload;

    @Column(nullable = false, length = 16)
    @Builder.Default private String state = AppConstant.STATEMENT_UNMAPPED;

    @Column(name = "mapped_payment_id") private Long mappedPaymentId;
    @Column(name = "mapped_booking_id") private Long mappedBookingId;
    @Column(name = "mapped_at") private OffsetDateTime mappedAt;
    @Column(name = "mapped_by", length = 64) private String mappedBy;
    /** Why it could not be placed automatically, in words a person can act on. */
    @Column(name = "unmapped_reason", columnDefinition = "TEXT") private String unmappedReason;

    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "institution_id") private Long institutionId;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    public boolean isMapped() { return AppConstant.STATEMENT_MAPPED.equals(state); }
    public boolean isUnmapped() { return AppConstant.STATEMENT_UNMAPPED.equals(state); }
}
