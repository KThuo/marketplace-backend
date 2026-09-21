package com.hodi.modules.bookings;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingDtos.InstalmentLine;
import com.hodi.modules.developments.*;
import com.hodi.modules.payments.PaymentAccountService;
import com.hodi.modules.payments.PaymentDtos.ReceiveRequest;
import com.hodi.modules.payments.PaymentIntentService;
import com.hodi.modules.payments.PaymentQueryService;
import com.hodi.modules.payments.PaymentService;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.properties.Property;
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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A buyer's own bookings, reached by identity.
 *
 * <p>What is worth a test: that a buyer sees the booking that carries their user id and not one that
 * carries somebody else's, however alike the names; that what they see carries the balance and the
 * receipts; and that what they are offered to pay with is the phone prompt and never cash, whatever
 * accounts the seller has configured.
 */
@SpringBootTest
@Transactional
class MyBookingsIT {

    @Autowired BookingService bookings;
    @Autowired UnitBookingRepository rows;
    @Autowired PaymentService payments;
    @Autowired PaymentQueryService queries;
    @Autowired PaymentIntentService intents;
    @Autowired PaymentAccountService accounts;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitRepository units;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private long buyer;
    private long stranger;
    private Long bookingRaw;
    private BookingResponse booking;

    @BeforeEach
    void build() {
        tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        List<Long> users = jdbc.queryForList("select id from users where status <> 5 order by id limit 2", Long.class);
        buyer = users.get(0);
        stranger = users.size() > 1 ? users.get(1) : buyer + 100_000;
        signInAsPlatform();

        Development development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Mine Heights").developmentType("APARTMENT").build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(new BigDecimal("9500000")).build());
        Property unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("M-1-01")
                .saleState(AppConstant.UNIT_AVAILABLE).constructionStatus(AppConstant.BUILD_PLANNED).build());
        booking = bookings.create(HashIdUtil.encodeId(development.getId()), new CreateBookingRequest(
                HashIdUtil.encodeId(unit.getId()), "Asha Mwangi", "+254712000111", null, null,
                new BigDecimal("9500000"), new BigDecimal("950000"), AppConstant.PLAN_INSTALMENTS, 14, null,
                List.of(new InstalmentLine("Deposit", LocalDate.now().minusDays(10), new BigDecimal("950000")),
                        new InstalmentLine("Balance", LocalDate.now().plusDays(90), new BigDecimal("8550000")))));
        bookingRaw = HashIdUtil.decodeId(booking.id());
        UnitBooking row = rows.findById(bookingRaw).orElseThrow();
        row.setBuyerUserId(buyer);
        rows.saveAndFlush(row);
        payments.receive(new ReceiveRequest(booking.id(), new BigDecimal("100000"), null, AppConstant.PAY_CASH,
                null, null, null, null, null, null));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void signInAsPlatform() {
        User user = User.builder().id(7L).username("mine-admin").password("x")
                .email("m@example.invalid").firstName("Mine").lastName("Admin")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(7L).userId(7L)
                .profileType(AppConstant.ACTOR_PLATFORM).userTypeCode("SUPER_ADMIN")
                .status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of("PAYMENTS_VIEW", "PAYMENTS_RECEIVE", "BOOKINGS_MANAGE",
                "UNITS_SELL", "UNITS_MANAGE"), List.of(), true, true));
    }

    private void signInAsBuyer(long userId) {
        User user = User.builder().id(userId).username("buyer-" + userId).password("x")
                .email("b" + userId + "@example.invalid").firstName("Asha").lastName("Mwangi")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(userId).userId(userId)
                .profileType(AppConstant.ACTOR_BUYER).userTypeCode("BUYER")
                .status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of(), List.of(), false, true));
    }

    private static void signIn(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @Test
    @DisplayName("a buyer sees the booking that carries their id, with what it owes and what it has paid")
    void mineByIdentity() {
        signInAsBuyer(buyer);
        List<BookingResponse> mine = bookings.mine();
        assertEquals(1, mine.stream().filter(b -> b.reference().equals(booking.reference())).count());
        BookingResponse it = mine.stream().filter(b -> b.reference().equals(booking.reference())).findFirst().orElseThrow();
        assertEquals(0, it.paid().compareTo(new BigDecimal("100000")));
        assertEquals(0, it.balance().compareTo(new BigDecimal("9400000")));
        assertEquals(0, it.overdue().compareTo(new BigDecimal("850000")), "the deposit less what has arrived");

        String hash = HashIdUtil.encodeId(bookingRaw);
        assertEquals(1, queries.forBooking(bookings.requireMine(hash).getId()).size(), "the receipt");
        assertEquals(2, bookings.mySchedule(hash).size());
        // The history of attempts is the sales office's: a buyer follows the one prompt they sent, no more.
        assertThrows(HodiException.class, () -> intents.forBooking(hash));

        signInAsBuyer(stranger);
        assertTrue(bookings.mine().stream().noneMatch(b -> b.reference().equals(booking.reference())),
                "the same name on the booking does not make it theirs");
        String asStranger = HashIdUtil.encodeId(bookingRaw);
        assertThrows(ResourceNotFoundException.class, () -> bookings.mine(asStranger));
    }

    @Test
    @DisplayName("a buyer is offered the phone prompt and never cash, however the seller is configured")
    void offeredToABuyer() {
        signInAsBuyer(buyer);
        var offered = accounts.offered(HashIdUtil.encodeId(bookingRaw));
        assertTrue(offered.stream().noneMatch(a -> "CASH".equals(a.renderAs()) || "CHEQUE".equals(a.renderAs())),
                "cash is somebody asserting money arrived, and the buyer is the somebody");
        assertTrue(offered.stream().noneMatch(a -> "TRANSFER".equals(a.renderAs())));
        assertTrue(offered.stream().noneMatch(a -> "VALIDATE".equals(a.renderAs())),
                "slip validation reaches a buyer only by the setting, which is off");

        signInAsBuyer(stranger);
        String asStranger = HashIdUtil.encodeId(bookingRaw);
        assertThrows(ResourceNotFoundException.class, () -> accounts.offered(asStranger));
    }
}
