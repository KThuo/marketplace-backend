package com.hodi.modules.payments;

import com.hodi.common.dto.PagedDataRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** What the statements screen sends and reads: the bank's side of the ledger, and a person's decisions about it. */
public final class StatementDtos {

    private StatementDtos() {}

    // ── requests ─────────────────────────────────────────────────────────────

    /**
     * One list for the ledger and the worklist: {@code state=UNMAPPED} <em>is</em> the queue.
     *
     * <p>Two endpoints would be two definitions of the same thing. {@code search} matches the bank's
     * reference, what the payer typed, their name and their phone — whatever the person is holding.
     */
    @Getter @Setter
    public static class StatementListRequest extends PagedDataRequest {
        /** MAPPED, UNMAPPED or IGNORED. Blank for everything. */
        private String state;
        /** The channel the money came through. */
        private String paymentTypeId;
        /** Credits that landed in an account collecting for this development. */
        private String developmentId;
    }

    /** Which booking the money belongs to. No amount: the amount is the bank's and cannot be changed. */
    public record AttachRequest(
            @NotBlank(message = "Choose the booking this money is for") String bookingId) {}

    public record SetAsideRequest(
            @NotBlank(message = "Say why this is not money for a booking")
            @Size(max = 500) String reason) {}

    // ── responses ────────────────────────────────────────────────────────────

    /**
     * A statement as the list reads it.
     *
     * @param quoted      what the payer typed, verbatim. Often wrong, which is why the row exists.
     * @param stateLabel  Used, Unused or Set aside
     * @param reason      why it is not placed, or why it was set aside, in words a person can act on
     * @param ownerKind   TENANT, INSTITUTION or PLATFORM: whose account it landed in
     */
    public record StatementResponse(
            String id,
            String refNo,
            String ourReference,
            String traceId,
            String state,
            String stateLabel,
            BigDecimal amount,
            String currency,
            OffsetDateTime paidAt,
            OffsetDateTime arrivedAt,
            String quoted,
            String payerName,
            String payerPhone,
            String transType,
            String paymentAccountId,
            String accountNo,
            String accountName,
            String paymentTypeId,
            String paymentTypeName,
            String category,
            String ownerKind,
            String paymentId,
            String paymentReference,
            String bookingId,
            String bookingReference,
            String unitLabel,
            OffsetDateTime mappedAt,
            String mappedBy,
            String reason) {}

    /** One statement with the payload as the bank sent it. The payload is platform staff's to read. */
    public record StatementDetail(StatementResponse statement, String rawPayload) {}

    /** What is waiting to be placed. "How long the oldest has waited" says whether anyone is working the queue. */
    public record Waiting(long count, BigDecimal total, OffsetDateTime oldestPaidAt) {}
}
