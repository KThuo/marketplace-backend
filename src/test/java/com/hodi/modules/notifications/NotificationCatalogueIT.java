package com.hodi.modules.notifications;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.consent.ConsentService;
import com.hodi.modules.notifications.NotificationCatalogue.*;
import com.hodi.modules.notifications.NotificationService.About;
import com.hodi.modules.notifications.NotificationService.Event;
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

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the platform says is a catalogue: seeded for every event, filled from a model, layered with an
 * organisation's say, and the last word is consent.
 */
@SpringBootTest
@Transactional
class NotificationCatalogueIT {

    @Autowired NotificationCatalogue catalogue;
    @Autowired NotificationService notifications;
    @Autowired NotificationEventRepository events;
    @Autowired NotificationLogRepository logs;
    @Autowired NotificationRepository inbox;
    @Autowired ConsentService consent;
    @Autowired UserRepository users;

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    @DisplayName("every event a module sends is catalogued, with the placeholders its wording asks for")
    void seeded() {
        List<EventRow> all = catalogue.all();
        assertEquals(34, all.size(), "the whole inventory, one row per notice a module sends");
        for (EventRow row : all) {
            for (String name : com.hodi.common.util.Placeholders.namesIn(row.subject() + " " + row.line())) {
                assertTrue(row.placeholders().contains(name) || row.composed(),
                        row.code() + " asks for {{" + name + "}} but does not list it");
            }
        }
        assertTrue(all.stream().anyMatch(r -> r.code().equals("OFFER_RECEIVED") && r.audience().equals("SELLER_STAFF")));
        assertTrue(all.stream().anyMatch(r -> r.code().equals("ALERT_DIGEST") && r.composed()));
    }

    @Test
    @DisplayName("the layers: the platform's row, then the organisation's say, wording only when the platform allows")
    void layering() {
        NotificationEvent event = NotificationEvent.builder().code("X").audience("BUYER").purpose(AppConstant.CONSENT_TRANSACTIONAL)
                .enabled(true).channels("EMAIL,SMS,IN_APP").subject("Hello {{name}}").line("Line {{name}}").description("x")
                .placeholders("name").build();

        Resolved plain = NotificationCatalogue.apply(event, null, false);
        assertTrue(plain.enabled());
        assertEquals(Set.of("EMAIL", "SMS", "IN_APP"), plain.channels());

        NotificationEventOverride off = NotificationEventOverride.builder().eventCode("X").tenantId(1L).enabled(false).build();
        assertFalse(NotificationCatalogue.apply(event, off, true).enabled(), "an organisation may switch it off");

        NotificationEventOverride fewer = NotificationEventOverride.builder().eventCode("X").tenantId(1L).channels("IN_APP,EMAIL").build();
        assertEquals(Set.of("EMAIL", "IN_APP"), NotificationCatalogue.apply(event, fewer, true).channels(), "and narrow the channels");

        NotificationEventOverride reworded = NotificationEventOverride.builder().eventCode("X").tenantId(1L).subject("Jambo {{name}}").build();
        assertEquals("Hello {{name}}", NotificationCatalogue.apply(event, reworded, false).subject(), "wording ignored until the platform allows");
        assertEquals("Jambo {{name}}", NotificationCatalogue.apply(event, reworded, true).subject());

        event.setEnabled(false);
        NotificationEventOverride on = NotificationEventOverride.builder().eventCode("X").tenantId(1L).enabled(true).build();
        assertFalse(NotificationCatalogue.apply(event, on, true).enabled(), "an organisation cannot switch on what the platform switched off");
    }

