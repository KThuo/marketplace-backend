package com.hodi.modules.buyerportal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The poll that runs saved searches (BRD FR023–FR024).
 *
 * <p>Thin on purpose: it finds what is due and hands each row to {@link SearchAlertRunner}, which is a
 * separate bean rather than a method here. Each alert runs in its own {@code REQUIRES_NEW} transaction, and
 * a self-invocation would not go through the proxy — the annotation would be there and mean nothing, with
 * every alert in a pass sharing one transaction and one gateway rejection rolling back the bookkeeping for
 * all of them. Two beans is what makes the boundary real.
 *
 * <h2>A poll, and honest about being one</h2>
 *
 * <p>{@code INSTANT} means "the next time this runs", which is a quarter of an hour. A genuine push needs a
 * queue between publication and delivery, and building one for a listing volume that does not exist yet
 * would be infrastructure bought on speculation. The naming stays truthful throughout: the screen offers
 * "as soon as we find one", and nobody is promised a second.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchAlertDispatcher {

    /** How many alerts one pass works through. A backlog drains over several passes, not in one burst. */
    private static final int BATCH = 50;

    private final SearchAlertRepository repository;
    private final SearchAlertRunner runner;

    @Scheduled(cron = "${hodi.alerts.dispatch-cron:0 */15 * * * *}")
    public void dispatchDue() {
        List<SearchAlert> due = repository.findDue(OffsetDateTime.now(), PageRequest.of(0, BATCH));
        if (due.isEmpty()) return;

        log.info("Search alerts: {} due", due.size());
        int sent = 0;
        for (SearchAlert alert : due) {
            try {
                if (runner.runOne(alert.getId())) sent++;
            } catch (Exception e) {
                // Logged and stepped over. One alert's failure is not a reason for the other forty-nine to
                // go unsent, and that row's own bookkeeping was committed or rolled back on its own.
                log.warn("Search alert {} failed: {}", alert.getId(), e.getMessage());
            }
        }
        log.info("Search alerts: {} of {} delivered", sent, due.size());
    }
}
