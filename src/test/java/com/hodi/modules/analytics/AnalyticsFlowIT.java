package com.hodi.modules.analytics;

import com.hodi.common.AppConstant;
import com.hodi.modules.analytics.AnalyticsViews.CollectionsView;
import com.hodi.modules.analytics.AnalyticsViews.FunnelView;
import com.hodi.modules.analytics.AnalyticsViews.Interval;
import com.hodi.modules.analytics.AnalyticsViews.Stage;
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

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Collections and the funnel, read against the same questions asked in SQL.
 *
 * <p>The shared development database holds whatever it holds, so nothing here compares to a constant: each
 * figure the service produces is checked against the plain query that defines it, and the relationships that
 * must hold between figures — on time plus late equals scheduled, the channels add up to the collected total,
 * a stage's conversion is its count over the stage before — are checked directly.
 */
@SpringBootTest
@Transactional
class AnalyticsFlowIT {

    @Autowired AnalyticsService service;
    @Autowired JdbcTemplate jdbc;

    private final AnalyticsWindow year = AnalyticsWindow.of(null, null, null, null);

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private void signInAsPlatform() {
        User user = User.builder().id(1L).username("superadmin").password("x").email("a@example.invalid")
                .firstName("Platform").lastName("Administrator").status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L).profileType(AppConstant.ACTOR_PLATFORM)
                .userTypeCode("SUPER_ADMIN").status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of("DASHBOARD_VIEW"), List.of(), true, true));
    }

    private void signInAsSeller(long tenantId) {
        User user = User.builder().id(8L).username("seller-staff").password("x").email("s@example.invalid")
                .firstName("Sel").lastName("Ler").status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(8L).userId(8L).profileType(AppConstant.ACTOR_SELLER)
                .userTypeCode("SELLER_OWNER").tenantId(tenantId).tenantName("Seller").status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of("DASHBOARD_VIEW"), List.of(tenantId), false, true));
    }

    private static void signIn(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private BigDecimal sum(String sql, Object... args) {
        BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, args);
        return v == null ? BigDecimal.ZERO : v;
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static Stage stage(FunnelView f, String key) {
        return f.stages().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("collections: due and collected agree with the schedules and the receipts, and the parts add up")
    void collectionsAddUp() {
        signInAsPlatform();
        CollectionsView view = service.collections(year, null);

        assertEquals(12, view.months().size());
        BigDecimal due = sum("select coalesce(sum(i.amount), 0) from booking_instalments i join unit_bookings b on b.id = i.booking_id"
                + " where b.status <> 5 and b.state in ('RESERVED','AGREED','COMPLETED') and i.status <> 5"
                + " and i.plan_no = (select max(plan_no) from booking_instalments i2 where i2.booking_id = i.booking_id and i2.status <> 5)"
                + " and i.due_on >= ? and i.due_on < ?", year.startDate(), year.endDateExclusive());
        BigDecimal collected = sum("select coalesce(sum(amount), 0) from payments where status = 1 and paid_on >= ? and paid_on < ?",
                year.startDate(), year.endDateExclusive());
        assertEquals(0, due.compareTo(view.due()), "what the schedules said was due");
        assertEquals(0, collected.compareTo(view.collected()), "what arrived");
        if (due.signum() > 0) {
            assertNotNull(view.efficiency());
            assertEquals(0, collected.multiply(BigDecimal.valueOf(100)).divide(due, 1, java.math.RoundingMode.HALF_UP)
                    .compareTo(view.efficiency()));
        }

        // Every payment that met an instalment is either on time or late; none is both or neither.
        assertEquals(view.lateness().scheduled(), view.lateness().onTime() + view.lateness().late());
        assertEquals(AnalyticsFlowQueries.GRACE_DAYS, view.lateness().graceDays());
        if (view.lateness().late() > 0) {
            assertNotNull(view.lateness().medianDaysLate());
            assertTrue(view.lateness().medianDaysLate() > AnalyticsFlowQueries.GRACE_DAYS, "late means past the grace");
        }

        // The channels are the collected total, cut by how it arrived.
        BigDecimal byChannel = view.channels().stream().map(c -> c.amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, collected.compareTo(byChannel), "the channels add up to what arrived");
        assertTrue(view.channels().stream().noneMatch(c -> c.channel().equals(c.channel().toUpperCase())
                && c.channel().contains("_")), "codes are turned into words: " + view.channels());

        // Prompts: sent is the sum of the outcomes, and the rate reads paid over answered.
        long sent = count("select count(*) from payment_intents where status <> 5 and created_at >= ? and created_at < ?",
                year.startDate(), year.endDateExclusive());
        assertEquals(sent, view.promptsSent());
        assertEquals(view.promptsSent(), view.promptsPaid() + view.promptsFailed() + view.promptsUnanswered());
        if (view.promptsPaid() + view.promptsFailed() > 0) assertNotNull(view.promptSuccessRate());
    }

    @Test
    @DisplayName("expected receivables: the horizons widen, never shrink, and the nearest agrees with the schedule")
    void expectedWidens() {
        signInAsPlatform();
        List<com.hodi.modules.analytics.AnalyticsViews.Expected> expected = service.receivables(null).expected();

        assertEquals(List.of(30, 60, 90), expected.stream().map(e -> e.days()).toList());
        for (int i = 1; i < expected.size(); i++) {
            assertTrue(expected.get(i).amount().compareTo(expected.get(i - 1).amount()) >= 0,
                    "what falls due by day 90 includes what falls due by day 30");
            assertTrue(expected.get(i).bookings() >= expected.get(i - 1).bookings());
        }
        assertTrue(expected.stream().allMatch(e -> e.amount().signum() >= 0), "beyond the overdue, never below it");

        BigDecimal in30 = sum("select coalesce(sum(greatest(h.due - v.paid, 0) - v.overdue), 0)"
                + " from v_booking_balances v join unit_bookings b on b.id = v.booking_id"
                + " join lateral (select coalesce(sum(i.amount), 0) due from booking_instalments i where i.booking_id = b.id and i.status <> 5"
                + "   and i.plan_no = (select max(plan_no) from booking_instalments i2 where i2.booking_id = i.booking_id and i2.status <> 5)"
                + "   and i.due_on <= current_date + 30) h on true"
                + " where b.status <> 5 and b.state in ('RESERVED','AGREED')");
        assertEquals(0, in30.compareTo(expected.get(0).amount()), "the schedule's own answer for the next thirty days");
    }

    @Test
    @DisplayName("the funnel: each stage is the list it counts, conversions read against the stage before, and steps have a median")
    void theFunnelCounts() {
        signInAsPlatform();
        FunnelView view = service.funnel(year);

        assertEquals(List.of("enquiries", "viewings", "offers", "bookings", "completed"),
                view.stages().stream().map(Stage::key).toList());
        assertEquals(count("select count(*) from enquiry_tickets where status <> 5 and created_at >= ? and created_at < ?",
                year.startDate(), year.endDateExclusive()), stage(view, "enquiries").count());
        assertEquals(count("select count(*) from site_visits where status <> 5 and requested_at >= ? and requested_at < ?",
                year.startDate(), year.endDateExclusive()), stage(view, "viewings").count());
        assertEquals(count("select count(*) from purchase_requests where status <> 5 and created_at >= ? and created_at < ?",
                year.startDate(), year.endDateExclusive()), stage(view, "offers").count());
        assertEquals(count("select count(*) from unit_bookings where status <> 5 and state in ('RESERVED','AGREED','COMPLETED')"
                + " and booked_on >= ? and booked_on < ?", year.startDate(), year.endDateExclusive()), stage(view, "bookings").count());

        assertNull(stage(view, "enquiries").conversion(), "the first stage converts from nothing");
        Stage viewings = stage(view, "viewings");
        if (stage(view, "enquiries").count() > 0) {
            assertEquals(0, BigDecimal.valueOf(viewings.count()).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(stage(view, "enquiries").count()), 1, java.math.RoundingMode.HALF_UP)
                    .compareTo(viewings.conversion()));
        }

        assertEquals(count("select count(*) from purchase_requests where status <> 5 and booking_id is not null"
                + " and created_at >= ? and created_at < ?", year.startDate(), year.endDateExclusive()), view.offersConverted());
        assertEquals(4, view.intervals().size());
        Interval offerToBooking = view.intervals().stream().filter(i -> i.key().equals("offerToBooking")).findFirst().orElseThrow();
        if (view.offersConverted() > 0) {
            assertNotNull(offerToBooking.medianDays(), "an offer that became a booking has a measurable gap");
            assertTrue(offerToBooking.medianDays() >= 0);
        } else {
            assertNull(offerToBooking.medianDays());
            assertEquals(0, offerToBooking.sample());
        }
        assertEquals(stage(view, "offers").count(),
                view.offersByOutcome().stream().mapToInt(s -> s.count()).sum(), "every offer has one outcome");
    }

    @Test
    @DisplayName("a seller reads their own organisation's funnel and collections, not the platform's")
    void aSellerReadsTheirOwn() {
        long tenantId = count("select tenant_id from developments where reference = 'DV260827DEMO'");
        signInAsSeller(tenantId);

        FunnelView funnel = service.funnel(year);
        assertEquals(count("select count(*) from purchase_requests where status <> 5 and tenant_id = ? and created_at >= ? and created_at < ?",
                tenantId, year.startDate(), year.endDateExclusive()), stage(funnel, "offers").count());

        CollectionsView collections = service.collections(year, null);
        BigDecimal theirs = sum("select coalesce(sum(p.amount), 0) from payments p where p.status = 1 and p.paid_on >= ? and p.paid_on < ?"
                + " and (p.tenant_id = ? or p.development_id in (select id from developments where selling_tenant_id = ? and status <> 5))",
                year.startDate(), year.endDateExclusive(), tenantId, tenantId);
        assertEquals(0, theirs.compareTo(collections.collected()), "their homes' money, and nobody else's");

        // A month that is not in the window is not a month at all.
        AnalyticsWindow one = new AnalyticsWindow(YearMonth.now().getYear(), YearMonth.now().getMonthValue(),
                YearMonth.now().getYear(), YearMonth.now().getMonthValue());
        assertEquals(1, service.collections(one, null).months().size());
    }
}
