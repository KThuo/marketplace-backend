package com.hodi.infra.coop;

import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.payments.PaymentIntent;
import com.hodi.modules.payments.PaymentIntentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Chasing the payments Co-op never called back about.
 *
 * <h2>Silence is not failure</h2>
 *
 * <p>The single rule this whole class exists to honour. A payment with no callback is a payment we do not
 * know about — the customer may well have entered their PIN. So nothing here fails anything. It asks the
 * bank, and only a definite answer from the bank moves an intent to a terminal state; everything else
 * leaves it in flight with a sentence saying what was tried.
 *
 * <h2>The cap, and why it is not optional</h2>
 *
 * <p>An intent the bank will never answer about — a reference it has lost, a channel switched off mid-flight
 * — is otherwise re-queried every thirty seconds for as long as the row exists. So automatic queries are
 * counted and capped; past the cap the sweep stops touching it and the stamped reason says it is waiting for
 * a person. An operator's own query is neither counted nor capped, because the cap exists to stop a machine
 * looping rather than to stop somebody working.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoopIntentSweep {

    /** One number for the one sweep. Two instances asking Co-op about the same prompt is twice the load and a race. */
    private static final long LOCK_KEY = 7_260_920_001L;

    private final PaymentIntentRepository intents;
    private final CoopStkService stk;
    private final ConfigurationService configs;
    private final org.springframework.transaction.support.TransactionTemplate newTransaction;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Scheduled(fixedRate = 30_000)
    public void chaseUnanswered() {
        /*
         * One instance at a time. A transaction-scoped advisory lock, so it is released however the pass
         * ends and needs no table of its own; an instance that finds it held skips this pass rather than
         * waiting, because the other instance is doing exactly the same work.
         */
        newTransaction.executeWithoutResult(status -> {
            Boolean acquired = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(acquired)) {
                log.debug("Another instance is chasing unanswered payments; skipping this pass");
                return;
            }
            chase();
        });
    }

    /** The pass itself. Package-private so a test can run it without the scheduler or the lock. */
    void chase() {
        /*
         * Everything in flight that was asked at all. The index narrows to PROCESSING; each intent's own
         * deadline decides in Java, because the timeout is a property of the payment — copied onto it when
         * it was made — rather than of the sweep.
         */
        List<PaymentIntent> candidates = intents.findInFlightBefore(OffsetDateTime.now());
        if (candidates.isEmpty()) return;

        int cap = configs.getInt(ConfigKey.COOP_STATUS_QUERY_MAX_ATTEMPTS);

        for (PaymentIntent intent : candidates) {
            try {
                if (!intent.pastItsDeadline()) continue;

                int used = intent.getStatusQueryAttempts() == null ? 0 : intent.getStatusQueryAttempts();
                if (used >= cap) {
                    // Already handed over. The reason on the row said so when the cap was reached; saying
                    // it again every thirty seconds would fill the log with one payment.
                    log.debug("Payment {} has used its {} automatic queries; waiting for a person",
                            intent.getReference(), cap);
                    continue;
                }

                String state = stk.query(intent.getId(), true, null);
                log.info("Chased payment {} after no callback — now {} (query {} of {})",
                        intent.getReference(), state, used + 1, cap);
            } catch (Exception e) {
                /*
                 * One payment's problem must not stop the sweep reaching the others, and it must never
                 * fail that payment either. The row keeps whatever reason it had; the next sweep tries
                 * again unless the cap is reached.
                 */
                log.error("Could not chase payment {}: {}", intent.getReference(), e.getMessage());
            }
        }
    }
}
