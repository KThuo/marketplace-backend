package com.hodi.modules.disbursements;

import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Chases transfers the bank has not answered about, and sends approved ones the after-commit hook missed.
 *
 * <p>The same rules the prompt's sweep paid for: a missing answer never fails a transfer, only a definite
 * answer is terminal, automatic enquiries are capped, and a row nobody can explain gets a sentence. Plus
 * one of its own: <b>nothing here re-sends.</b> An approved row that was never claimed is sent; a SENDING or
 * SENT row past its deadline is asked about.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DisbursementSweep {

    private static final long LOCK_KEY = 7_260_920_002L;

    private final DisbursementService service;
    private final ConfigurationService configs;
    private final TransactionTemplate newTransaction;
    private final JdbcTemplate jdbc;

    @Scheduled(fixedRate = 30_000)
    public void sweep() {
        newTransaction.executeWithoutResult(status -> {
            Boolean acquired = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(acquired)) return;
            pass();
        });
    }

    /** The pass itself. Package-private so a test can run it without the scheduler or the lock. */
    void pass() {
        for (Disbursement row : service.approvedAndUnsent()) {
            try {
                log.info("Disbursement {} was approved and never sent; sending", row.getReference());
                service.dispatch(row.getId());
            } catch (Exception e) {
                log.error("Could not send disbursement {}: {}", row.getReference(), e.getMessage());
            }
        }
        int cap = configs.getInt(ConfigKey.COOP_STATUS_QUERY_MAX_ATTEMPTS);
        for (Disbursement row : service.unanswered()) {
            try {
                if (!row.pastItsDeadline()) continue;
                int used = row.getStatusQueryAttempts() == null ? 0 : row.getStatusQueryAttempts();
                if (used >= cap) continue;
                String state = service.query(row.getId(), true, null);
                log.info("Chased disbursement {} — now {} (enquiry {} of {})", row.getReference(), state, used + 1, cap);
            } catch (Exception e) {
                log.error("Could not chase disbursement {}: {}", row.getReference(), e.getMessage());
            }
        }
    }
}
