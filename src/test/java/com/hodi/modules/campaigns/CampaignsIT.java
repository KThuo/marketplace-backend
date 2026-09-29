package com.hodi.modules.campaigns;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.campaigns.CampaignService.*;
import com.hodi.modules.consent.ConsentService;
import com.hodi.modules.consent.ConsentService.UpdateConsentRequest;
import com.hodi.modules.notifications.NotificationLogRepository;
import com.hodi.modules.notifications.NotificationRepository;
import com.hodi.modules.notifications.UnsubscribeService;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.security.principal.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A campaign is written, checked by a second person, and sent to the people who agreed — each recipient
 * through the same door as every other message, with a way out that works without signing in.
 */
@SpringBootTest
@Transactional
class CampaignsIT {

    @Autowired CampaignService campaigns;
    @Autowired CampaignSender sender;
    @Autowired CampaignRepository rows;
    @Autowired CampaignSendRepository sends;
    @Autowired ConsentService consent;
    @Autowired UnsubscribeService unsubscribe;
    @Autowired NotificationLogRepository logs;
    @Autowired NotificationRepository inbox;
    @Autowired UserRepository users;
    @Autowired UserProfileRepository profiles;
    @Autowired JdbcTemplate jdbc;

    private User willing;
    private User refused;
    private User admin;
    private User checker;

