package com.hodi.modules.bookings;

import com.hodi.common.AppConstant;
import com.hodi.modules.properties.Property;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.developments.*;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The expiry sweep, which cannot be tested inside a transaction.
 *
 * <h2>Why this class commits</h2>
 *
 * <p>{@code BookingService.lapseOne} runs {@code REQUIRES_NEW} on purpose: one stuck booking must not roll
 * back a whole pass, and a self-invocation inside the service would not pass through the proxy at all. The
 * consequence is that it takes a separate connection and cannot see uncommitted rows — so called from a
 * {@code @Transactional} test it finds nothing, returns false, and any assertion built on that passes while
 * proving nothing.
 *
 * <p>So this class writes real rows and removes them afterwards. The cleanup is by id, in dependency order,
 * and it runs even when a test fails.
 */
@SpringBootTest
class BookingExpiryIT {

    @Autowired BookingService service;
    @Autowired UnitBookingRepository bookings;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitRepository units;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private Development development;
    private DevelopmentUnitType typology;
    private Property unit;

    @BeforeEach
    void signInAndBuild() {
        tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        User user = User.builder().id(1L).username("expiry-test").password("x")
                .email("e@example.invalid").firstName("Eve").lastName("Expiry")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenantId).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("UNITS_MANAGE", "UNITS_SELL", "BOOKINGS_MANAGE"), List.of(tenantId), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Expiring Heights").developmentType("APARTMENT").build());
        typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("1B").name("One bedroom").propertyType("APARTMENT").bedrooms((short) 1)
                .listPrice(new BigDecimal("8400000")).build());
        unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("A-2-04")
                .saleState(AppConstant.UNIT_AVAILABLE)
                .constructionStatus(AppConstant.BUILD_PLANNED).build());
    }

    /**
     * Removes exactly what this test made, in dependency order.
     *
     * <p>Hard deletes rather than the soft-delete the application uses: these are fixtures, not records, and a
     * status of 5 would leave them in every count on the dev database for good.
     */
    @AfterEach
    void cleanUp() {
        try {
            jdbc.update("delete from payments where booking_id in "
                    + "(select id from unit_bookings where development_id = ?)", development.getId());
            jdbc.update("delete from booking_instalments where booking_id in "
                    + "(select id from unit_bookings where development_id = ?)", development.getId());
            jdbc.update("delete from unit_bookings where development_id = ?", development.getId());
            jdbc.update("delete from properties where listing_kind = 'UNIT' and development_id = ?", development.getId());
            jdbc.update("delete from development_unit_types where development_id = ?", development.getId());
            jdbc.update("delete from developments where id = ?", development.getId());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private BookingResponse book() {
        return service.create(HashIdUtil.encodeId(development.getId()),
                new CreateBookingRequest(HashIdUtil.encodeId(unit.getId()), "Lapsing Buyer",
                        "+254712000999", null, null, new BigDecimal("8400000"), null, null, 14, null,
                        null));
    }

    /** What a fortnight passing looks like, without waiting one. */
    private void expire(String bookingHashId) {
        jdbc.update("update unit_bookings set expires_at = now() - interval '1 day' where id = ?",
                HashIdUtil.decodeId(bookingHashId));
    }

    @Test
    @DisplayName("the sweep closes an expired reservation and puts the unit back on the market")
    void sweepLapsesAndFrees() {
        BookingResponse saved = book();
        expire(saved.id());

        List<Long> due = service.findLapsedIds();
        assertTrue(due.contains(HashIdUtil.decodeId(saved.id())));
        assertTrue(service.lapseOne(HashIdUtil.decodeId(saved.id())));

        UnitBooking reloaded = bookings.findById(HashIdUtil.decodeId(saved.id())).orElseThrow();
        assertEquals(AppConstant.BOOKING_LAPSED, reloaded.getState(),
                "lapsed, not cancelled — a clock is not a decision, and keeping them apart is the only way "
                        + "to answer how many bookings a hold policy loses");
        assertNotNull(reloaded.getClosedAt());
        assertEquals(AppConstant.UNIT_AVAILABLE,
                units.findById(unit.getId()).orElseThrow().getSaleState());
    }

    @Test
    @DisplayName("the sweep skips a booking somebody agreed in the meantime")
    void sweepSkipsAnAgreedBooking() {
        BookingResponse saved = book();
        expire(saved.id());
        List<Long> due = service.findLapsedIds();
        assertTrue(due.contains(HashIdUtil.decodeId(saved.id())));

        service.agree(HashIdUtil.encodeId(development.getId()), saved.id());

        assertFalse(service.lapseOne(HashIdUtil.decodeId(saved.id())),
                "a race with a correct outcome, not an error — the query ran before the agreement");
        assertEquals(AppConstant.BOOKING_AGREED,
                bookings.findById(HashIdUtil.decodeId(saved.id())).orElseThrow().getState());
        assertEquals(AppConstant.UNIT_RESERVED,
                units.findById(unit.getId()).orElseThrow().getSaleState(),
                "and the committed unit is RESERVED with no deadline, not HELD");
    }
}
