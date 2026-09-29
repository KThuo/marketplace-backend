package com.hodi.modules.notifications;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Tries failed sends again, with backoff, up to the configured cap.
 *
 * <p>Every minute, under an advisory lock so two instances do not both send. Only FAILED rows: a SKIPPED
 * one was a switched-off channel or a missing address, and trying again would not change either.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRetrySweep {

    private static final long LOCK_KEY = 7_260_929_003L;

    private final NotificationService notifications;
    private final TransactionTemplate newTransaction;
    private final JdbcTemplate jdbc;

    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    public void sweep() {
        Boolean acquired = newTransaction.execute(status ->
                jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY));
        if (!Boolean.TRUE.equals(acquired)) return;
        try {
            int sent = notifications.retryDue();
            if (sent > 0) log.info("Notifications: {} failed sends went through on retry", sent);
        } catch (Exception e) {
            log.warn("Notification retry sweep failed: {}", e.getMessage());
        }
    }
}
