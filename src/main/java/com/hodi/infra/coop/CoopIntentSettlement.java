package com.hodi.infra.coop;

import com.hodi.common.AppConstant;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.payments.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * Every state change an intent can undergo, and the only place any of them happen.
 *
 * <h2>Why this is separate from the service that calls the bank</h2>
 *
 * <p>Two reasons, both structural. {@link #open} must commit <em>before</em> the outbound call, which means
 * its own transaction — and a {@code REQUIRES_NEW} on a method of the same bean is a self-invocation Spring
 * does not proxy, so it would silently do nothing. And putting the transitions in one class is what lets
 * the idempotency below be a property of the system rather than a habit of each caller.
 *
 * <h2>Nothing here happens twice</h2>
 *
 * <p>The same money is reported more than once as a matter of routine: Co-op retries a callback it thinks
 * we did not acknowledge, a notification arrives for a payment the status query already settled, two
 * deliveries race. Every transition below is therefore written to be safe to repeat —
 *
 * <ul>
 *   <li>{@link #succeeded} on an intent already succeeded returns it untouched. It does not write a second
 *       payment, which would double a buyer's balance and take a person to unpick.</li>
 *   <li>A terminal intent is never moved back. A late callback for something already settled is recorded
 *       as an observation, not as a change.</li>
 *   <li>{@link #failed} never overrides a success. If the bank said it worked and then said it did not,
 *       the money is what it is, and a person decides — the machine does not quietly reverse a credit.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoopIntentSettlement {

    private final PaymentIntentRepository intents;
    private final PaymentAccountRepository accounts;
    private final UnitBookingRepository bookings;
    private final PaymentService payments;

    /**
     * Writes the intent and commits it, before anything is asked of the bank.
     *
     * <p>{@code REQUIRES_NEW} so this survives whatever happens to the caller's transaction. A prompt
     * that reached the customer while our own transaction rolled back would otherwise leave them debited
     * against a row that never existed.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentIntent open(PaymentIntent intent) {
        PaymentIntent saved = intents.saveAndFlush(intent);
        log.info("Payment intent {} opened for booking {} — {} {}", saved.getReference(),
                saved.getBookingId(), saved.getCurrency(), saved.getAmount());
        return saved;
    }

    /** Accepted by the bank and awaiting an answer. The deadline starts here, not at creation. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentIntent inFlight(Long intentId, String bankReference, String reason) {
        PaymentIntent intent = intents.findById(intentId).orElseThrow();
        if (terminal(intent)) return intent;

        intent.setState(PaymentIntent.PROCESSING);
        if (bankReference != null) intent.setBankReference(bankReference);
        // Set once. A second call — a retry, a re-push — must not restart the clock on a payment that
        // has already been waiting, or the sweep would never reach it.
        if (intent.getProcessedAt() == null) intent.setProcessedAt(OffsetDateTime.now());
        intent.setProcessingReason(reason);
        intent.setUpdatedBy(AppConstant.USERNAME_SYSTEM);
        return intents.save(intent);
    }

    /** Still in flight after a query that could not settle it. Stamps why, and counts the attempt. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentIntent stillWaiting(Long intentId, int attempts, String reason) {
        PaymentIntent intent = intents.findById(intentId).orElseThrow();
        if (terminal(intent)) return intent;

        intent.setStatusQueryAttempts(attempts);
        intent.setProcessingReason(reason);
        intent.setUpdatedBy(AppConstant.USERNAME_SYSTEM);
        return intents.save(intent);
    }

    /**
     * Co-op said it did not go through.
     *
     * <p>Only ever from a definite answer. Silence, a timeout and an unreadable code all leave the payment
     * in flight instead — see {@link CoopAnswer}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentIntent failed(Long intentId, String bankReference, String reason) {
        PaymentIntent intent = intents.findById(intentId).orElseThrow();
        if (PaymentIntent.SUCCEEDED.equals(intent.getState())) {
            // The bank has contradicted itself. The money is what it is; a person decides, and the
            // credit is not quietly reversed by a background job.
            log.warn("Co-op reported {} as failed after it had succeeded: {}", intent.getReference(), reason);
            return intent;
        }
        intent.setState(PaymentIntent.FAILED);
        if (bankReference != null) intent.setBankReference(bankReference);
        intent.setProcessingReason(reason);
        intent.setUpdatedBy(AppConstant.USERNAME_SYSTEM);
        log.info("Payment intent {} failed: {}", intent.getReference(), reason);
        return intents.save(intent);
    }

    /**
     * Co-op confirmed the money, so the booking is credited — once.
     *
     * <p>The guard is the whole method. A callback and a status query routinely report the same payment,
     * and Co-op retries callbacks it believes went unacknowledged; without this, one payment becomes two
     * on a buyer's statement.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentIntent succeeded(Long intentId, String bankReference, String receipt, String said,
                                   String by) {
        PaymentIntent intent = intents.findById(intentId).orElseThrow();

        if (PaymentIntent.SUCCEEDED.equals(intent.getState()) && intent.getPaymentId() != null) {
            log.info("Payment intent {} already credited as payment {}; ignoring the repeat",
                    intent.getReference(), intent.getPaymentId());
            return intent;
        }

        intent.setState(PaymentIntent.SUCCEEDED);
        if (bankReference != null) intent.setBankReference(bankReference);
        if (receipt != null) intent.setReceipt(receipt);
        intent.setProcessingReason("Confirmed by Co-op: " + said);
        intent.setUpdatedBy(by == null ? AppConstant.USERNAME_SYSTEM : by);

        // The credit, if this intent is against a booking and has not already produced one.
        if (intent.getPaymentId() == null && intent.getBookingId() != null) {
            UnitBooking booking = bookings.findById(intent.getBookingId()).orElse(null);
            PaymentAccount account = intent.getPaymentAccountId() == null ? null
                    : accounts.findById(intent.getPaymentAccountId()).orElse(null);
            if (booking == null) {
                intent.setProcessingReason("Confirmed by Co-op: " + said
                        + " — but the booking it was for no longer exists, so nothing was credited.");
            } else {
                Payment payment = payments.recordFromIntent(booking, intent, account);
                intent.setPaymentId(payment.getId());
                log.info("Payment intent {} credited booking {} as {}", intent.getReference(),
                        booking.getReference(), payment.getReference());
            }
        }
        return intents.save(intent);
    }

    /**
     * Links a notification to the intent it answers, without crediting twice.
     *
     * <p>Called when a statement arrives quoting an intent's reference. If the status query already
     * settled and credited it, this records which notification corresponded to it and stops. Both paths
     * report the same money, and only one of them may move a balance.
     */
    @Transactional
    public PaymentIntent attachStatement(PaymentIntent intent, Long statementId, Long paymentId) {
        intent.setStatementId(statementId);
        if (intent.getPaymentId() == null && paymentId != null) intent.setPaymentId(paymentId);
        if (!PaymentIntent.SUCCEEDED.equals(intent.getState())) {
            intent.setState(PaymentIntent.SUCCEEDED);
            intent.setProcessingReason("Confirmed by a Co-op notification.");
        }
        intent.setUpdatedBy(AppConstant.USERNAME_SYSTEM);
        return intents.save(intent);
    }

    private static boolean terminal(PaymentIntent intent) {
        return PaymentIntent.SUCCEEDED.equals(intent.getState())
                || PaymentIntent.FAILED.equals(intent.getState());
    }
}
