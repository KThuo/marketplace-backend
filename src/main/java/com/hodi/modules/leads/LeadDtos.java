package com.hodi.modules.leads;

import com.hodi.common.dto.PagedDataRequest;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Request and response shapes for M4.
 *
 * <h2>One response shape per lead, for both sides</h2>
 *
 * <p>The listing and affordability modules each split their responses in two, because each had something
 * one audience must never see. A lead has no such field: the buyer's contact details are the point of the
 * row for the seller, and the seller's replies are the point of it for the buyer. Splitting here would be
 * ceremony — and two records that always agree drift the first time only one of them is edited.
 *
 * <p>What differs by side is <em>which rows</em> you get, and that is decided by the query.
 */
public final class LeadDtos {

    private LeadDtos() {}

    // ── enquiries ─────────────────────────────────────────────────────────────

    public record MessageResponse(
            String authorSide,
            String authorName,
            String body,
            OffsetDateTime at,
            /** MESSAGE for words; OFFER, COUNTER, ACCEPTED_COUNTER, REVIEW, ACCEPTED, DECLINED, WITHDRAWN for a move. */
            String kind,
            /** The figure a move carries. Null for words. */
            BigDecimal amount) {}

    /** A counter: a figure, and a word to go with it. */
    public record CounterRequest(
            @NotNull(message = "Say the figure") @Positive(message = "The figure must be above zero") BigDecimal amount,
            String note) {}

    public record EnquiryResponse(
            String reference,
            String propertyReference,
            String propertyTitle,
            String sellerName,
            String buyerName,
            String buyerEmail,
            String buyerPhone,
            String subject,
            String state,
            String assignedToName,
            int messageCount,
            OffsetDateTime lastMessageAt,
            String lastMessageSide,
            /** Whether the seller owes a reply — the inbox's default sort and the buyer's "waiting" label. */
            boolean awaitingSeller,
            String closeReason,
            OffsetDateTime createdAt,
            /** Null on a list; the whole conversation when one enquiry is opened. */
            List<MessageResponse> messages,
            /**
             * The most recent message, present on a list as well as on a single read.
             *
             * <p>{@link #messages} is deliberately null on a list — fifty threads is a very different
             * response from fifty rows — but a list with no message text at all was the whole of "display
             * the chat": a buyer saw the count rise and the badge flip to "They replied" without one word
             * of the reply, and the answer they had come back for was behind a button that did not say it
             * had arrived.
             */
            MessageResponse lastMessage,
            /** The agent who brought this buyer, when one is named. */
            String introducedByAgentRef,
            String introducedByAgentName) {}

    public record RaiseEnquiryRequest(
            @NotBlank(message = "Which listing is this about?") String propertyReference,
            @Size(max = 180, message = "That subject is too long") String subject,
            @NotBlank(message = "Write your question")
            @Size(max = 4000, message = "That message is too long") String message,
            /** Optional overrides — a buyer may want a seller to call a different number. */
            String contactPhone,
            String contactEmail) {}

    public record ReplyRequest(
            @NotBlank(message = "Write a reply")
            @Size(max = 4000, message = "That message is too long") String message) {}

    public record CloseRequest(String reason) {}

    public record AssignRequest(String userHashId) {}

    @Getter
    @Setter
    public static class EnquiryListRequest extends PagedDataRequest {
        private String state;
        /** {@code true} for the inbox's default: only what is waiting on this seller. */
        private Boolean awaiting;
        private String propertyReference;
    }

    // ── viewings ──────────────────────────────────────────────────────────────

    public record VisitResponse(
            String reference,
            String propertyReference,
            String propertyTitle,
            String sellerName,
            String buyerName,
            String buyerEmail,
            String buyerPhone,
            OffsetDateTime requestedAt,
            OffsetDateTime slotAt,
            Short partySize,
            String buyerNote,
            String state,
            String sellerNote,
            String outcomeNote,
            OffsetDateTime decidedAt,
            OffsetDateTime createdAt,
            /**
             * Everything said about this viewing, oldest first.
             *
             * <p>Carried on the list as well as on a single read, unlike {@link EnquiryResponse#messages}.
             * The enquiry inbox opens a thread pane; the viewings screen is a table, and the complaint it
             * answers is that the history was not there to open.
             */
            List<MessageResponse> messages) {}

