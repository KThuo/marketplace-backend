package com.hodi.modules.analytics;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.analytics.AnalyticsViews.BankView;
import com.hodi.modules.analytics.AnalyticsViews.InventoryView;
import com.hodi.modules.analytics.AnalyticsViews.StockRow;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.users.User;
import com.hodi.security.principal.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The stock and the bank's money, read against the questions that define them.
 *
 * <p>Nothing here compares to a constant: the shared database holds what it holds. Each figure is checked
 * against its own SQL, the parts against their whole, and the bank's figures against the one rule that
 * matters most about them — that nobody but the platform gets them.
 */
@SpringBootTest
@Transactional
class AnalyticsStockIT {

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

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("inventory: the rows add up to the stock, the pace is sold over months, and months of stock follows")
    void theStockAddsUp() {
        signInAsPlatform();
        InventoryView view = service.inventory(year, null);

        assertEquals(12, view.months().size());
        long soldInWindow = count("select count(*) from properties u join developments d on d.id = u.development_id"
                + " where u.listing_kind = 'UNIT' and u.status <> 5 and d.status <> 5 and u.sale_state = 'SOLD'"
                + " and u.sold_at >= ? and u.sold_at < ?", year.startDate(), year.endDateExclusive());
        assertEquals(soldInWindow, view.soldInWindow());
        assertEquals(soldInWindow, view.months().stream().mapToInt(m -> m.sold()).sum(), "the months are the total, cut up");
        assertEquals(0, BigDecimal.valueOf(soldInWindow).divide(BigDecimal.valueOf(12), 1, java.math.RoundingMode.HALF_UP)
                .compareTo(view.soldPerMonth()));
        if (soldInWindow > 0) {
            assertNotNull(view.monthsOfStock());
            assertEquals(0, BigDecimal.valueOf(view.unitsAvailable()).divide(view.soldPerMonth(), 1, java.math.RoundingMode.HALF_UP)
                    .compareTo(view.monthsOfStock()), "what is left, at this pace");
        } else {
            assertNull(view.monthsOfStock(), "no pace, no forecast");
        }

        // Every unit is in exactly one row and one column.
        assertEquals(view.unitsTotal(), view.rows().stream().mapToInt(StockRow::total).sum(), "the rows are the stock");
        for (StockRow r : view.rows()) {
            assertEquals(r.total(), r.available() + r.held() + r.sold()
                    + count("select count(*) from properties u join developments d on d.id = u.development_id"
                            + " left join development_unit_types t on t.id = u.unit_type_id"
                            + " where u.listing_kind = 'UNIT' and u.status <> 5 and d.status <> 5 and d.name = ?"
                            + " and coalesce(t.name, 'Units') = ? and u.sale_state in ('NOT_FOR_SALE','RETAINED')",
                            r.developmentName(), r.unitType()),
                    "available, held, sold or withheld: " + r);
            if (r.pricePerSqm() != null) assertTrue(r.pricePerSqm().signum() > 0, r.toString());
        }
        assertEquals(count("select count(*) from unit_bookings where status <> 5 and state = 'LAPSED' and closed_at >= ? and closed_at < ?",
                year.startDate(), year.endDateExclusive()), view.holdsLapsed());
    }

    @Test
    @DisplayName("the bank: every statement has one outcome, placing has a median, and money out is what the bank confirmed")
    void theBankAddsUp() {
        signInAsPlatform();
        BankView view = service.bank(year);

        long arrived = count("select count(*) from coop_statements where status <> 5 and created_at >= ? and created_at < ?",
                year.startDate(), year.endDateExclusive());
        assertEquals(arrived, view.statements());
        assertEquals(view.statements(), view.automatic() + view.byHand() + view.setAside() + view.unplaced(),
                "placed by the matcher, placed by hand, set aside or unplaced — one of the four");
        assertEquals(count("select count(*) from coop_statements where status <> 5 and state = 'MAPPED' and mapped_by = 'system'"
                + " and created_at >= ? and created_at < ?", year.startDate(), year.endDateExclusive()), view.automatic());
        if (view.automatic() + view.byHand() > 0) {
            assertNotNull(view.automaticShare());
            assertNotNull(view.medianMinutesToPlace());
            assertTrue(view.medianMinutesToPlace() >= 0);
        }
        assertEquals(12, view.months().size());
        assertEquals(12, view.disbursements().size());
        assertEquals(12, view.flow().size());

        BigDecimal confirmedOut = jdbc.queryForObject("select coalesce(sum(amount), 0) from disbursements where status <> 5"
                + " and state = 'SUCCEEDED' and settled_at >= ? and settled_at < ?", BigDecimal.class,
                year.startDate(), year.endDateExclusive());
        assertEquals(0, confirmedOut.compareTo(view.disbursed()), "only what Co-op confirmed left");
        assertEquals(0, confirmedOut.compareTo(view.flow().stream().map(f -> f.out()).reduce(BigDecimal.ZERO, BigDecimal::add)));
        assertEquals(count("select count(*) from disbursements where status <> 5 and state = 'SUCCEEDED' and created_at >= ? and created_at < ?",
                year.startDate(), year.endDateExclusive()), view.disbursementsSucceeded());
    }

    @Test
    @DisplayName("a seller is refused the bank's figures outright, and reads only their own stock")
    void aSellerIsRefusedTheBank() {
        long tenantId = count("select tenant_id from developments where reference = 'DV260827DEMO'");
        signInAsSeller(tenantId);

        HodiException refused = assertThrows(HodiException.class, () -> service.bank(year));
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatus());

        InventoryView theirs = service.inventory(year, null);
        long theirUnits = count("select count(*) from properties u join developments d on d.id = u.development_id"
                + " where u.listing_kind = 'UNIT' and u.status <> 5 and d.status <> 5"
                + " and (d.tenant_id = ? or d.selling_tenant_id = ?)", tenantId, tenantId);
        assertEquals(theirUnits, theirs.rows().stream().mapToInt(StockRow::total).sum(), "their developments' units, and nobody else's");
    }
}
