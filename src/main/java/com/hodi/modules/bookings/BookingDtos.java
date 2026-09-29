package com.hodi.modules.bookings;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What goes in and out of the booking endpoints.
 *
 * <p>Separate response records for the owner and for the buyer's own view, as everywhere else in this
 * codebase: a record with private fields blanked is one refactor away from leaking them, and a booking carries
 * an ID number and a phone.
 */
public final class BookingDtos {

    private BookingDtos() {}

    // ── requests ─────────────────────────────────────────────────────────────

    public record CreateBookingRequest(
            /** Required on the development route, where the body names the unit; absent on a property's own. */
            String unitHashId,
            @NotBlank(message = "The buyer's name is required")
            @Size(max = 160) String buyerName,
            /**
             * Mandatory, unlike a listing enquiry's.
             *
             * <p>This is somebody paying for a flat over three years: it is how they are reached, and it is
             * what an inbound payment is corroborated against when the four-character reference alone is not
             * enough to place the money safely.
             */
            @NotBlank(message = "A phone number is required — payments are matched against it")
            @Size(max = 32) String buyerPhone,
            @Size(max = 128) String buyerEmail,
            @Size(max = 32) String buyerIdNumber,
            @DecimalMin("0") BigDecimal priceAgreed,
            @DecimalMin("0") BigDecimal depositDue,
            @Size(max = 24) String paymentPlan,
            /** How long the reservation stands. Defaults to the configured window. */
            @Min(1) @Max(365) Integer holdDays,
            String notes,
            /** The schedule, where it is known at booking. Can be set later instead. */
            @Valid List<InstalmentLine> instalments,
            /** The agent who brought this buyer, by reference. Optional; can be named later while live. */
            @Size(max = 16) String introducedByAgentRef) {
        public CreateBookingRequest(String unitHashId, String buyerName, String buyerPhone, String buyerEmail,
                                    String buyerIdNumber, BigDecimal priceAgreed, BigDecimal depositDue,
                                    String paymentPlan, Integer holdDays, String notes,
                                    List<InstalmentLine> instalments) {
            this(unitHashId, buyerName, buyerPhone, buyerEmail, buyerIdNumber, priceAgreed, depositDue,
                    paymentPlan, holdDays, notes, instalments, null);
        }
    }

    /** Who brought the buyer. A blank reference clears it. */
    public record IntroducerRequest(@Size(max = 16) String agentRef) {}

    public record InstalmentLine(
            @Size(max = 120) String label,
            @NotNull(message = "Every instalment needs a date") LocalDate dueOn,
            @NotNull(message = "Every instalment needs an amount")
            @DecimalMin("0") BigDecimal amount) {}

    public record RescheduleRequest(
            @NotNull @Valid List<InstalmentLine> instalments,
            @NotBlank(message = "Say why the schedule is changing") String reason) {}

    public record CloseBookingRequest(
            @NotBlank(message = "Say why — it stays on the record") String reason) {}

    // ── responses ────────────────────────────────────────────────────────────

    /**
     * The bookings list — every home somebody is buying, and what they still owe.
     *
     * <p>Paginated and filtered on the server. A seller with four hundred units has four hundred
     * bookings, and a page that fetched them all to sort them in the browser would be slow in exactly
     * the deployment that matters.
     */
    @lombok.Getter
    @lombok.Setter
    public static class BookingListRequest extends com.hodi.common.dto.PagedDataRequest {
        /** Buyer, reference, unit or property title. */
        private String search;
        /** RESERVED, AGREED, COMPLETED, CANCELLED, LAPSED. */
        private String state;
        /** True for the bookings that still owe something — the working list. */
        private Boolean owing;
    }

    public record BookingResponse(
            String id,
            String reference,
            String developmentName,
            String unitLabel,
            /** The four characters the buyer quotes when paying. On the response because it goes on letters. */
            String payReference,
            String unitTypeName,
            String buyerName,
            String buyerPhone,
            String buyerEmail,
            String buyerIdNumber,
            String state,
            BigDecimal priceAgreed,
            BigDecimal depositDue,
            String currency,
            String paymentPlan,
            LocalDate bookedOn,
            OffsetDateTime expiresAt,
            boolean expired,
            OffsetDateTime agreedAt,
            OffsetDateTime completedAt,
            OffsetDateTime closedAt,
            String closeReason,
            String notes,
            /** Derived, never stored — see v_booking_balances. */
            BigDecimal scheduled,
            BigDecimal paid,
            BigDecimal balance,
            BigDecimal overdue,
            LocalDate nextDueOn,
            int instalmentCount,
            int paymentCount,
            OffsetDateTime createdAt,
            String createdBy,
            /** The home. A UNIT of a development or a HOUSE; the title reads right for either. */
            String propertyId,
            String propertyTitle,
            String listingKind,
            /** The agent who brought the buyer, when one is named. */
            String introducedByAgentRef,
            String introducedByAgentName,
            /** The latest completed valuation of the home, with what the bank lends against, or null. */
            com.hodi.modules.valuations.LendingValueService.ValuationFigures valuation,
            /** NONE, PRESENTED, ACCEPTED or DECLINED: where the buyer's agreement to the terms stands. */
            String termsState) {}

    /**
     * A sale made off the platform, or a booking completed by hand.
     *
     * <p>Marks a home sold by writing a completed booking for it, so the sale has a buyer, a price and a place
     * for money to land later — rather than a flag on the row that a report cannot add up.
     */
    public record MarkSoldRequest(
            @Size(max = 160) String buyerName,
            @Size(max = 32) String buyerPhone,
            @Size(max = 128) String buyerEmail,
            @DecimalMin("0") BigDecimal price,
            String note) {}

    public record InstalmentResponse(
            String id,
            short planNo,
            short sequenceNo,
            String label,
            LocalDate dueOn,
            BigDecimal amount,
            String currency) {}

    /**
     * A booking's money, worked out in one place.
     *
     * <p>Read from {@code v_booking_balances} rather than computed here, so the figure a screen shows and the
     * figure a report shows come from the same arithmetic. Two implementations of a balance is two balances.
     */
    public record BalanceRow(
            Long bookingId,
            String reference,
            String state,
            String currency,
            BigDecimal priceAgreed,
            BigDecimal depositDue,
            BigDecimal scheduled,
            BigDecimal paid,
            BigDecimal balance,
            BigDecimal overdue,
            LocalDate nextDueOn) {}
}
