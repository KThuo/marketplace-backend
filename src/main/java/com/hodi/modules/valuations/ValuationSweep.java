package com.hodi.modules.valuations;

import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;

/**
 * Says the two things about valuations that only the calendar knows (plan §3.4).
 *
 * <p>A job whose due date has passed is overdue, and the valuer on it and the platform's assigners are told
 * — once, because the column that remembers it is set in the same transaction as the notice. A valuer whose
 * professional indemnity cover or registration runs out within the configured notice is told, and so are
 * the panel's managers — once per expiry date, so a renewal that moves the date earns a fresh warning when
 * its turn comes, and a valuer who ignores one is not nagged every morning.
 *
 * <p>Daily, early, under an advisory lock so two instances do not both say it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ValuationSweep {

    private static final long LOCK_KEY = 7_260_929_001L;

    private final ValuationRequestRepository requests;
    private final ValuerProfileRepository valuers;
    private final ValuationNotifier notifier;
    private final ConfigurationService configs;
    private final TransactionTemplate newTransaction;
    private final JdbcTemplate jdbc;

    @Scheduled(cron = "${hodi.valuations.sweep-cron:0 20 6 * * *}")
    public void sweep() {
        newTransaction.executeWithoutResult(status -> {
            Boolean acquired = jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
            if (!Boolean.TRUE.equals(acquired)) return;
            pass(LocalDate.now());
        });
    }

    /** The pass itself, for a day. Package-private so a test can run it without the scheduler or the lock. */
    void pass(LocalDate today) {
        int overdue = 0;
        for (ValuationRequest job : requests.findOverdueUnnoticed(today)) {
            try {
                ValuerProfile valuer = job.getValuerProfileId() == null ? null
                        : valuers.findById(job.getValuerProfileId()).orElse(null);
                notifier.overdue(job, valuer);
                job.setOverdueNoticedOn(today);
                requests.save(job);
                overdue++;
            } catch (Exception e) {
                log.error("Could not notice overdue valuation {}: {}", job.getReference(), e.getMessage());
            }
        }

        int warned = 0;
        LocalDate horizon = today.plusDays(Math.max(0, configs.getInt(ConfigKey.VALUATION_LAPSE_WARNING_DAYS)));
        for (ValuerProfile valuer : valuers.findLapsingBy(horizon)) {
            try {
                LocalDate on = valuer.nextLapseOn();
                if (on == null || on.equals(valuer.getLapseWarnedFor())) continue;
                String what = on.equals(valuer.getPiExpiresOn()) ? "professional indemnity cover" : "registration";
                notifier.lapseWarning(valuer, on, what);
                valuer.setLapseWarnedFor(on);
                valuers.save(valuer);
                warned++;
            } catch (Exception e) {
                log.error("Could not warn valuer {} about a lapse: {}", valuer.getReference(), e.getMessage());
            }
        }
        if (overdue > 0 || warned > 0) {
            log.info("Valuations: {} overdue notices, {} lapse warnings", overdue, warned);
        }
    }
}
