package com.hodi.modules.consent;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.consent.ConsentService.ConsentRow;
import com.hodi.modules.consent.ConsentService.UpdateConsentRequest;
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
 * Everyone has a position on consent, and in-app is a channel.
 *
 * <p>The bug this guards against: a person whose account somebody else created had no consent rows, and
 * every notice to them was dropped as if refused. Now onboarding writes the opening position, a sender
 * that finds none records it rather than staying silent, and in-app is on for everyone by default.
 */
@SpringBootTest
@Transactional
class ConsentForEveryoneIT {

    @Autowired ConsentService consent;
    @Autowired ConsentRepository rows;
    @Autowired UserRepository users;

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    @DisplayName("an onboarded account starts with transactional and in-app granted, the rest refused, marked as onboarding")
    void onboardingWritesTheOpeningPosition() {
        User staff = users.save(User.builder().username("onboarded-" + System.nanoTime()).password("x")
                .email("onboarded" + System.nanoTime() + "@example.invalid").firstName("On").lastName("Boarded")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build());
        signInAsPlatform();
        consent.captureAtOnboarding(staff.getId());

        assertEquals(9, rows.findByUserIdOrderByPurposeAscChannelAsc(staff.getId()).size(), "three channels by three purposes");
        assertEquals(Set.of("EMAIL", "SMS", "IN_APP"), consent.channelsFor(staff.getId(), AppConstant.CONSENT_TRANSACTIONAL));
        assertEquals(Set.of("IN_APP"), consent.channelsFor(staff.getId(), AppConstant.CONSENT_PROMOTIONAL), "marketing is not smuggled in");
        assertEquals(Set.of("IN_APP"), consent.channelsFor(staff.getId(), AppConstant.CONSENT_PROPERTY_ALERTS));
        assertTrue(rows.findByUserIdOrderByPurposeAscChannelAsc(staff.getId()).stream()
                .allMatch(r -> AppConstant.CONSENT_SOURCE_ONBOARDING.equals(r.getSource())));

        consent.captureAtOnboarding(staff.getId());
        assertEquals(9, rows.findByUserIdOrderByPurposeAscChannelAsc(staff.getId()).size(), "idempotent");
    }

    @Test
    @DisplayName("a sender that finds no position records the opening one instead of staying silent")
    void aSenderNeverFindsNothing() {
        User orphan = users.save(User.builder().username("orphan-" + System.nanoTime()).password("x")
                .email("orphan" + System.nanoTime() + "@example.invalid").firstName("Or").lastName("Phan")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build());
        assertTrue(rows.findByUserIdOrderByPurposeAscChannelAsc(orphan.getId()).isEmpty(), "nobody asked them");

        Set<String> channels = consent.channelsFor(orphan.getId(), AppConstant.CONSENT_TRANSACTIONAL);
        assertEquals(Set.of("EMAIL", "SMS", "IN_APP"), channels, "told, not dropped");
        assertEquals(9, rows.findByUserIdOrderByPurposeAscChannelAsc(orphan.getId()).size());
        assertTrue(rows.findByUserIdOrderByPurposeAscChannelAsc(orphan.getId()).stream()
                .allMatch(r -> "ASSUMED".equals(r.getSource())), "and honest about it");
        assertEquals(Set.of("IN_APP"), consent.channelsFor(orphan.getId(), AppConstant.CONSENT_PROMOTIONAL));
    }

    @Test
    @DisplayName("in-app is a column of the grid: on by default, refusable for alerts and marketing, never for transactional")
    void inAppIsAChannel() {
        User buyer = users.save(User.builder().username("grid-" + System.nanoTime()).password("x")
                .email("grid" + System.nanoTime() + "@example.invalid").firstName("Gr").lastName("Id")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build());
        signInAs(buyer);
        consent.captureAtRegistration(buyer.getId(), true);

        List<ConsentRow> grid = consent.mine();
        assertEquals(3, grid.size());
        for (ConsentRow row : grid) {
            assertEquals(Set.of("EMAIL", "SMS", "IN_APP"), row.channels().keySet());
            assertTrue(row.channels().get("IN_APP"), row.purpose() + " in-app on by default");
        }

        consent.update(new UpdateConsentRequest(AppConstant.CONSENT_PROMOTIONAL, Map.of("IN_APP", false)));
        assertEquals(Set.of(), consent.channelsFor(buyer.getId(), AppConstant.CONSENT_PROMOTIONAL));

        HodiException refused = assertThrows(HodiException.class, () -> consent.update(
                new UpdateConsentRequest(AppConstant.CONSENT_TRANSACTIONAL, Map.of("IN_APP", false))));
        assertTrue(refused.getMessage().contains("cannot be switched off"));
    }

    private void signInAsPlatform() {
        User user = User.builder().id(7L).username("consent-admin").password("x")
                .email("c@example.invalid").firstName("Con").lastName("Sent")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        signInWith(user, AppConstant.ACTOR_PLATFORM, "SUPER_ADMIN", true);
    }

    private void signInAs(User user) {
        signInWith(user, AppConstant.ACTOR_BUYER, "BUYER", false);
    }

    private static void signInWith(User user, String actor, String type, boolean platform) {
        UserProfile profile = UserProfile.builder().id(user.getId()).userId(user.getId())
                .profileType(actor).userTypeCode(type).status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile, Set.of(), List.of(), platform, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