    public record RequestVisitRequest(
            @NotBlank(message = "Which listing is this about?") String propertyReference,
            @NotNull(message = "When would you like to come?") OffsetDateTime requestedAt,
            Short partySize,
            @Size(max = 1000, message = "That note is too long") String note,
            String contactPhone) {}

    /**
     * @param slotAt when confirming or offering another time. Null on a decline.
     */
    public record DecideVisitRequest(
            @NotBlank(message = "Say what you are doing") String decision,
            OffsetDateTime slotAt,
            String note) {}

    public record CompleteVisitRequest(String outcomeNote) {}

    @Getter
    @Setter
    public static class VisitListRequest extends PagedDataRequest {
        private String state;
        private String propertyReference;
        /** {@code true} for the diary: confirmed and still ahead. */
        private Boolean upcoming;
    }

    // ── offers ────────────────────────────────────────────────────────────────

    public record OfferResponse(
            String reference,
            String propertyReference,
            String propertyTitle,
            String sellerName,
            BigDecimal askingPrice,
            String buyerName,
            String buyerEmail,
            String buyerPhone,
            BigDecimal offerAmount,
            String currency,
            String financing,
            String affordabilityReference,
            String productReference,
            BigDecimal depositAvailable,
            String buyerMessage,
            String state,
            String decisionNote,
            OffsetDateTime decidedAt,
            OffsetDateTime createdAt,
            /** Everything said about this offer, oldest first. See {@link VisitResponse#messages}. */
            List<MessageResponse> messages,
            /** The booking an accepted offer was converted into, or null while it is still only accepted. */
            String bookingId,
            String bookingReference,
            /** What the buyer first offered. Never changes. */
            BigDecimal originalAmount,
            /** The seller's counter the buyer has not yet answered, or null. */
            BigDecimal counterAmount,
            String counterBy,
            /** The figure the offer was accepted at, or null until it is. */
            BigDecimal agreedAmount,
            /** The agent who brought this buyer, when one is named — carried onto the booking. */
            String introducedByAgentRef,
            String introducedByAgentName) {}

    /**
     * Turning an accepted offer into a booking. Everything here is optional: the offer already names the
     * buyer, the home and the figure, and these are the terms the sales office adds on top.
     */
    public record BookFromOfferRequest(
            /** The price agreed. The offer's amount when left blank. */
            @DecimalMin("0") BigDecimal priceAgreed,
            /** What is due to hold the home. What the buyer said they had ready, when left blank. */
            @DecimalMin("0") BigDecimal depositDue,
            @Size(max = 24) String paymentPlan,
            @Min(1) @Max(365) Integer holdDays,
            String notes,
            @Valid List<com.hodi.modules.bookings.BookingDtos.InstalmentLine> instalments) {}

    public record SubmitOfferRequest(
            @NotBlank(message = "Which listing is this about?") String propertyReference,
            @NotNull(message = "How much are you offering?")
            @DecimalMin(value = "1", message = "An offer has to be more than nothing")
            BigDecimal offerAmount,
            String financing,
            String affordabilityReference,
            String productReference,
            BigDecimal depositAvailable,
            @Size(max = 2000, message = "That message is too long") String message,
            String contactPhone) {}

    public record DecideOfferRequest(
            @NotBlank(message = "Say what you are doing") String decision,
            String note) {}

    @Getter
    @Setter
    public static class OfferListRequest extends PagedDataRequest {
        private String state;
        private String propertyReference;
    }

    /** The counts the seller's shell and the buyer's account page draw. */
    public record LeadCounts(
            long enquiriesAwaiting,
            long visitsPending,
            long offersLive) {}
}
