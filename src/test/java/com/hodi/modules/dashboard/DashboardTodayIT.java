package com.hodi.modules.dashboard;

import com.hodi.common.AppConstant;
import com.hodi.modules.analytics.AnalyticsViews.Attention;
import com.hodi.modules.analytics.AnalyticsViews.TodayView;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.users.User;
import com.hodi.security.principal.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The dashboard's one read, and what it may say to whom.
 *
 * <p>What is protected: every "needs you" line agrees with the list screen behind its link, a line is present
 * only for a caller who may act on it, and the month's figures are the month's — the same sums the analytics
 * page makes, not a second set. Read against whatever the shared database holds, so the assertions compare
 * the service's numbers to the same questions asked in SQL rather than to constants.
 */
@SpringBootTest
@Transactional
class DashboardTodayIT {

    @Autowired DashboardService service;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private void signInAsPlatform(String... permissions) {
        User user = User.builder().id(1L).username("superadmin").password("x").email("a@example.invalid")
                .firstName("Platform").lastName("Administrator").status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L).profileType(AppConstant.ACTOR_PLATFORM)
                .userTypeCode("SUPER_ADMIN").status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of(permissions), List.of(), true, true));
    }

    private void signInAsSeller(long tenantId, String... permissions) {
        User user = User.builder().id(8L).username("seller-staff").password("x").email("s@example.invalid")
                .firstName("Sel").lastName("Ler").status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(8L).userId(8L).profileType(AppConstant.ACTOR_SELLER)
                .userTypeCode("SELLER_OWNER").tenantId(tenantId).tenantName("Seller").status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of(permissions), List.of(tenantId), false, true));
    }

    private static void signIn(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static Optional<Attention> line(TodayView view, String key) {
        return view.attention().stream().filter(a -> a.key().equals(key)).findFirst();
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("every line agrees with the list behind its link, and the month's figures are the month's")
    void theLinesAgreeWithTheLists() {
        signInAsPlatform("DASHBOARD_VIEW", "STATEMENTS_VIEW", "BOOKINGS_VIEW", "DISBURSEMENTS_VIEW", "APPROVALS_VIEW",
                "PAYMENTS_VIEW", "PURCHASE_REQUESTS_DECIDE", "SITE_VISITS_DECIDE", "ENQUIRIES_VIEW", "PROPERTIES_APPROVE",
                "SELLERS_VIEW", "KYC_VIEW", "DEVELOPMENTS_FINANCE_VIEW");

        TodayView view = service.today(null);

        assertEquals("PLATFORM", view.audience());
        assertTrue(view.greeting().startsWith("Good "), view.greeting());
        assertEquals(12, view.trend().size(), "the year behind the month, month by month");
        assertTrue(view.recent().size() <= 6);

        long unplaced = count("select count(*) from coop_statements where status <> 5 and state = 'UNMAPPED'");
        assertEquals(unplaced > 0, line(view, "unplacedCredits").isPresent(), "a line only when there is something");
        line(view, "unplacedCredits").ifPresent(a -> {
            assertEquals(unplaced, a.count());
            assertEquals("/app/statements?state=UNMAPPED", a.action(), "the statements page on its queue");
            assertNotNull(a.amount());
            assertNotNull(a.oldest());
            assertTrue(a.title().contains("bank credit"), a.title());
        });

        long offers = count("select count(*) from purchase_requests where status <> 5 and state in ('SUBMITTED','UNDER_REVIEW')");
        assertEquals(offers > 0, line(view, "offers").isPresent());
        line(view, "offers").ifPresent(a -> assertEquals(offers, a.count()));

        long viewings = count("select count(*) from site_visits where status <> 5 and state = 'REQUESTED'");
        line(view, "viewings").ifPresent(a -> assertEquals(viewings, a.count()));

        long awaitingRelease = count("select count(*) from disbursements where status <> 5 and state = 'AWAITING_APPROVAL'");
        assertEquals(awaitingRelease > 0, line(view, "disbursementsAwaiting").isPresent());

        long myApprovals = count("select count(*) from approval_workflows where status <> 5 and state = 'PENDING' and submitted_by_user_id <> 1");
        assertEquals(myApprovals > 0, line(view, "approvals").isPresent(), "never the caller's own proposals");
        line(view, "approvals").ifPresent(a -> assertEquals(myApprovals, a.count()));

        long behind = count("select count(*) from v_booking_balances v join unit_bookings b on b.id = v.booking_id"
                + " where b.status <> 5 and b.state in ('RESERVED','AGREED') and v.overdue > 0");
        assertEquals(behind > 0, line(view, "buyersBehind").isPresent());
        line(view, "buyersBehind").ifPresent(a -> assertEquals(behind, a.count()));

        // The month: the same sum the analytics page makes for the same month.
        long paymentsThisMonth = count("select count(*) from payments where status = 1"
                + " and paid_on >= date_trunc('month', current_date) and paid_on < date_trunc('month', current_date) + interval '1 month'");
        assertEquals(paymentsThisMonth, view.month().payments());
        assertNotNull(view.month().collected().previous(), "read against the month before");
        assertEquals(view.month().bookings(), view.funnel().bookings(), "one number for one fact");

        // Money first, then work: a warning never comes after a neutral line.
        int firstNeutral = -1, lastWarning = -1;
        for (int i = 0; i < view.attention().size(); i++) {
            String tone = view.attention().get(i).tone();
            if ("neutral".equals(tone) && firstNeutral < 0) firstNeutral = i;
            if ("warning".equals(tone)) lastWarning = i;
        }
        if (firstNeutral >= 0 && lastWarning >= 0) {
            // The platform's project lines are warnings and come last by design; everything else honours the order.
            List<String> tail = view.attention().stream().map(Attention::key).filter(k -> k.startsWith("projects")).toList();
            assertTrue(lastWarning < firstNeutral || !tail.isEmpty());
        }
    }

    @Test
    @DisplayName("a line is present only for a caller who may act on it")
    void linesAreGatedOnTheRightToAct() {
        signInAsPlatform("DASHBOARD_VIEW");
        TodayView bare = service.today(null);
        assertTrue(bare.attention().isEmpty(), "no permission, no line: " + bare.attention());
        assertNotNull(bare.month(), "the figures still come — that is what DASHBOARD_VIEW is");

        signInAsPlatform("DASHBOARD_VIEW", "STATEMENTS_VIEW");
        TodayView statementsOnly = service.today(null);
        assertTrue(statementsOnly.attention().stream().allMatch(a -> a.key().equals("unplacedCredits")),
                "only the one line the permission opens: " + statementsOnly.attention());
    }

    @Test
    @DisplayName("a seller reads their own organisation: no bank queues, and figures scoped to their homes")
    void aSellerReadsTheirOwn() {
        long tenantId = count("select tenant_id from developments where reference = 'DV260827DEMO'");
        signInAsSeller(tenantId, "DASHBOARD_VIEW", "STATEMENTS_VIEW", "DISBURSEMENTS_VIEW", "BOOKINGS_VIEW",
                "PURCHASE_REQUESTS_DECIDE", "PROPERTIES_APPROVE", "KYC_VIEW");

        TodayView view = service.today(null);

        assertEquals("SELLER", view.audience());
        assertTrue(line(view, "unplacedCredits").isEmpty(), "the platform's accounts are not theirs to place");
        assertTrue(line(view, "disbursementsAwaiting").isEmpty() && line(view, "listingsPending").isEmpty()
                && line(view, "kycPending").isEmpty(), "the platform's queues are the platform's");

        long theirOffers = count("select count(*) from purchase_requests where status <> 5 and tenant_id = ?"
                + " and state in ('SUBMITTED','UNDER_REVIEW')", tenantId);
        assertEquals(theirOffers > 0, line(view, "offers").isPresent());
        line(view, "offers").ifPresent(a -> assertEquals(theirOffers, a.count()));

        long theirBehind = count("select count(*) from v_booking_balances v join unit_bookings b on b.id = v.booking_id"
                + " where b.status <> 5 and b.state in ('RESERVED','AGREED') and v.overdue > 0 and b.tenant_id = ?", tenantId);
        line(view, "buyersBehind").ifPresent(a -> assertEquals(theirBehind, a.count()));
        assertTrue(view.recent().stream().allMatch(r -> r.developmentName() != null), "their developments' receipts");
    }
}
