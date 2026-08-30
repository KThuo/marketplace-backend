package com.hodi.modules.analytics;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.analytics.ChartService.ChartData;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drawing a chart, and the scoping that stops it being somebody else's chart.
 *
 * <p>The scoping is the reason this is an integration test rather than a unit one. An aggregate that escaped
 * its tenant predicate returns a plausible number to the wrong organisation, and a plausible number is not
 * something anybody notices — it is not a stack trace, it is a slightly larger figure than expected.
 */
@SpringBootTest
@Transactional
class ChartServiceIT {

    @Autowired ChartService service;
    @Autowired PropertyRepository properties;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Long tenant(int index) {
        List<Long> ids = jdbc.queryForList(
                "select id from tenants where status <> 5 order by id", Long.class);
        return ids.get(Math.min(index, ids.size() - 1));
    }

    /** Signs in as a seller who may see only their own organisation. */
    private void signInAs(Long tenantId, String... permissions) {
        User user = User.builder().id(1L).username("chart-test").password("x")
                .email("c@example.invalid").firstName("Cara").lastName("Chart")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenantId).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile, Set.of(permissions),
                List.of(tenantId), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private Property listingFor(Long tenantId, String title) {
        return properties.save(Property.builder()
                .reference(RrnGenerator.generate("PR")).tenantId(tenantId)
                .title(title).description("For the chart test.")
                .propertyType("APARTMENT").listingType(AppConstant.LISTING_TYPE_SALE)
                .price(new BigDecimal("5000000")).county("Nairobi").town("Nairobi")
                .listingState(AppConstant.LISTING_LIVE).publishedAt(OffsetDateTime.now()).build());
    }

    // ── the thing that matters ────────────────────────────────────────────────

    @Test
    @DisplayName("a chart shows only the organisation asking for it")
    void chartsAreScopedToTheCaller() {
        Long mine = tenant(0);
        Long theirs = tenant(1);
        listingFor(mine, "Mine, on the chart");
        listingFor(theirs, "Theirs, not on my chart");

        signInAs(mine, "REPORTS_VIEW");
        ChartData chart = service.draw("listings-by-type");

        /*
         * The assertion is on the total rather than on labels, because both organisations list apartments —
         * a leak here would not add a category, it would inflate a number. That is exactly why an unscoped
         * aggregate is worse than an unscoped row: nothing about the result looks foreign.
         */
        BigDecimal total = chart.series().getFirst().total();
        signInAs(theirs, "REPORTS_VIEW");
        BigDecimal theirTotal = service.draw("listings-by-type").series().getFirst().total();

        long everything = jdbc.queryForObject(
                "select count(*) from properties where status <> 5 and listing_state = 'LIVE'", Long.class);
        assertTrue(total.longValue() < everything,
                "the caller's own figure must be smaller than the platform's: got " + total
                        + " against " + everything);
        assertTrue(theirTotal.longValue() < everything);
    }

    @Test
    @DisplayName("a chart the caller has no permission for is not found, not forbidden")
    void unpermittedChartIsNotFound() {
        signInAs(tenant(0), "REPORTS_VIEW");
        // Behind BOOKINGS_VIEW, which this caller does not hold.
        assertThrows(ResourceNotFoundException.class, () -> service.draw("bookings-by-state"));
    }

    @Test
    @DisplayName("a key naming nothing is refused rather than guessed at")
    void unknownKeyIsRefused() {
        signInAs(tenant(0), "REPORTS_VIEW");
        assertThrows(ResourceNotFoundException.class, () -> service.draw("v_report_listings"));
        assertThrows(ResourceNotFoundException.class, () -> service.draw("../etc/passwd"));
        assertThrows(ResourceNotFoundException.class,
                () -> service.draw("listings-by-type; drop table properties"));
    }

    @Test
    @DisplayName("the list offers only what the caller may draw")
    void availableIsFiltered() {
        signInAs(tenant(0), "REPORTS_VIEW");
        var keys = service.available().stream().map(c -> c.get("key")).toList();

        assertTrue(keys.contains("listings-by-type"));
        assertFalse(keys.contains("bookings-by-state"),
                "a chart somebody may not draw should not be offered to them");
    }

    // ── the shape of what comes back ──────────────────────────────────────────

    @Test
    @DisplayName("a chart carries a sentence describing itself")
    void everyChartHasASummary() {
        listingFor(tenant(0), "Something to chart");
        signInAs(tenant(0), "REPORTS_VIEW");

        ChartData chart = service.draw("listings-by-type");

        /*
         * The summary is the caption, the accessible name and the data table's caption. Composed here so it
         * cannot drift from the numbers — a chart is never a picture with no text alternative.
         */
        assertNotNull(chart.summary());
        assertFalse(chart.summary().isBlank());
        assertTrue(chart.summary().contains("listings"),
                "the sentence should name its units: " + chart.summary());
    }

    @Test
    @DisplayName("an empty chart says so rather than returning bare axes")
    void emptyChartIsMarked() {
        // A tenant with nothing of its own to chart.
        signInAs(tenant(0), "REPORTS_VIEW", "BOOKINGS_VIEW");
        ChartData chart = service.draw("payments-by-month");

        if (chart.series().isEmpty()) {
            assertTrue(chart.empty());
            assertTrue(chart.summary().toLowerCase().contains("nothing"),
                    "an empty chart's caption should say why it is empty: " + chart.summary());
        }
    }

    @Test
    @DisplayName("series are zero-filled, so a line does not jump a gap it should cross flat")
    void seriesAlignWithLabels() {
        signInAs(tenant(0), "REPORTS_VIEW");
        ChartData chart = service.draw("listings-by-month");

        for (var series : chart.series()) {
            assertEquals(chart.labels().size(), series.values().size(),
                    "series '" + series.name() + "' does not line up with the labels, so every point "
                            + "after the first gap would be plotted against the wrong month");
        }
    }
}