    @Test
    @DisplayName("a catalogued event is filled from its model and delivered; a disabled or unknown one is not")
    void filledAndDelivered() {
        User buyer = user("cat");
        signInAs(buyer, false);
        consent.captureAtRegistration(buyer.getId(), false);

        notifications.event(buyer.getId(), Event.of("OFFER_COUNTERED",
                Map.of("property", "Villa Rosa", "seller", "Acacia Ridge", "amount", "KES 9,000,000", "reference", "OF-1"),
                "/account/conversations", new About("OFFER", 7L, "OF-1")));
        var item = inbox.findTop8ByUserIdOrderByCreatedAtDesc(buyer.getId()).get(0);
        assertEquals("A counter on your offer for Villa Rosa", item.getTitle());
        assertEquals("Acacia Ridge has come back at KES 9,000,000 on Villa Rosa. Accept it or counter from your offers.", item.getLine());
        assertEquals(2, logs.findByAboutTypeAndAboutIdOrderByCreatedAtDesc("OFFER", 7L).size(), "email and SMS rows");
        assertEquals("A counter on your offer for Villa Rosa", logs.findByAboutTypeAndAboutIdOrderByCreatedAtDesc("OFFER", 7L).get(0).getSubject());

        // The platform switches the event off: nothing more is said.
        signInAs(user("admin"), true);
        catalogue.save("OFFER_COUNTERED", new SaveEventRequest(false, null, null, null));
        notifications.event(buyer.getId(), Event.of("OFFER_COUNTERED", Map.of("property", "x", "seller", "y", "amount", "z", "reference", "r"),
                "/", new About("OFFER", 8L, "OF-2")));
        assertTrue(logs.findByAboutTypeAndAboutIdOrderByCreatedAtDesc("OFFER", 8L).isEmpty());
        assertEquals(1, inbox.findTop8ByUserIdOrderByCreatedAtDesc(buyer.getId()).size());

        // The platform narrows it to in-app and rewords it.
        catalogue.save("OFFER_COUNTERED", new SaveEventRequest(true, List.of("IN_APP"), "Counter: {{property}}", null));
        notifications.event(buyer.getId(), Event.of("OFFER_COUNTERED", Map.of("property", "Villa Rosa", "seller", "y", "amount", "z", "reference", "r"),
                "/", new About("OFFER", 9L, "OF-3")));
        assertTrue(logs.findByAboutTypeAndAboutIdOrderByCreatedAtDesc("OFFER", 9L).isEmpty(), "no email, no SMS");
        assertEquals("Counter: Villa Rosa", inbox.findTop8ByUserIdOrderByCreatedAtDesc(buyer.getId()).get(0).getTitle());

        // A wording that asks for a figure the event does not have is refused.
        HodiException hole = assertThrows(HodiException.class, () -> catalogue.save("OFFER_COUNTERED",
                new SaveEventRequest(null, null, "Counter {{price}}", null)));
        assertTrue(hole.getMessage().contains("{{price}}"));

        // Unknown: nothing, and no exception into the caller.
        notifications.event(buyer.getId(), Event.of("NO_SUCH_EVENT", Map.of(), "/", new About("OFFER", 10L, "OF-4")));
        assertTrue(logs.findByAboutTypeAndAboutIdOrderByCreatedAtDesc("OFFER", 10L).isEmpty());
    }

    @Test
    @DisplayName("an organisation's override applies to its own events and is refused for the platform's")
    void organisationOverride() {
        User admin = user("org");
        signInAsOrganisation(admin, 1L);
        List<EventRow> mine = catalogue.mine();
        assertTrue(mine.stream().anyMatch(r -> r.code().equals("ENQUIRY_RECEIVED")), "its staff's events");
        assertTrue(mine.stream().anyMatch(r -> r.code().equals("OFFER_COUNTERED")), "its buyers' events");
        assertFalse(mine.stream().anyMatch(r -> r.code().equals("VALUATION_RAISED")), "not the platform's");

        EventRow saved = catalogue.saveMine("ENQUIRY_RECEIVED", new SaveOverrideRequest(false, null, null, null));
        assertEquals(Boolean.FALSE, saved.effectiveEnabled());
        assertFalse(catalogue.resolve("ENQUIRY_RECEIVED", 1L, null).orElseThrow().enabled(), "off for this seller");
        assertTrue(catalogue.resolve("ENQUIRY_RECEIVED", 2L, null).orElseThrow().enabled(), "on for another");

        assertThrows(HodiException.class, () -> catalogue.saveMine("VALUATION_RAISED", new SaveOverrideRequest(false, null, null, null)));

        EventRow cleared = catalogue.saveMine("ENQUIRY_RECEIVED", new SaveOverrideRequest(null, null, null, null));
        assertNull(cleared.override(), "all blank clears it");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private User user(String tag) {
        return users.save(User.builder().username(tag + "-" + System.nanoTime()).password("x")
                .email(tag + System.nanoTime() + "@example.invalid").phone("+2547" + (System.nanoTime() % 100000000L))
                .firstName("Cat").lastName("Alogue").status(AppConstant.STATUS_ACTIVE).enabled(true).build());
    }

    private static void signInAs(User user, boolean platform) {
        UserProfile profile = UserProfile.builder().id(user.getId()).userId(user.getId())
                .profileType(platform ? AppConstant.ACTOR_PLATFORM : AppConstant.ACTOR_BUYER)
                .userTypeCode(platform ? "SUPER_ADMIN" : "BUYER").status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile, Set.of("APP_SETTINGS_UPDATE", "APP_SETTINGS_VIEW"), List.of(), platform, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static void signInAsOrganisation(User user, Long tenantId) {
        UserProfile profile = UserProfile.builder().id(user.getId()).userId(user.getId())
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER").tenantId(tenantId).tenantName("Org")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile, Set.of("APP_SETTINGS_OVERRIDE"), List.of(tenantId), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
