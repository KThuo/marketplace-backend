package com.hodi.modules.sellerops;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ends placements whose window has closed (M13).
 *
 * <h2>Swept rather than computed</h2>
 *
 * <p>Marketplace search reads the boost column on every result. Expressing "…and the end date has not
 * passed" in that query would evaluate a comparison per row per search, on the hottest read path on the
 * platform, to answer a question that changes a handful of times a day.
 *
 * <p>The cost of sweeping is latency: a placement that ends at 14:00 stops being shown at 14:05. That is a
 * trade somebody would make knowingly, and nobody has ever asked for a promotion to stop to the second.
 *
 * <p>Hourly rather than by the minute for the same reason — the shorter the interval, the more often the
 * platform runs a query that almost always finds nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PromotionSweeper {

    private final PromotionService promotions;

    @Scheduled(cron = "0 5 * * * *")
    public void sweep() {
        try {
            int expired = promotions.expireFinished();
            if (expired > 0) log.info("Promotion sweep ended {} placement(s)", expired);
        } catch (RuntimeException e) {
            // A sweep that throws must not stop the next one from running.
            log.warn("Promotion sweep failed: {}", e.getMessage());
        }
    }
}
