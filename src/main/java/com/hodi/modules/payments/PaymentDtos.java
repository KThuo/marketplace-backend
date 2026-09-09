package com.hodi.modules.payments;

import com.hodi.common.dto.PagedDataRequest;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Request and response shapes for {@code /api/v1/payments}. */
public final class PaymentDtos {

    private PaymentDtos() {}

    // ── requests ─────────────────────────────────────────────────────────────

    /**
     * Record money received.
     *
     * <p>The booking is required; the account is not. Cash across a counter came through no account, and
     * where the money did come through one — a paybill credit keyed in from a bank statement — naming it is
     * what lets the receipt say "Lipa na KCB" rather than "mobile money". Choosing an account fixes the
     * method from the channel; otherwise {@code method} is taken as given.
     */
    public record ReceiveRequest(
            @NotBlank(message = "Say which booking the money is for") String bookingId,
            @NotNull(message = "How much was received?")
            @DecimalMin(value = "0.01", message = "The amount must be above zero") BigDecimal amount,
            /** When the money arrived. Defaults to today. */
            LocalDate paidOn,
            @Size(max = 24) String method,
            /** Which configured account it landed in, where one applies. */
            String paymentAccountId,
            /** What the payer quoted, if they quoted anything. */
            @Size(max = 16) String quotedReference,
            @Size(max = 64) String externalReference,
            @Size(max = 160) String payerName,
            @Size(max = 32) String payerPhone,
            String notes) {}

    public record VoidRequest(
            @NotBlank(message = "Say why this payment is being voided — it stays on the record") String reason) {}

    @Getter @Setter
    public static class PaymentListRequest extends PagedDataRequest {
        private String developmentId;
        private String bookingId;
        /** The channel, which is what a "payment type" filter means. Not {@code method}. */
        private String paymentTypeId;
        private String method;
        private String source;
    }

    // ── responses ────────────────────────────────────────────────────────────

    /**
     * A payment as the list and the receipt read it.
     *
     * @param arrivedAs   what a person calls how the money arrived — the channel's name, or the method's label
     *                    where there was no channel
     * @param ownerKind   TENANT, INSTITUTION or PLATFORM: whose money it is, from the development
     */
    public record PaymentResponse(
            String id,
            String reference,
            Integer status,
            String statusLabel,
            String bookingId,
            String bookingReference,
            String developmentId,
            String developmentName,
            String unitId,
            String unitLabel,
            String buyerName,
            String buyerPhone,
            String ownerKind,
            String ownerName,
            LocalDate paidOn,
            BigDecimal amount,
            String currency,
            String source,
            String method,
            String methodLabel,
            String arrivedAs,
            String paymentTypeId,
            String paymentTypeName,
            String quotedReference,
            String externalReference,
            String payerName,
            String payerPhone,
            BigDecimal balanceBefore,
            BigDecimal balanceAfter,
            String notes,
            OffsetDateTime voidedAt,
            String voidedBy,
            String voidReason,
            OffsetDateTime createdAt,
            String createdBy) {}

    /** The receipt: the payment, and the booking it was applied to as it stands now. */
    public record PaymentDetail(PaymentResponse payment, BookingBalance booking) {}

    /**
     * What a booking owes and what has arrived, for the receive form and the receipt.
     *
     * <p>Shown before anything is submitted. Keying money against the wrong booking is the mistake this
     * module can actually make, and a panel naming the home, the buyer and the balance is what catches it.
     * Every figure comes from {@code v_booking_balances}.
     */
    public record BookingBalance(
            String bookingId,
            String reference,
            String state,
            String buyerName,
            String buyerPhone,
            String developmentId,
            String developmentName,
            String unitId,
            String unitLabel,
            /** The four characters the buyer quotes when paying. */
            String payReference,
            String currency,
            BigDecimal priceAgreed,
            BigDecimal scheduled,
            BigDecimal paid,
            BigDecimal balance,
            BigDecimal overdue,
            LocalDate nextDueOn,
            List<ScheduleLine> schedule) {}

    public record ScheduleLine(String label, LocalDate dueOn, BigDecimal amount, boolean past) {}

    /** A live booking the caller may record money against — the receive form's picker. */
    public record BookingOption(
            String id,
            String reference,
            String state,
            String buyerName,
            String buyerPhone,
            String developmentName,
            String unitLabel,
            String currency,
            BigDecimal balance) {}

    /** The methods a caller may choose from, so the form does not hardcode the list. */
    public record MethodOption(String value, String label) {}
}
