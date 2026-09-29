package com.hodi.modules.notifications;

import com.hodi.common.AppConstant;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.modules.consent.ConsentService;
import com.hodi.modules.consent.ConsentService.UpdateConsentRequest;
import com.hodi.modules.notifications.NotificationService.*;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.security.principal.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every message is recorded, and the person has an inbox.
 *
 * <p>The test suite runs the gateway in dry-run, so a send comes back skipped; what is tested is the
 * record — a row per channel, the inbox line, what the message was about — and the retry's arithmetic.
 */
@SpringBootTest
@Transactional
class NotificationsIT {

    @Autowired NotificationService notifications;
    @Autowired NotificationLogRepository logs;
    @Autowired NotificationRepository inbox;
    @Autowired ConsentService consent;
    @Autowired UserRepository users;

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    @DisplayName("a notice to a user is a row per consented channel, an inbox line, and says what it was about")
    void recordedAndInTheInbox() {
        User person = user("told");
        signInAs(person);
        consent.captureAtRegistration(person.getId(), false);
        consent.update(new UpdateConsentRequest(AppConstant.CONSENT_PROMOTIONAL, Map.of("EMAIL", true)));

        About about = new About("BOOKING", 42L, "BK-TEST");
        notifications.toUser(person.getId(), Notice.transactional("BOOKINGS", "Your booking", "Something happened.", "/account/bookings", about));

        List<NotificationLog> rows = logs.findByAboutTypeAndAboutIdOrderByCreatedAtDesc("BOOKING", 42L);
        assertEquals(2, rows.size(), "email and SMS; in-app is the inbox, not the gateway");
        assertEquals(Set.of("EMAIL", "SMS"), Set.of(rows.get(0).getChannel(), rows.get(1).getChannel()));
        for (NotificationLog row : rows) {
            assertEquals(person.getId(), row.getUserId());
            assertEquals("BK-TEST", row.getAboutRef());
            assertEquals(NotificationLog.SKIPPED, row.getStatus(), "dry run: the gateway did not take it, and it is not a failure");
            assertEquals(1, row.getAttempts());
            assertNotNull(row.getPayload(), "kept as composed, for a retry");
            assertTrue(row.getRecipientMasked().contains("***"), "shown masked");
        }

        InboxSummary summary = notifications.summary();
        assertEquals(1, summary.unread());
        InboxItem item = summary.latest().get(0);
        assertEquals("Your booking", item.title());
        assertEquals("BK-TEST", item.aboutRef());
        assertNull(item.readAt());

        notifications.markRead(item.id());
        assertEquals(0, notifications.summary().unread());
        assertNotNull(notifications.mine(new PagedDataRequest()).getContent().get(0).readAt());

        // Marketing: in-app and email were granted, SMS was not.
        notifications.toUser(person.getId(), new Notice("CAMPAIGNS", AppConstant.CONSENT_PROMOTIONAL, "News", "Some news.", "/", null));
        assertEquals(1, notifications.summary().unread(), "in-app for marketing too, since it is on");
        long marketingRows = logs.findAll().stream().filter(l -> "CAMPAIGNS".equals(l.getEventCode()) && person.getId().equals(l.getUserId())).count();
        assertEquals(1, marketingRows, "email only");

        assertEquals(1, notifications.markAllRead());
    }

    @Test
    @DisplayName("a contact with no account is told by email and SMS and recorded without a user")
    void aContactIsRecordedToo() {
        signInAs(user("staff"));
        About about = new About("BOOKING", 43L, "BK-WALKIN");
        notifications.toContact(new Contact("Walk In", "walkin@example.invalid", "+254700000000"),
                Notice.transactional("BOOKINGS", "Your booking terms", "Read and answer.", "/account/bookings", about));
        List<NotificationLog> rows = logs.findByAboutTypeAndAboutIdOrderByCreatedAtDesc("BOOKING", 43L);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(r -> r.getUserId() == null));
        assertEquals("wa***@example.invalid", rows.stream().filter(r -> "EMAIL".equals(r.getChannel())).findFirst().orElseThrow().getRecipientMasked());
        assertEquals("***0000", rows.stream().filter(r -> "SMS".equals(r.getChannel())).findFirst().orElseThrow().getRecipientMasked());

