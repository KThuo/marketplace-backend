package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * Money that actually arrived against a booking.
 *
 * <h2>Recorded by hand first, and forever</h2>
 *
 * <p>{@code MANUAL} is the cheque, the RTGS transfer and the cash a bank reconciles, and it is not
 * scaffolding waiting for a gateway: a bank needs it in production permanently. {@code GATEWAY} rows are
 * written by an inbound Pesi notification, through the same service and the same method.
 *
 * <h2>Never edited, only voided</h2>
 *
 * <p>A receipt is a record of what a buyer was told had been received. Editing one means their copy and the
 * copy on file disagree about how much they paid. A wrong payment is voided with a reason, and the row stays
 * — {@code status} 4 with who, when and why beside it — which is a fresh fact rather than a correction that
 * hides one. The balance view sums received rows only.
 *
 * <h2>Names are copied in and never touched again</h2>
 *
 * <p>The development, the unit, the buyer and the channel's name are all stamped at receipt. A receipt printed
 * today must still read the same after a unit is relabelled or the catalogue row is renamed, because it
 * records what was said to the payer.
 */
@Entity
@Table(name = "payments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Payment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The receipt number. Unique platform-wide: a buyer reading it down the phone does not know whose numbering they are in. */
    @Column(nullable = false, unique = true, length = 16) private String reference;

    @Column(name = "booking_id", nullable = false) private Long bookingId;
    /** Null for a house. Cached from the booking, like the names beside it. */
    @Column(name = "development_id") private Long developmentId;
    @Column(name = "property_id") private Long propertyId;
    /** Cached from the booking so the list can scope without a join. */
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "institution_id") private Long institutionId;

    @Column(name = "development_name", length = 255) private String developmentName;
    @Column(name = "unit_label", length = 32) private String unitLabel;
    @Column(name = "buyer_name", length = 160) private String buyerName;
    @Column(name = "buyer_phone", length = 32) private String buyerPhone;

    /** When the money arrived, not when it was keyed in. Cash taken on Friday and entered on Monday is a Friday payment. */
    @Column(name = "paid_on", nullable = false) @Builder.Default private LocalDate paidOn = LocalDate.now();
    @Column(nullable = false, precision = 15, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";

    @Column(nullable = false, length = 24) @Builder.Default private String source = AppConstant.PAY_MANUAL;
    /** The coarse kind of money — what the system did with it. Not a payment type. */
    @Column(nullable = false, length = 24)
    @Builder.Default private String method = AppConstant.PAY_BANK_TRANSFER;

    /**
     * The configured account this came through, where it came through one — beside {@link #method}, not
     * instead of it. The money did not arrive by "MOBILE_MONEY", it arrived by Lipa na KCB, and that is what
     * a receipt has to say. The name is copied so a rename of the catalogue row cannot retitle old receipts.
     */
    @Column(name = "payment_type_id") private Long paymentTypeId;
    @Column(name = "payment_type_name", length = 120) private String paymentTypeName;

    /** What the payer quoted — the unit's four-character code, when they got it right. */
    @Column(name = "quoted_reference", length = 16) private String quotedReference;
    /** The bank's or the gateway's own reference, for tracing a disputed payment to its source. */
    @Column(name = "external_reference", length = 64) private String externalReference;
    @Column(name = "payer_name", length = 160) private String payerName;
    @Column(name = "payer_phone", length = 32) private String payerPhone;

    /**
     * What the booking owed immediately before and after this landed.
     *
     * <p>Both stored, neither derived. {@code after + amount} is wrong the moment the payment is voided and
     * the balance recomputed, and a receipt has to show the subtraction it performed.
     */
    @Column(name = "balance_before", precision = 15, scale = 2) private BigDecimal balanceBefore;
    @Column(name = "balance_after", precision = 15, scale = 2) private BigDecimal balanceAfter;

    /** The bank credit behind it, where there was one. Null for anything keyed in by hand. */
    @Column(name = "statement_id") private Long statementId;

    @Column(columnDefinition = "TEXT") private String notes;

    @Column(name = "voided_at") private OffsetDateTime voidedAt;
    @Column(name = "voided_by", length = 64) private String voidedBy;
    @Column(name = "void_reason", columnDefinition = "TEXT") private String voidReason;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.PAYMENT_RECEIVED;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @Column(name = "search_text", insertable = false, updatable = false) private String searchText;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    public boolean isVoided() {
        return status != null && status == AppConstant.PAYMENT_VOIDED;
    }

    public boolean isReceived() {
        return status != null && status == AppConstant.PAYMENT_RECEIVED;
    }

    /**
     * What to call how this money arrived.
     *
     * <p>The channel's own name where it came through a configured account, and the method's label otherwise
     * — cash and cheque have no account behind them, and "Cash" is what they are.
     */
    public String arrivedAs() {
        return paymentTypeName == null || paymentTypeName.isBlank()
                ? PaymentMethods.label(method)
                : paymentTypeName;
    }

    /** Marks the payment voided. The row stays; the balance view stops counting it. */
    public void voidWith(String by, String reason) {
        this.status = AppConstant.PAYMENT_VOIDED;
        this.statusFlag = AppConstant.FLAG_INACTIVE;
        this.voidedAt = OffsetDateTime.now();
        this.voidedBy = by;
        this.voidReason = reason;
        this.updatedBy = by;
    }
}
