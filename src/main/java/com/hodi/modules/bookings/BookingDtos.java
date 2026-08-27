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
            @NotBlank(message = "Say which unit") String unitHashId,
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
            @Valid List<InstalmentLine> instalments) {}

    public record InstalmentLine(
            @Size(max = 120) String label,
            @NotNull(message = "Every instalment needs a date") LocalDate dueOn,
            @NotNull(message = "Every instalment needs an amount")
            @DecimalMin("0") BigDecimal amount) {}

    public record RescheduleRequest(
            @NotNull @Valid List<InstalmentLine> instalments,
            @NotBlank(message = "Say why the schedule is changing") String reason) {}

    public record RecordPaymentRequest(
            @NotNull(message = "How much was received?") @DecimalMin("0.01") BigDecimal amount,
            LocalDate paidOn,
            @Size(max = 24) String method,
            /** What the payer quoted, if they quoted anything. */
            @Size(max = 16) String quotedReference,
            @Size(max = 64) String externalReference,
            @Size(max = 160) String payerName,
            @Size(max = 32) String payerPhone,
            String notes) {}

    public record ReversePaymentRequest(
            @NotBlank(message = "Say why this payment is being reversed") String reason) {}

    public record CloseBookingRequest(
            @NotBlank(message = "Say why — it stays on the record") String reason) {}

    // ── responses ────────────────────────────────────────────────────────────

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
            String createdBy) {}

    public record InstalmentResponse(
            String id,
            short planNo,
            short sequenceNo,
            String label,
            LocalDate dueOn,
            BigDecimal amount,
            String currency) {}

    public record PaymentResponse(
            String id,
            String reference,
            LocalDate paidOn,
            BigDecimal amount,
            String currency,
            String source,
            String method,
            String quotedReference,
            String externalReference,
            String payerName,
            String payerPhone,
            boolean reversal,
            String reversalReason,
            /** Whether a later row has already reversed this one, so the button can be hidden. */
            boolean reversed,
            String notes,
            OffsetDateTime createdAt,
            String createdBy) {}

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