    @BeforeEach
    void build() {
        Long buyerType = jdbc.queryForObject("select id from user_types where code = 'BUYER' limit 1", Long.class);
        willing = buyer("willing", buyerType);
        refused = buyer("refused", buyerType);
        admin = users.save(User.builder().username("cm-admin-" + System.nanoTime()).password("x").email("cma" + System.nanoTime() + "@example.invalid")
                .firstName("Cam").lastName("Admin").status(AppConstant.STATUS_ACTIVE).enabled(true).build());
        checker = users.save(User.builder().username("cm-check-" + System.nanoTime()).password("x").email("cmc" + System.nanoTime() + "@example.invalid")
                .firstName("Cam").lastName("Checker").status(AppConstant.STATUS_ACTIVE).enabled(true).build());
        signIn(willing, false, Set.of());
        consent.captureAtRegistration(willing.getId(), false);
        consent.update(new UpdateConsentRequest(AppConstant.CONSENT_PROMOTIONAL, Map.of("EMAIL", true, "SMS", false)));
        signIn(refused, false, Set.of());
        consent.captureAtRegistration(refused.getId(), false);
        consent.update(new UpdateConsentRequest(AppConstant.CONSENT_PROMOTIONAL, Map.of("IN_APP", false)));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    @DisplayName("the audience is who agreed; a second person approves; the sweep sends inside the window with a way out")
    void writtenCheckedAndSent() {
        signInAsPlatform(admin, "CAMPAIGNS_MANAGE");
        CampaignResponse draft = campaigns.create(new SaveRequest("Spring open days", null, null, List.of("EMAIL", "IN_APP"),
                "Open days this weekend", "Come and see the show homes.\n\nSaturday and Sunday, ten till four.", "See the homes", "/marketplace", null));
        assertEquals(Campaign.DRAFT, draft.state());
        assertTrue(draft.mayEdit() && draft.maySubmit());

        int audience = campaigns.audienceCount(draft.reference());
        assertTrue(audience >= 1, "the willing buyer at least");
        List<Long> ids = campaigns.audienceIds(rows.findByReference(draft.reference()).orElseThrow());
        assertTrue(ids.contains(willing.getId()), "agreed to email");
        assertFalse(ids.contains(refused.getId()), "refused everything: not in the audience");

        CampaignResponse submitted = campaigns.submit(draft.reference());
        assertEquals(Campaign.SUBMITTED, submitted.state());
        assertEquals(audience, submitted.audienceCount());
        assertFalse(submitted.mayApprove(), "the author does not approve their own");
        assertThrows(HodiException.class, () -> campaigns.update(draft.reference(), new SaveRequest("x", null, null, List.of("EMAIL"), "s", "b", null, null, null)),
                "not edited once submitted");

        signInAsPlatform(checker, "CAMPAIGNS_APPROVE", "APPROVALS_DECIDE");
        CampaignResponse approved = campaigns.decide(draft.reference(), new ApprovalService.DecisionRequest("APPROVED", "Reads well"));
        assertEquals(Campaign.APPROVED, approved.state());
        assertEquals(checker.getUsername(), approved.approvedBy());
        Long campaignId = rows.findByReference(draft.reference()).orElseThrow().getId();
        assertEquals(audience, sends.countByCampaignIdAndState(campaignId, CampaignSend.PENDING), "the audience is fixed at approval");

        // The willing buyer changes their mind between approval and their turn: not sent to.
        signIn(willing, false, Set.of());
        consent.update(new UpdateConsentRequest(AppConstant.CONSENT_PROMOTIONAL, Map.of("EMAIL", false, "IN_APP", true)));

        // Outside the window nothing moves; inside it the batch goes.
        assertFalse(CampaignSender.insideWindow(LocalTime.of(2, 0), LocalTime.of(8, 0), LocalTime.of(20, 0)));
        assertTrue(CampaignSender.insideWindow(LocalTime.of(8, 0), LocalTime.of(8, 0), LocalTime.of(20, 0)));
        assertFalse(CampaignSender.insideWindow(LocalTime.of(20, 0), LocalTime.of(8, 0), LocalTime.of(20, 0)));
        OffsetDateTime insideWindow = OffsetDateTime.of(java.time.LocalDate.now(), LocalTime.of(10, 0), java.time.ZoneOffset.ofHours(3)); // 10:00 in Nairobi
        int done = sender.pass(insideWindow);
        assertTrue(done >= 1);
        Campaign after = rows.findById(campaignId).orElseThrow();
        assertEquals(Campaign.SENT, after.getState(), "small enough to finish in one batch");
        CampaignSend toWilling = sends.findByCampaignIdOrderByIdAsc(campaignId).stream().filter(s -> s.getUserId().equals(willing.getId())).findFirst().orElseThrow();
        assertEquals(CampaignSend.SENT, toWilling.getState());
        assertEquals("IN_APP", toWilling.getChannels(), "consent asked again at the moment of sending: email is off now, in-app on");
        assertEquals(1, inbox.findTop8ByUserIdOrderByCreatedAtDesc(willing.getId()).size());
        assertEquals("Open days this weekend", inbox.findTop8ByUserIdOrderByCreatedAtDesc(willing.getId()).get(0).getTitle());
        assertTrue(logs.findByAboutTypeAndAboutIdOrderByCreatedAtDesc("CAMPAIGN", campaignId).stream().noneMatch(l -> l.getUserId().equals(willing.getId())),
                "nothing by email or SMS for them");

        // The email carries the way out.
        String html = sender.emailBody(after, willing, unsubscribe.linkFor(willing.getId(), AppConstant.CONSENT_PROMOTIONAL));
        assertTrue(html.contains("Stop these messages"));
        assertTrue(html.contains("/unsubscribe?t="));
        assertTrue(html.contains("Come and see the show homes."));
        assertTrue(html.contains("See the homes"));
    }

    @Test
    @DisplayName("the unsubscribe link refuses the purpose on every channel without signing in, and cannot be forged")
    void theWayOut() {
        signIn(willing, false, Set.of());
        assertEquals(Set.of("EMAIL", "IN_APP"), consent.channelsFor(willing.getId(), AppConstant.CONSENT_PROMOTIONAL));
        SecurityContextHolder.clearContext();

        String token = unsubscribe.token(willing.getId(), AppConstant.CONSENT_PROMOTIONAL);
        assertEquals(AppConstant.CONSENT_PROMOTIONAL, unsubscribe.unsubscribe(token));
        assertEquals(Set.of(), consent.channelsFor(willing.getId(), AppConstant.CONSENT_PROMOTIONAL));
        assertEquals(AppConstant.CONSENT_SOURCE_UNSUBSCRIBE, jdbc.queryForObject(
                "select source from consent_preferences where user_id = ? and purpose = 'PROMOTIONAL' and channel = 'EMAIL'", String.class, willing.getId()));
        assertEquals(AppConstant.CONSENT_PROMOTIONAL, unsubscribe.unsubscribe(token), "a stale link still lands softly");

        assertThrows(HodiException.class, () -> unsubscribe.unsubscribe(token.substring(0, token.length() - 2) + "xx"), "forged");
        assertThrows(HodiException.class, () -> unsubscribe.unsubscribe(unsubscribe.token(willing.getId(), AppConstant.CONSENT_TRANSACTIONAL)),
                "transactional cannot be switched off from a link");
        assertEquals(Set.of("EMAIL", "SMS", "IN_APP"), consent.channelsFor(willing.getId(), AppConstant.CONSENT_TRANSACTIONAL));
    }

    @Test
    @DisplayName("an organisation runs campaigns only when the platform has opened the door, and only to its own buyers")
    void organisationCampaigns() {
        Long tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        signInAsOrganisation(admin, tenantId, "CAMPAIGNS_MANAGE");
        // The platform's setting is off by default in this environment: refused.
        HodiException closed = assertThrows(HodiException.class, () -> campaigns.create(new SaveRequest("Ours", null, null, List.of("EMAIL"),
                "s", "b", null, null, null)));
        assertTrue(closed.getMessage().contains("not opened"));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private User buyer(String tag, Long buyerType) {
        User u = users.save(User.builder().username(tag + "-" + System.nanoTime()).password("x")
                .email(tag + System.nanoTime() + "@example.invalid").phone("+2547" + (System.nanoTime() % 100000000L))
                .firstName("Cam").lastName("Paign").status(AppConstant.STATUS_ACTIVE).enabled(true).build());
        profiles.save(UserProfile.builder().userId(u.getId()).profileType(AppConstant.ACTOR_BUYER).userTypeId(buyerType).userTypeCode("BUYER")
                .userTypeName("Buyer").status(AppConstant.STATUS_ACTIVE).defaultProfile(true).build());
        return u;
    }

    private static void signIn(User user, boolean platform, Set<String> permissions) {
        UserProfile profile = UserProfile.builder().id(user.getId()).userId(user.getId())
                .profileType(platform ? AppConstant.ACTOR_PLATFORM : AppConstant.ACTOR_BUYER)
                .userTypeCode(platform ? "SUPER_ADMIN" : "BUYER").status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile, permissions, List.of(), platform, true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static void signInAsPlatform(User user, String... permissions) {
        signIn(user, true, Set.of(permissions));
    }

    private static void signInAsOrganisation(User user, Long tenantId, String... permissions) {
        UserProfile profile = UserProfile.builder().id(user.getId()).userId(user.getId())
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER").tenantId(tenantId).tenantName("Org")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile, Set.of(permissions), List.of(tenantId), false, true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
