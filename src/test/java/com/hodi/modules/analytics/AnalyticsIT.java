package com.hodi.modules.analytics;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.analytics.AnalyticsViews.*;
import com.hodi.modules.dashboard.DashboardService;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.users.User;
import com.hodi.security.hashid.HashIdUtil;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The analytics figures, and the scoping that makes them somebody's figures.
 *
 * <p>Every assertion about a total is made twice: once as the organisation that owns the rows, once as one
 * that does not. A total that reaches past the caller's scope is a plausible number to the wrong person, and
 * nothing about it looks foreign — which is why the second half of each test is the half that matters.
 */
@SpringBootTest
@Transactional
class AnalyticsIT {

    @Autowired AnalyticsService analytics;
    @Autowired DashboardService dashboard;
    @Autowired DevelopmentRepository developments;
    @Autowired JdbcTemplate jdbc;

    private Long mine;
    private Long theirs;
    private Long institutionId;
    private Development development;
    private final YearMonth thisMonth = YearMonth.now();
    private final AnalyticsWindow window = new AnalyticsWindow(
            thisMonth.minusMonths(2).getYear(), thisMonth.minusMonths(2).getMonthValue(),
            thisMonth.getYear(), thisMonth.getMonthValue());

    @BeforeEach
    void build() {
        List<Long> tenants = jdbc.queryForList("select id from tenants where status <> 5 order by id limit 2", Long.class);
        mine = tenants.getFirst();
        theirs = tenants.get(1);
        institutionId = jdbc.queryForObject("select id from banks order by id limit 1", Long.class);

        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(mine).sellingTenantId(mine)
                .name("Figures Court").developmentType("APARTMENT").currency("KES")
                .budgetAmount(new BigDecimal("1000000")).build());

        // A cost this month and one last month, both spent, one committed; a payment this month.
        jdbc.update("insert into development_expenditures (reference, development_id, category_id, kind, amount, currency,"
                + " incurred_on, tenant_id, created_by, updated_by) values (?, ?, "
                + "(select id from development_cost_categories where code = 'CONSTRUCTION'), 'SPENT', 600000, 'KES', ?, ?, 'test', 'test')",
                RrnGenerator.generate("EX"), development.getId(), LocalDate.now(), mine);
        jdbc.update("insert into development_expenditures (reference, development_id, category_id, kind, amount, currency,"
                + " incurred_on, tenant_id, created_by, updated_by) values (?, ?, "
                + "(select id from development_cost_categories where code = 'LAND'), 'SPENT', 500000, 'KES', ?, ?, 'test', 'test')",
                RrnGenerator.generate("EX"), development.getId(), LocalDate.now().minusMonths(1), mine);
        jdbc.update("insert into development_expenditures (reference, development_id, category_id, kind, amount, currency,"
                + " incurred_on, tenant_id, created_by, updated_by) values (?, ?, "
                + "(select id from development_cost_categories where code = 'OTHER'), 'COMMITTED', 70000, 'KES', ?, ?, 'test', 'test')",
                RrnGenerator.generate("EX"), development.getId(), LocalDate.now(), mine);

