package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One request for money: what we asked for, who we asked, and what came back.
 *
 * <h2>Written before the call, not after it</h2>
 *
 * <p>A request that dies on the socket has often still reached the bank — Co-op may already have pushed the
 * prompt to the customer's handset. So the row exists before anything leaves this process, and the reference
 * on it is ours. Recording only what the reply told us would lose precisely the payments that need chasing.
 *
 * <h2>Terminal states are only ever reached on a definite answer</h2>
 *
 * <p>{@code SUCCEEDED} when the bank says so; {@code FAILED} when the bank says so. Silence is not failure,
 * a timeout is not failure, and an error from the status query is not failure — all of those leave the
 * payment {@code PROCESSING} with a sentence saying why, for the sweep to try again or a person to settle.
 * A payment marked failed whose money did arrive is a customer who has paid twice.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "payment_intents")
public class PaymentIntent {

    /** Written, not yet sent. Nothing has left this process. */
    public static final String PENDING = "PENDING";
    /** The bank accepted it and owes us an answer. Everything in flight is here. */
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Ours, and the handle everything else uses. Quoted to the bank as its message reference. */
    @Column(nullable = false, unique = true, length = 16) private String reference;

    @Column(name = "payment_type_id", nullable = false) private Long paymentTypeId;
    @Column(name = "payment_account_id") private Long paymentAccountId;

    @Column(name = "booking_id") private Long bookingId;
    /** Copied rather than joined: a payment must be findable by listing even if the booking is cancelled. */
    @Column(name = "property_id") private Long propertyId;
    @Column(name = "buyer_user_id") private Long buyerUserId;

    @Column(nullable = false, precision = 15, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";
    @Column(name = "phone_no", length = 32) private String phoneNo;
    @Column(length = 160) private String narration;

    @Column(nullable = false, length = 16) @Builder.Default private String state = PENDING;

    /** What Co-op calls the conversation, and then the receipt they issue on success. */
    @Column(name = "bank_reference", length = 64) private String bankReference;
    @Column(length = 64) private String receipt;

    @Column(name = "processed_at") private OffsetDateTime processedAt;

    /**
     * Copied from the setting when the intent is made, never read live.
     *
     * <p>Changing the platform's timeout must not move the deadline of something already in flight —
     * a payment's own deadline is a fact about that payment.
     */
    @Column(name = "callback_timeout_seconds", nullable = false) @Builder.Default
    private Integer callbackTimeoutSeconds = 60;

    @Column(name = "status_query_attempts", nullable = false) @Builder.Default
    private Integer statusQueryAttempts = 0;

    /** The sentence a person reads. An intent nobody can explain is worse than one that failed. */
    @Column(name = "processing_reason", columnDefinition = "TEXT") private String processingReason;

    @Column(name = "statement_id") private Long statementId;
    @Column(name = "payment_id") private Long paymentId;

    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "institution_id") private Long institutionId;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 16) @Builder.Default
    private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    /** Still owed an answer. */
    public boolean inFlight() {
        return PROCESSING.equals(state);
    }

    /** Whether the bank has had long enough that silence is now worth asking about. */
    public boolean pastItsDeadline() {
        if (processedAt == null) return false;
        return OffsetDateTime.now().isAfter(
                processedAt.plusSeconds(callbackTimeoutSeconds == null ? 60 : callbackTimeoutSeconds));
    }
}
