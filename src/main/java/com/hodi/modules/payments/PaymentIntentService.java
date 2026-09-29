package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.infra.coop.CoopStkService;
import com.hodi.modules.bookings.BookingAccess;
import com.hodi.modules.bookings.BookingBalanceReader;
import com.hodi.modules.bookings.BookingDtos.BalanceRow;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * A payment somebody asks for: who may ask, for how much, and what they may read about it afterwards.
 *
 * <h2>Two kinds of asker</h2>
 *
 * <p>Platform staff who may record money, against any booking they can see. And the buyer, against their
 * own booking, because the money leaves their own phone on their own PIN and nothing is asserted on their
 * behalf. A seller's staff are neither: they read the receipts like everybody else.
 *
 * <h2>The ask is an acknowledgement</h2>
 *
 * <p>{@link #prompt} returns the moment Co-op has accepted the request. The prompt is then on the
 * customer's handset and the answer arrives by callback, by notification or by the status enquiry —
 * never by the return value. The screen polls {@link #find} until the state is terminal. Holding the
 * request open for the customer to decide used to occupy a server thread for two and a half minutes per
 * prompt, which at a few hundred prompts was the whole server.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentIntentService {

    private final CoopStkService stk;
    private final PaymentIntentRepository intents;
    private final UnitBookingRepository bookings;
    private final BookingAccess access;
    private final BookingBalanceReader balances;

    public record PromptRequest(String bookingId, BigDecimal amount, String phone, String narration) {}

    /**
     * An intent as the screen reads it.
     *
     * @param settled true once the state is terminal, so a poll knows when to stop
     */
    public record IntentResponse(
            String id,
            String reference,
            String bookingId,
            String state,
            boolean settled,
            BigDecimal amount,
            String currency,
            String phoneNo,
            String bankReference,
            String receipt,
            String paymentId,
            String processingReason,
            /** The handle an investigation starts from. Shown to the customer beside a plain sentence. */
            String traceId,
            Integer statusQueryAttempts,
            OffsetDateTime processedAt,
            OffsetDateTime createdAt) {}

    /*
     * What the customer is told, in place of the reason staff read.
     *
     * The staff sentence names the firewall, the endpoint, the bank's codes — none of it is the customer's
     * to act on, and all of it says more about our plumbing than anybody outside should hear. They are
     * told it did not go through and to try again, with the trace id, so a support call has one handle
     * that lands on every log line written about the request.
     */
    public static final String BUYER_FAILED = "The payment request did not go through. Please try again in a moment.";
    public static final String BUYER_WAITING = "Waiting for the bank to confirm.";

    /** Asks Co-op to prompt the phone, and returns as soon as they have accepted the request. */
    public IntentResponse prompt(PromptRequest request) {
        UserPrincipal caller = AuthContext.require();
        UnitBooking booking = readable(request.bookingId(), caller);
        assertMayAsk(caller, booking);
        com.hodi.modules.bookings.BookingTermsService.assertMayPay(booking, !caller.isBuyer());

        BigDecimal amount = request.amount();
        BalanceRow balance = balances.forBooking(booking.getId()).orElse(null);
        if (amount == null || amount.signum() <= 0) {
            /*
             * What is owed now, when the asker did not say. Whatever is overdue, else the balance — a buyer
             * pressing "Pay" is asked for what the schedule says, not made to type a figure they may get
             * wrong. Staff may still name any amount up to the balance.
             */
            amount = balance == null ? null
                    : balance.overdue() != null && balance.overdue().signum() > 0 ? balance.overdue()
                    : balance.balance();
            if (amount == null || amount.signum() <= 0) {
                throw new HodiException("Nothing is owed on this booking.", HttpStatus.CONFLICT);
            }
        } else if (balance != null && balance.balance() != null && amount.compareTo(balance.balance()) > 0) {
            // Never more than is owed: a prompt for more than the balance is a refund waiting to happen.
            throw new HodiException("That is more than the " + booking.getCurrency() + " "
                    + balance.balance().toPlainString() + " outstanding on this booking.", HttpStatus.BAD_REQUEST);
        }

        // A buyer pays from their own phone. Staff may name another — a relative paying on their behalf.
        String phone = caller.isBuyer() ? booking.getBuyerPhone() : request.phone();

        PaymentIntent intent = stk.push(booking.getId(), amount, phone, request.narration(),
                AuthContext.username());
        return toResponse(intent, caller);
    }

    /** One intent, for the poll. The buyer sees their own; staff see what they can read. */
    @Transactional(readOnly = true)
    public IntentResponse find(String hashId) {
        UserPrincipal caller = AuthContext.require();
        PaymentIntent intent = intents.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Payment", hashId));
        if (intent.getBookingId() == null) {
            if (!caller.isPlatformStaff()) throw new ResourceNotFoundException("Payment", hashId);
        } else {
            readable(HashIdUtil.encodeId(intent.getBookingId()), caller);
        }
        return toResponse(intent, caller);
    }

    /**
     * Every ask against a booking, newest first. The sales office's list, not the buyer's.
     *
     * <p>A buyer follows the one prompt they just sent through {@link #find}; a history of every attempt,
     * with what went wrong each time, is a working record for whoever chases payments and reads as a
     * list of failures to whoever owes the money.
     */
    @Transactional(readOnly = true)
    public List<IntentResponse> forBooking(String bookingHash) {
        UserPrincipal caller = AuthContext.require();
        if (caller.isBuyer()) {
            throw new HodiException("That list is kept by the sales office.", HttpStatus.FORBIDDEN);
        }
        UnitBooking booking = readable(bookingHash, caller);
        return intents.findByBookingIdOrderByCreatedAtDesc(booking.getId()).stream()
                .map(intent -> toResponse(intent, caller)).toList();
    }

    // ── who may ───────────────────────────────────────────────────────────────

    /** Staff with the money permission, or the buyer for their own booking. */
    public boolean mayAsk(UserPrincipal caller, UnitBooking booking) {
        if (caller.isPlatformStaff()) return holds(caller, "PAYMENTS_RECEIVE");
        return caller.isBuyer() && booking.getBuyerUserId() != null
                && Objects.equals(booking.getBuyerUserId(), caller.getUserId());
    }

    private void assertMayAsk(UserPrincipal caller, UnitBooking booking) {
        if (mayAsk(caller, booking)) return;
        throw new HodiException("You cannot ask for a payment against this booking.", HttpStatus.FORBIDDEN);
    }

    private UnitBooking readable(String hash, UserPrincipal caller) {
        UnitBooking booking = bookings.findById(HashIdUtil.decodeId(hash))
                .filter(b -> b.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Booking", hash));
        if (caller.isBuyer()) {
            if (!Objects.equals(booking.getBuyerUserId(), caller.getUserId())) {
                throw new ResourceNotFoundException("Booking", hash);
            }
            return booking;
        }
        if (!access.mayRead(access.propertyOf(booking), caller)) {
            throw new ResourceNotFoundException("Booking", hash);
        }
        return booking;
    }

    private static boolean holds(UserPrincipal caller, String permission) {
        return caller.getAuthorities().stream().anyMatch(a -> permission.equals(a.getAuthority()));
    }

    /** The intent as this caller may read it: staff get the reason in full, a buyer a plain sentence. */
    public static IntentResponse toResponse(PaymentIntent intent, UserPrincipal caller) {
        IntentResponse full = toResponse(intent);
        if (caller == null || !caller.isBuyer()) return full;
        String said = switch (intent.getState()) {
            case PaymentIntent.FAILED -> BUYER_FAILED;
            case PaymentIntent.SUCCEEDED -> full.processingReason();
            default -> BUYER_WAITING;
        };
        // No trace id either: it is the sales office's handle into our log, and a customer quoting it
        // to the bank or to us gains nothing they would not get from the payment reference.
        return new IntentResponse(full.id(), full.reference(), full.bookingId(), full.state(), full.settled(),
                full.amount(), full.currency(), full.phoneNo(), full.bankReference(), full.receipt(),
                full.paymentId(), said, null, full.statusQueryAttempts(), full.processedAt(),
                full.createdAt());
    }

    public static IntentResponse toResponse(PaymentIntent intent) {
        boolean settled = PaymentIntent.SUCCEEDED.equals(intent.getState())
                || PaymentIntent.FAILED.equals(intent.getState());
        return new IntentResponse(
                HashIdUtil.encodeId(intent.getId()),
                intent.getReference(),
                HashIdUtil.encodeId(intent.getBookingId()),
                intent.getState(),
                settled,
                intent.getAmount(),
                intent.getCurrency(),
                intent.getPhoneNo(),
                intent.getBankReference(),
                intent.getReceipt(),
                HashIdUtil.encodeId(intent.getPaymentId()),
                intent.getProcessingReason(),
                intent.getTraceId(),
                intent.getStatusQueryAttempts(),
                intent.getProcessedAt(),
                intent.getCreatedAt());
    }
}