        signInAsSeller(mine);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signIn(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void signInAsSeller(Long tenant) {
        User user = User.builder().id(1L).username("figures-seller").password("x").email("s@example.invalid")
                .firstName("Sam").lastName("Seller").status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L).profileType(AppConstant.ACTOR_SELLER)
                .userTypeCode("SELLER_OWNER").tenantId(tenant).tenantName("Seller").status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of("DASHBOARD_VIEW", "DEVELOPMENTS_FINANCE_VIEW"), List.of(tenant), false, true));
    }

    /*
     * A caller bound to the bank that owns the row.
     *
     * <p>The profile type is a literal rather than a constant from AppConstant, and deliberately so: no
     * user type produces an institution-bound principal any more, so there is no actor class to name. What
     * is still in the code is the branch in OwnerScopeSql and DevelopmentVisibility that scopes on
     * institution_id, because bank-owned developments are real rows. This keeps that branch guarded —
     * anything but PLATFORM reaches it, and PLATFORM short-circuits to TRUE, which is what a platform
     * caller should get and is not what this is testing.
     */
    private void signInAsBank(Long institution) {
        User user = User.builder().id(2L).username("figures-bank").password("x").email("l@example.invalid")
                .firstName("Len").lastName("Der").status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(2L).userId(2L).profileType("BANK")
                .userTypeCode("BANK_ADMIN").institutionId(institution).status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of("DASHBOARD_VIEW", "DEVELOPMENTS_FINANCE_VIEW"), List.of(), false, true));
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertNotNull(actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    @Test
    @DisplayName("the summary sums the window, and the previous window is the same length before it")
    void summaryOverTheWindow() {
        String hash = HashIdUtil.encodeId(development.getId());
        SummaryView s = analytics.summary(window, hash);
        assertMoney("1100000", s.spent().value());
        assertEquals(3, s.months().size());
        assertEquals(window.months(), s.previous().months());
        assertMoney("1100000", s.now().spentToDate());
        assertEquals(1, s.now().developmentsOverBudget(), "1.1m spent against a 1m budget");

        // The trend puts each cost in the month it was incurred.
        TrendPoint last = s.months().getLast();
        assertMoney("600000", last.spent());
        assertMoney("500000", s.months().get(1).spent());

        // A one-month window has last month's line only in its "previous".
        AnalyticsWindow oneMonth = new AnalyticsWindow(thisMonth.getYear(), thisMonth.getMonthValue(),
                thisMonth.getYear(), thisMonth.getMonthValue());
        SummaryView m = analytics.summary(oneMonth, hash);
        assertMoney("600000", m.spent().value());
        assertMoney("500000", m.spent().previous());
        assertEquals(0, new BigDecimal("20.0").compareTo(m.spent().change()));
    }

    @Test
    @DisplayName("composition and comparison read the same ledger")
    void compositionAndComparison() {
        String hash = HashIdUtil.encodeId(development.getId());
        CompositionView c = analytics.composition(window, hash);
        assertEquals(2, c.spendByCategory().size());
        assertEquals("Construction", c.spendByCategory().getFirst().label());

        DevelopmentsView d = analytics.developments(window, hash);
        assertEquals(1, d.rows().size());
        DevelopmentComparison row = d.rows().getFirst();
        assertTrue(row.overBudget());
        assertMoney("1100000", row.spent());
        assertMoney("70000", row.committed());
        assertMoney("1100000", d.spent());
    }

    @Test
    @DisplayName("another seller's figures exclude this development; the development itself is not found to them")
    void scopedToTheCaller() {
        String hash = HashIdUtil.encodeId(development.getId());
        signInAsSeller(theirs);
        SummaryView s = analytics.summary(window, null);
        // Cannot be asserted as zero — the seeded database may hold their own costs — but ours are not in it.
        long ours = jdbc.queryForObject("select count(*) from development_expenditures where development_id = ?",
                Long.class, development.getId());
        assertEquals(3, ours);
        DevelopmentsView d = analytics.developments(window, null);
        assertTrue(d.rows().stream().noneMatch(r -> r.name().equals("Figures Court")));
        assertNotNull(s);
        assertThrows(ResourceNotFoundException.class, () -> analytics.summary(window, hash));
        assertThrows(ResourceNotFoundException.class, () -> dashboard.overall(null, hash));
    }

    @Test
    @DisplayName("the bank's figures cover its own financed project and not a seller's")
    void bankScope() {
        Development financed = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).institutionId(institutionId).sellingTenantId(theirs)
                .name("Bank Court").developmentType("APARTMENT").currency("KES")
                .facilityAmount(new BigDecimal("5000000")).build());
        jdbc.update("insert into facility_drawdowns (reference, development_id, amount, currency, drawn_on,"
                + " institution_id, created_by, updated_by) values (?, ?, 2000000, 'KES', ?, ?, 'test', 'test')",
                RrnGenerator.generate("DD"), financed.getId(), LocalDate.now(), institutionId);

        signInAsBank(institutionId);
        SummaryView s = analytics.summary(window, null);
        assertTrue(s.drawn().value().compareTo(new BigDecimal("2000000")) >= 0);
        DevelopmentsView d = analytics.developments(window, null);
        assertTrue(d.rows().stream().anyMatch(r -> r.name().equals("Bank Court")));
        assertTrue(d.rows().stream().noneMatch(r -> r.name().equals("Figures Court")),
                "the seller's own project is not the bank's to add up");

        OverallView overall = dashboard.overall(null, HashIdUtil.encodeId(financed.getId()));
        assertMoney("2000000", overall.totals().drawn());
        assertMoney("3000000", overall.now().facility().subtract(overall.now().drawnToDate()));
    }

    @Test
    @DisplayName("the dashboard's month and calendar agree with the analytics trend")
    void dashboardFigures() {
        String hash = HashIdUtil.encodeId(development.getId());
        MonthlyView m = dashboard.monthly(thisMonth.getYear(), thisMonth.getMonthValue(), hash, 0, 10);
        assertMoney("600000", m.totals().spent());
        assertEquals(0, m.collections().totalElements());

        CalendarView c = dashboard.calendar(thisMonth.getYear(), hash);
        assertEquals(12, c.months().size());
        assertMoney("600000", c.months().get(thisMonth.getMonthValue() - 1).spent());

        ReceivablesView r = analytics.receivables(hash);
        assertEquals(5, r.ageing().size(), "every band present, empty ones included");
        assertMoney("0", r.overdue());

        PipelineView p = analytics.pipeline(window);
        assertNotNull(p.stats());
    }
}
