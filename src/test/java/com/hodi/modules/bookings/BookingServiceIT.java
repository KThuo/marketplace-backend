package com.hodi.modules.bookings;

import com.hodi.common.AppConstant;
import com.hodi.modules.properties.Property;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.bookings.BookingDtos.*;
import com.hodi.modules.developments.*;
import com.hodi.modules.payments.PaymentDtos.ReceiveRequest;
import com.hodi.modules.payments.PaymentRepository;
import com.hodi.modules.payments.PaymentService;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.users.User;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Booking a unit, and the money against it.
 *
 * <p>What is worth a test rather than a reading: the balance arithmetic, which comes out of a view and is the
 * figure a buyer is told; the boundary with the unit's own hold path, which is the only thing stopping two
 * writers from disagreeing about whether a unit is available; and the state machine, where a wrong transition
 * marks a unit sold with money still owed. The money itself — receiving and voiding — is
 * {@code PaymentServiceIT}'s.
 */
@SpringBootTest
@Transactional
class BookingServiceIT {

    @Autowired BookingService service;
    @Autowired PaymentService paymentService;
    @Autowired DevelopmentUnitService unitService;
    @Autowired UnitBookingRepository bookings;
    @Autowired PaymentRepository payments;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitRepository units;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired JdbcTemplate jdbc;
    /*
     * Needed because these tests move a date underneath JPA.
     *
     * `expires_at` is pushed into the past with plain SQL — which is what a fortnight passing looks like — and
     * the persistence context still holds the row it loaded a moment earlier. Without a clear, findById
     * returns that cached instance and isExpired() reads the old date, so the test fails for a reason that has
     * nothing to do with the behaviour.
     */
    @Autowired jakarta.persistence.EntityManager entityManager;

    /** Pushes a reservation's window into the past and drops the stale copy JPA is holding. */
    private void expire(String bookingHashId) {
        jdbc.update("update unit_bookings set expires_at = now() - interval '1 day' where id = ?",
                HashIdUtil.decodeId(bookingHashId));
        entityManager.clear();
    }

    private Long tenantId;
    private Development development;
    private DevelopmentUnitType typology;
    private Property unit;