        notifications.toContact(new Contact("Nobody", null, "-"), Notice.transactional("BOOKINGS", "x", "y", "/", new About("BOOKING", 44L, "BK-NONE")));
        assertTrue(logs.findByAboutTypeAndAboutIdOrderByCreatedAtDesc("BOOKING", 44L).isEmpty(), "no address, nothing to record");
    }

    @Test
    @DisplayName("a failed send is tried again with backoff until the cap; a skipped one is left alone")
    void retry() {
        signInAs(user("retry"));
        NotificationLog failed = logs.save(NotificationLog.builder()
                .eventCode("TEST").purpose(AppConstant.CONSENT_TRANSACTIONAL).channel("EMAIL")
                .recipient("retry@example.invalid").recipientMasked("re***@example.invalid").subject("Again")
                .payload("<p>again</p>").status(NotificationLog.FAILED).attempts(1).error("gateway down")
                .nextAttemptAt(OffsetDateTime.now().minusMinutes(1)).build());
        NotificationLog notYet = logs.save(NotificationLog.builder()
                .eventCode("TEST").purpose(AppConstant.CONSENT_TRANSACTIONAL).channel("SMS")
                .recipient("+254711111111").recipientMasked("***1111").payload("later")
                .status(NotificationLog.FAILED).attempts(1).nextAttemptAt(OffsetDateTime.now().plusMinutes(10)).build());
        NotificationLog capped = logs.save(NotificationLog.builder()
                .eventCode("TEST").purpose(AppConstant.CONSENT_TRANSACTIONAL).channel("SMS")
                .recipient("+254722222222").recipientMasked("***2222").payload("never")
                .status(NotificationLog.FAILED).attempts(5).nextAttemptAt(OffsetDateTime.now().minusMinutes(1)).build());
        NotificationLog skipped = logs.save(NotificationLog.builder()
                .eventCode("TEST").purpose(AppConstant.CONSENT_TRANSACTIONAL).channel("SMS")
                .recipient("+254733333333").recipientMasked("***3333").payload("off")
                .status(NotificationLog.SKIPPED).attempts(1).build());

        List<NotificationLog> due = logs.findDueForRetry(OffsetDateTime.now(), 5);
        assertTrue(due.stream().anyMatch(l -> l.getId().equals(failed.getId())), "its turn has come");
        assertFalse(due.stream().anyMatch(l -> l.getId().equals(notYet.getId())), "not yet");
        assertFalse(due.stream().anyMatch(l -> l.getId().equals(capped.getId())), "given up on");
        assertFalse(due.stream().anyMatch(l -> l.getId().equals(skipped.getId())), "never a failure");

        notifications.attempt(failed);
        NotificationLog after = logs.findById(failed.getId()).orElseThrow();
        assertEquals(2, after.getAttempts());
        assertNotNull(after.getLastAttemptAt());
        // Dry run answers "skipped": the row stops being a failure and is not tried again.
        assertEquals(NotificationLog.SKIPPED, after.getStatus());
        assertNull(after.getNextAttemptAt());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private User user(String tag) {
        return users.save(User.builder().username(tag + "-" + System.nanoTime()).password("x")
                .email(tag + System.nanoTime() + "@example.invalid").phone("+2547" + (System.nanoTime() % 100000000L))
                .firstName("No").lastName("Tified").status(AppConstant.STATUS_ACTIVE).enabled(true).build());
    }

    private static void signInAs(User user) {
        UserProfile profile = UserProfile.builder().id(user.getId()).userId(user.getId())
                .profileType(AppConstant.ACTOR_BUYER).userTypeCode("BUYER").status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile, Set.of(), List.of(), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
