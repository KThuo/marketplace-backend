package com.hodi.modules.disbursements;

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
 * Money the bank sends out. Not a payment: it debits, and it never touches a booking or a statement.
 *
 * <h2>Validated before it exists</h2>
 *
 * <p>{@link #validatedName} is NOT NULL. A row is written only after Co-op has resolved the destination to
 * the name it is held in, because that name is what the checker approves — "KES 450,000 to JANE W. MWANGI"
 * can be checked against an invoice; an account number cannot.
 *
 * <h2>States</h2>
 *
 * <pre>
 *   AWAITING_APPROVAL → APPROVED → SENDING → SENT → SUCCEEDED | FAILED
 *                     → REFUSED
 * </pre>
 *
 * <p>{@code SENDING} is the claim taken immediately before the outbound call. A process that dies mid-call
 * leaves a row that says "we may have sent this", which is chased with a status enquiry and never by sending
 * again: re-sending a transfer that may have gone out is how somebody is paid twice.
 */
@Entity
@Table(name = "disbursements")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Disbursement {

    public static final String AWAITING_APPROVAL = "AWAITING_APPROVAL";
    public static final String APPROVED = "APPROVED";
    public static final String SENDING = "SENDING";
    public static final String SENT = "SENT";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";
    public static final String REFUSED = "REFUSED";

    public static final String PAYEE_SELLER = "SELLER_ORGANISATION";
    public static final String PAYEE_OTHER = "OTHER";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Ours, "DB…", generated before anything is asked of the bank and quoted as the MessageReference. */
    @Column(nullable = false, unique = true, length = 16) private String reference;

    @Column(name = "payee_kind", nullable = false, length = 24) private String payeeKind;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "payee_name", nullable = false, length = 160) private String payeeName;
    @Column(name = "bank_code", nullable = false, length = 8) private String bankCode;
    @Column(name = "account_no", nullable = false, length = 32) private String accountNo;
    @Column(name = "validated_name", nullable = false, length = 160) private String validatedName;
    @Column(name = "validated_at", nullable = false) private OffsetDateTime validatedAt;

    @Column(nullable = false, precision = 15, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";
    @Column(nullable = false, length = 240) private String purpose;
    @Column(length = 160) private String narration;
    @Column(name = "source_account_id", nullable = false) private Long sourceAccountId;

    @Column(nullable = false, length = 24) @Builder.Default private String state = AWAITING_APPROVAL;
    @Column(name = "bank_reference", length = 64) private String bankReference;
    @Column(name = "response_code", length = 16) private String responseCode;
    @Column(name = "response_description", length = 400) private String responseDescription;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response", columnDefinition = "jsonb") private String rawResponse;
    @Column(name = "sent_at") private OffsetDateTime sentAt;
    @Column(name = "settled_at") private OffsetDateTime settledAt;
    @Column(name = "callback_timeout_seconds", nullable = false) @Builder.Default
    private Integer callbackTimeoutSeconds = 300;
    @Column(name = "status_query_attempts", nullable = false) @Builder.Default
    private Integer statusQueryAttempts = 0;
    @Column(name = "processing_reason", columnDefinition = "TEXT") private String processingReason;

    @Column(name = "made_by", nullable = false, length = 64) private String madeBy;
    @Column(name = "checked_by", length = 64) private String checkedBy;
    @Column(name = "checked_at") private OffsetDateTime checkedAt;
    @Column(name = "decision_reason", columnDefinition = "TEXT") private String decisionReason;

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

    public boolean isTerminal() {
        return SUCCEEDED.equals(state) || FAILED.equals(state) || REFUSED.equals(state);
    }

    /** Out with the bank and unanswered: sent, or claimed for sending by a process that may have died. */
    public boolean isOut() {
        return SENT.equals(state) || SENDING.equals(state);
    }

    public boolean pastItsDeadline() {
        if (sentAt == null) return false;
        return sentAt.plusSeconds(callbackTimeoutSeconds == null ? 300 : callbackTimeoutSeconds)
                .isBefore(OffsetDateTime.now());
    }
}