    @BeforeEach
    void signInAndBuild() {
        tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        User user = User.builder().id(1L).username("booking-test").password("x")
                .email("b@example.invalid").firstName("Bo").lastName("Booker")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenantId).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("UNITS_MANAGE", "UNITS_SELL", "BOOKINGS_MANAGE", "PAYMENTS_RECEIVE", "PAYMENTS_VOID"),
                List.of(tenantId), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Bookable Heights").developmentType("APARTMENT").build());
        typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(new BigDecimal("9500000")).build());
        unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("B-1-01")
                .saleState(AppConstant.UNIT_AVAILABLE)
                .constructionStatus(AppConstant.BUILD_PLANNED).build());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private String devId() { return HashIdUtil.encodeId(development.getId()); }
    private String unitId() { return HashIdUtil.encodeId(unit.getId()); }

    private CreateBookingRequest booking(List<InstalmentLine> schedule) {
        return new CreateBookingRequest(unitId(), "Asha Mwangi", "+254712000111",
                "asha@example.invalid", "12345678", new BigDecimal("9500000"),
                new BigDecimal("950000"), AppConstant.PLAN_INSTALMENTS, 14, null, schedule);
    }

    // ── booking ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("booking a unit reserves it and puts the buyer on the unit for the table to read")
    void bookingReservesTheUnit() {
        BookingResponse saved = service.create(devId(), booking(null));

        assertNotNull(saved.id());
        assertEquals(AppConstant.BOOKING_RESERVED, saved.state());
        assertNotNull(saved.expiresAt(), "a reservation expires");
        assertFalse(saved.expired());
        assertTrue(saved.payReference().matches("[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}"),
                "the booking's own four-character code, on the response: " + saved.payReference());

        Property after = units.findById(unit.getId()).orElseThrow();
        assertEquals(AppConstant.UNIT_HELD, after.getSaleState(),
                "a reservation is a hold with a deadline, which is what HELD means here");
        assertEquals("Asha Mwangi", after.getBuyerName(),
                "cached on the unit, so a table does not join to a booking to say who has it");
    }

    @Test
    @DisplayName("a second booking on the same unit is refused, and names who has it")
    void secondBookingRefused() {
        service.create(devId(), booking(null));

        HodiException e = assertThrows(HodiException.class, () -> service.create(devId(),
                new CreateBookingRequest(unitId(), "Someone Else", "+254712000222", null, null,
                        null, null, null, null, null, null)));
        assertTrue(e.getMessage().contains("Asha Mwangi"), e.getMessage());
    }

    @Test
    @DisplayName("the unique index is what actually guarantees it, not the check")
    void indexRefusesASecondLiveBooking() {
        service.create(devId(), booking(null));

        /*
         * Inserted underneath the service, which is the only way to reach the state the check cannot prevent:
         * two requests arriving together both see an unbooked unit. If this insert succeeds, the guarantee is
         * a service-level check — and that check loses this race in production.
         *
         * Nothing is asserted after it. A failed statement aborts the surrounding Postgres transaction, so any
         * further query in this test would fail for that reason rather than for the reason under test — which
         * is how a passing assertion can end up proving nothing.
         */
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into unit_bookings (reference, development_id, property_id, tenant_id, buyer_name,
                    buyer_phone, state, currency, booked_on, expires_at, created_by)
                values (?, ?, ?, ?, 'Race Condition', '+254700000000', 'RESERVED', 'KES',
                        current_date, now() + interval '14 days', 'test')
                """, RrnGenerator.generate("BK"), development.getId(), unit.getId(), tenantId));
    }

    @Test
    @DisplayName("a cancelled booking frees the unit and lets it be booked again")
    void cancellingFreesTheUnit() {
        BookingResponse first = service.create(devId(), booking(null));
        service.cancel(devId(), first.id(), new CloseBookingRequest("The buyer changed their mind."));

        Property after = units.findById(unit.getId()).orElseThrow();
        assertEquals(AppConstant.UNIT_AVAILABLE, after.getSaleState());
        assertNull(after.getBuyerName(), "the previous buyer's name does not stay on a freed unit");

        BookingResponse second = service.create(devId(),
                new CreateBookingRequest(unitId(), "Brian Otieno", "+254712000333", null, null,
                        null, null, null, null, null, null));
        assertEquals(AppConstant.BOOKING_RESERVED, second.state(),
                "the partial index permits a second booking once the first is closed");
    }

    @Test
    @DisplayName("agreeing clears the expiry, because a commitment does not run out")
    void agreeingClearsTheExpiry() {
        BookingResponse saved = service.create(devId(), booking(null));
        BookingResponse agreed = service.agree(devId(), saved.id());

        assertEquals(AppConstant.BOOKING_AGREED, agreed.state());
        assertNull(agreed.expiresAt());
        assertNotNull(agreed.agreedAt());
        assertFalse(agreed.expired());
    }

    // ── the money ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the balance is the schedule less what was paid, from the view")
    void balanceComesOutOfTheView() {
        BookingResponse saved = service.create(devId(), booking(List.of(
                new InstalmentLine("Deposit", LocalDate.now().minusDays(30), new BigDecimal("950000")),
                new InstalmentLine("Second", LocalDate.now().plusDays(30), new BigDecimal("4275000")),
                new InstalmentLine("Final", LocalDate.now().plusDays(90), new BigDecimal("4275000")))));

        paymentService.receive(new ReceiveRequest(saved.id(), new BigDecimal("950000"),
                LocalDate.now().minusDays(28), AppConstant.PAY_CHEQUE, null,
                "A7K2", "FT2609281234", "Asha Mwangi", "+254712000111", null));

        BookingResponse after = service.find(devId(), saved.id());
        assertEquals(0, after.scheduled().compareTo(new BigDecimal("9500000")));
        assertEquals(0, after.paid().compareTo(new BigDecimal("950000")));
        assertEquals(0, after.balance().compareTo(new BigDecimal("8550000")));
        assertEquals(0, after.overdue().compareTo(BigDecimal.ZERO),
                "the deposit was due and is paid, so nothing is behind");
    }

    @Test
    @DisplayName("what is overdue is what was due by today and unpaid, not the whole balance")
    void overdueIsWhatIsBehind() {
        BookingResponse saved = service.create(devId(), booking(List.of(
                new InstalmentLine("Deposit", LocalDate.now().minusDays(30), new BigDecimal("950000")),
                new InstalmentLine("Final", LocalDate.now().plusDays(90), new BigDecimal("8550000")))));

        BookingResponse after = service.find(devId(), saved.id());
        assertEquals(0, after.overdue().compareTo(new BigDecimal("950000")),
                "a buyer three years into a plan owes a lot and is behind on nothing");
        assertEquals(0, after.balance().compareTo(new BigDecimal("9500000")));
        assertEquals(LocalDate.now().plusDays(90), after.nextDueOn());
    }

    @Test
    @DisplayName("completing is refused while anything is outstanding, and says how much")
    void completingRefusedWithABalance() {
        BookingResponse saved = service.create(devId(), booking(List.of(
                new InstalmentLine("All of it", LocalDate.now(), new BigDecimal("9500000")))));

        HodiException e = assertThrows(HodiException.class, () -> service.complete(devId(), saved.id()));
        assertTrue(e.getMessage().contains("9500000"), e.getMessage());

        Property after = units.findById(unit.getId()).orElseThrow();
        assertEquals(AppConstant.UNIT_HELD, after.getSaleState(), "and the unit is not marked sold");
    }

    @Test
    @DisplayName("paid in full, completing marks the unit sold")
    void completingSellsTheUnit() {
        BookingResponse saved = service.create(devId(), booking(List.of(
                new InstalmentLine("All of it", LocalDate.now(), new BigDecimal("9500000")))));
        paymentService.receive(new ReceiveRequest(saved.id(), new BigDecimal("9500000"), null,
                AppConstant.PAY_CHEQUE, null, "A7K2", null, null, null, null));

        BookingResponse done = service.complete(devId(), saved.id());
        assertEquals(AppConstant.BOOKING_COMPLETED, done.state());

        Property after = units.findById(unit.getId()).orElseThrow();
        assertEquals(AppConstant.UNIT_SOLD, after.getSaleState());
        assertEquals(0, after.getSoldPrice().compareTo(new BigDecimal("9500000")));
    }

    // ── the schedule ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("rescheduling writes a new plan and leaves the agreed one readable")
    void reschedulingKeepsTheOldPlan() {
        BookingResponse saved = service.create(devId(), booking(List.of(
                new InstalmentLine("March", LocalDate.now().plusDays(10), new BigDecimal("9500000")))));

        List<InstalmentResponse> revised = service.reschedule(devId(), saved.id(),
                new RescheduleRequest(List.of(
                        new InstalmentLine("June", LocalDate.now().plusDays(100),
                                new BigDecimal("4750000")),
                        new InstalmentLine("September", LocalDate.now().plusDays(190),
                                new BigDecimal("4750000"))),
                        "The buyer's financing was delayed."));

        assertEquals(2, revised.size());
        assertEquals(2, revised.getFirst().planNo(), "the current plan is the new one");

        BookingResponse after = service.find(devId(), saved.id());
        assertEquals(0, after.scheduled().compareTo(new BigDecimal("9500000")),
                "and the total counts one plan, not both — or every figure would double");

        Integer plans = jdbc.queryForObject(
                "select count(distinct plan_no) from booking_instalments where booking_id = ?",
                Integer.class, HashIdUtil.decodeId(saved.id()));
        assertEquals(2, plans, "what was agreed in March is still on the record");
    }

    // ── the boundary with the unit's own hold path ────────────────────────────

    @Test
    @DisplayName("a booked unit cannot be held, sold or released through the unit path")
    void unitPathRefusesABookedUnit() {
        BookingResponse saved = service.create(devId(), booking(null));

        /*
         * The whole point of the boundary. Two writers of a unit's sale state is how a unit ends up sold with
         * no booking, or released while a booking still claims it — and both would look correct from the
         * screen that did it.
         */
        HodiException held = assertThrows(HodiException.class, () -> unitService.reserve(
                devId(), unitId(), new DevelopmentUnitDtos.ReserveUnitRequest(
                        "Interloper", null, null, null, null)));
        assertTrue(held.getMessage().contains(saved.reference()), held.getMessage());

        assertThrows(HodiException.class, () -> unitService.release(devId(), unitId()));
        assertThrows(HodiException.class, () -> unitService.sell(devId(), unitId(),
                new DevelopmentUnitDtos.SellUnitRequest("Interloper", null, null,
                        new BigDecimal("9500000"), null)));
    }

    @Test
    @DisplayName("once the booking is closed the unit path works again")
    void unitPathWorksOnceFreed() {
        BookingResponse saved = service.create(devId(), booking(null));
        service.cancel(devId(), saved.id(), new CloseBookingRequest("Withdrawn."));

        var held = unitService.reserve(devId(), unitId(), new DevelopmentUnitDtos.ReserveUnitRequest(
                "Direct Hold", "+254712000444", null, 7, null));
        assertEquals(AppConstant.UNIT_RESERVED, held.saleState());
    }

    // ── expiry ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an expired reservation reads as expired before any sweep has run")
    void expiryIsDerivedNotSwept() {
        BookingResponse saved = service.create(devId(), booking(null));
        expire(saved.id());

        UnitBooking reloaded = bookings.findById(HashIdUtil.decodeId(saved.id())).orElseThrow();
        assertTrue(reloaded.isExpired(),
                "correctness must not wait on a scheduler — a missed cron would sell a unit twice");
        assertEquals(AppConstant.BOOKING_RESERVED, reloaded.getState(),
                "and the recorded state is still reserved until the sweep says otherwise");
    }

    /*
     * The two sweep tests live in BookingExpiryIT, without @Transactional.
     *
     * lapseOne runs REQUIRES_NEW — deliberately, so one stuck booking cannot roll back a whole pass — which
     * means it takes a separate connection and cannot see this class's uncommitted rows. Called from here it
     * finds nothing and returns false, and an assertion that it returned false then passes while proving
     * nothing at all. That is worse than a failing test, and it is what the first draft of this file did.
     */
}
