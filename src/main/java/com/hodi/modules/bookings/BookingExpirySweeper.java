package com.hodi.modules.bookings;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Closes reservations whose window has passed.
 *
 * <h2>Why this is a second bean</h2>
 *
 * <p>{@link BookingService#lapseOne} runs in its own {@code REQUIRES_NEW} transaction, and a self-invocation
 * inside the service would not go through the proxy — the annotation would be there and mean nothing, every
 * booking in a pass would share one transaction, and one bad row would roll back the lot. The same reason
 * {@code SearchAlertDispatcher} and {@code SearchAlertRunner} are two beans.
 *
 * <h2>Why lapsing is tidiness rather than correctness</h2>
 *
 * <p>{@code UnitBooking.isExpired()} is derived from the clock, so a reservation is already lapsed in every
 * reader's eyes the moment its window passes — before this has run, and whether or not it ever does. What the
 * sweep adds is the recorded state and the freed unit. That ordering is deliberate: a system where a hold only
 * expires once a scheduler has fired is a system where a missed cron sells a unit twice.
 *
 * <p>Hourly, not by the minute. The window is measured in days, so the cost of being up to an hour late is a
 * unit showing reserved for an hour longer — against a job that would otherwise wake sixty times as often to
 * find nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpirySweeper {

    private final BookingService bookings;
    private final com.hodi.modules.configurations.ConfigurationService configs;

    @Scheduled(cron = "${hodi.bookings.expiry-cron:0 10 * * * *}")
    public void sweep() {
        try {
            int reminded = bookings.remindExpiring(configs.getInt(
                    com.hodi.enums.ConfigKey.BOOKING_EXPIRY_REMINDER_DAYS, 3));
            if (reminded > 0) log.info("Bookings: {} buyers reminded that their hold is about to lapse", reminded);
        } catch (Exception e) {
            log.warn("Could not send hold reminders: {}", e.getMessage());
        }
        List<Long> due = bookings.findLapsedIds();
        if (due.isEmpty()) return;

        int lapsed = 0;
        for (Long id : due) {
            try {
                if (bookings.lapseOne(id)) lapsed++;
            } catch (Exception e) {
                // Stepped over, logged with the id so it can be chased. One stuck booking is not a reason for
                // the other forty to keep holding units nobody has claimed.
                log.warn("Could not lapse booking {}: {}", id, e.getMessage());
            }
        }
        log.info("Bookings: {} of {} expired reservations closed", lapsed, due.size());
    }
}
