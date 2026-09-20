package com.hodi.modules.payments;

import com.hodi.common.dto.PagedDataRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

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

    /**
     * A slip, confirmed and applied in one step. A reference and a booking; never an amount.
     *
     * <p>Its own request rather than the ordinary receive form's, so there is no path on which somebody
     * sends a reference <em>and</em> a figure the bank did not confirm.
     */
    public record TakeSlipRequest(
            @NotBlank(message = "Choose the booking this money is for") String bookingId,
            @NotBlank(message = "Enter the reference from the bank")
            @Size(min = 6, max = 64, message = "A bank reference is between six and sixty-four characters")
            String reference) {}

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

    // ── uploads ──────────────────────────────────────────────────────────────

    /** One column of the statement CSV: what to call it, whether it must be there, and an example. */
    public record UploadColumn(String key, boolean required, String example, String notes) {}

    /** What the upload wants, so the form describes the file before anything is downloaded. */
    public record UploadSpec(List<UploadColumn> columns, int maxRows, String notes) {}

    /**
     * One line of the file, and what became of it: PLACED on a booking, QUEUED for a person, SKIPPED as
     * already known, or FAILED with what was wrong on that line.
     */
    public record UploadLine(int line, String refNo, String outcome, String message, String statementId,
                             String bookingReference) {

        static UploadLine skipped(int line, String refNo, String message) {
            return new UploadLine(line, refNo, "SKIPPED", message, null, null);
        }

        static UploadLine failed(int line, String refNo, String message) {
            return new UploadLine(line, refNo, "FAILED", message, null, null);
        }
    }

    public record UploadOutcome(int rows, int placed, int queued, int skipped, int failed,
                                List<UploadLine> lines) {}

    /**
     * What a bank reference turned out to be.
     *
     * <p>Valid means an unused credit that collects for this booking was found, and the figures below are
     * the bank's. Invalid carries a message and nothing else: the row it may be about is not this
     * caller's to see.
     */
    public record SlipResult(
            boolean valid,
            String message,
            String statementId,
            String refNo,
            BigDecimal amount,
            String currency,
            OffsetDateTime paidAt,
            OffsetDateTime arrivedAt,
            String payerName,
            String payerPhone,
            String quoted,
            String paymentTypeId,
            String paymentTypeName,
            String category,
            String accountNo,
            String accountName) {

        public static SlipResult no(String message) {
            return new SlipResult(false, message, null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null);
        }
    }
}
