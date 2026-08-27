package com.hodi.modules.bookings;

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
 * scaffolding waiting for a gateway: a bank needs it in production permanently. It also decouples the three
 * charts this client most wants from somebody else's API key.
 *
 * <h2>Never edited, only reversed</h2>
 *
 * <p>A wrong payment is corrected by a second row with a negative amount and a reason, not by changing the
 * first. Editing money already reported to a buyer is how a balance becomes unexplainable — the buyer's
 * statement and ours stop matching and nothing records why. A reversal leaves both facts on the record, and
 * the balance view sums them, which is exactly the arithmetic wanted.
 */
@Entity
@Table(name = "booking_payments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BookingPayment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 16) private String reference;

    @Column(name = "booking_id", nullable = false) private Long bookingId;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "institution_id") private Long institutionId;

    @Column(name = "paid_on", nullable = false) @Builder.Default private LocalDate paidOn = LocalDate.now();
    @Column(nullable = false, precision = 15, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";

    @Column(nullable = false, length = 24) @Builder.Default private String source = AppConstant.PAY_MANUAL;
    @Column(nullable = false, length = 24)
    @Builder.Default private String method = AppConstant.PAY_BANK_TRANSFER;

    /** What the payer quoted — the unit's four-character code, when they got it right. */
    @Column(name = "quoted_reference", length = 16) private String quotedReference;
    /** The bank's or the gateway's own reference, for tracing a disputed payment to its source. */
    @Column(name = "external_reference", length = 64) private String externalReference;
    @Column(name = "payer_name", length = 160) private String payerName;
    @Column(name = "payer_phone", length = 32) private String payerPhone;

    @Column(name = "reversal_of_id") private Long reversalOfId;
    @Column(name = "reversal_reason", columnDefinition = "TEXT") private String reversalReason;

    @Column(columnDefinition = "TEXT") private String notes;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    public boolean isReversal() { return reversalOfId != null; }
}
