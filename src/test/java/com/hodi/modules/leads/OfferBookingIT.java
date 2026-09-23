package com.hodi.modules.leads;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.bookings.BookingDtos.InstalmentLine;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.developments.DevelopmentUnitRepository;
import com.hodi.modules.developments.DevelopmentUnitType;
import com.hodi.modules.developments.DevelopmentUnitTypeRepository;
import com.hodi.modules.leads.LeadDtos.BookFromOfferRequest;
import com.hodi.modules.leads.LeadDtos.OfferResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An accepted offer becomes a booking — the step the offer funnel used to end without.
 *
 * <p>What is worth protecting: the booking is the offer's buyer and figure, not a retyped copy; the buyer's
 * own account is on it so they can pay from their bookings; the offer remembers which booking it became and
 * cannot become a second one; and nothing but an accepted offer converts.
 */
@SpringBootTest
@Transactional
class OfferBookingIT {

    @Autowired PurchaseRequestService offers;
    @Autowired PurchaseRequestRepository offerRows;
    @Autowired UnitBookingRepository bookings;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitRepository units;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private long buyerUser;
    private Property unit;

    @BeforeEach
    void build() {
        tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        buyerUser = jdbc.queryForObject("select id from users where status <> 5 order by id limit 1", Long.class);
        signInAsPlatform();

        Development development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Offer Court").developmentType("APARTMENT").build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("1B").name("One bedroom").propertyType("APARTMENT").bedrooms((short) 1)
                .listPrice(new BigDecimal("6500000")).build());
        unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit O-1-01")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("O-1-01")
                .tenantId(tenantId)
                .saleState(AppConstant.UNIT_AVAILABLE).constructionStatus(AppConstant.BUILD_PLANNED).build());
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private void signInAsPlatform() {
        User user = User.builder().id(9L).username("offers-admin").password("x")
                .email("o@example.invalid").firstName("Offer").lastName("Admin")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(9L).userId(9L)
                .profileType(AppConstant.ACTOR_PLATFORM).userTypeCode("SUPER_ADMIN")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("PURCHASE_REQUESTS_VIEW", "PURCHASE_REQUESTS_DECIDE", "BOOKINGS_MANAGE", "BOOKINGS_VIEW",
                        "UNITS_SELL", "UNITS_MANAGE"), List.of(), true, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /** An offer on the unit, in the state asked for, as the buyer would have left it. */
    private PurchaseRequest offer(String state) {
        return offerRows.save(PurchaseRequest.builder()
                .reference("OF" + Long.toString(System.nanoTime(), 36).toUpperCase().substring(0, 8))
                .tenantId(tenantId).tenantName("Offer Court Sellers")
                .propertyId(unit.getId()).propertyReference(unit.getReference()).propertyTitle(unit.getTitle())
                .askingPrice(new BigDecimal("6500000"))
                .userId(buyerUser).buyerName("Asha Mwangi").buyerEmail("asha@example.invalid")
                .buyerPhone("+254712000111")
                .offerAmount(new BigDecimal("6200000")).depositAvailable(new BigDecimal("620000"))
                .buyerMessage("Can move in by December.")
                .state(state)
                // ck_purchase_decided: a decided state carries the moment it was decided.
                .decidedAt(AppConstant.PURCHASE_ACCEPTED.equals(state) || AppConstant.PURCHASE_DECLINED.equals(state)
                        ? OffsetDateTime.now() : null)
                .createdBy("buyer").build());
    }

    @Test
    @DisplayName("an accepted offer becomes a booking for its buyer, at its figure, linked to their account")
    void anAcceptedOfferBecomesABooking() {
        PurchaseRequest accepted = offer(AppConstant.PURCHASE_ACCEPTED);

        OfferResponse booked = offers.book(accepted.getReference(), null);
        assertEquals(booked.bookingReference(), offers.find(accepted.getReference()).bookingReference(),
                "the detail read shows the same offer, booking and all");

        assertNotNull(booked.bookingId());
        assertTrue(booked.bookingReference().startsWith("BK"), booked.bookingReference());
        UnitBooking booking = bookings.findById(HashIdUtil.decodeId(booked.bookingId())).orElseThrow();
        assertEquals("Asha Mwangi", booking.getBuyerName());
        assertEquals("+254712000111", booking.getBuyerPhone());
        assertEquals("asha@example.invalid", booking.getBuyerEmail());
        assertEquals(0, new BigDecimal("6200000").compareTo(booking.getPriceAgreed()), "the offer's figure, not the asking price");
        assertEquals(0, new BigDecimal("620000").compareTo(booking.getDepositDue()), "what the buyer said they had ready");
        assertEquals(buyerUser, booking.getBuyerUserId(), "their own account, so it is under their bookings and they can pay it");
        assertEquals(unit.getId(), booking.getPropertyId());
        assertEquals(AppConstant.BOOKING_RESERVED, booking.getState());
        assertTrue(booking.getNotes().contains(accepted.getReference()), booking.getNotes());

        PurchaseRequest after = offerRows.findById(accepted.getId()).orElseThrow();
        assertEquals(booking.getId(), after.getBookingId());
        assertEquals(AppConstant.PURCHASE_ACCEPTED, after.getState(), "still an accepted offer; the booking is the next thing");

        HodiException twice = assertThrows(HodiException.class, () -> offers.book(accepted.getReference(), null));
        assertEquals(HttpStatus.CONFLICT, twice.getStatus());
        assertTrue(twice.getMessage().contains(booking.getReference()), twice.getMessage());
    }

    @Test
    @DisplayName("the sales office may set the terms on top of the offer: price, deposit, plan, schedule")
    void termsMayBeSetOnTop() {
        PurchaseRequest accepted = offer(AppConstant.PURCHASE_ACCEPTED);

        OfferResponse booked = offers.book(accepted.getReference(), new BookFromOfferRequest(
                new BigDecimal("6300000"), new BigDecimal("630000"), AppConstant.PLAN_INSTALMENTS, 30,
                "Agreed on the phone", List.of(
                        new InstalmentLine("Deposit", LocalDate.now().plusDays(7), new BigDecimal("630000")),
                        new InstalmentLine("Balance", LocalDate.now().plusDays(90), new BigDecimal("5670000")))));

        UnitBooking booking = bookings.findById(HashIdUtil.decodeId(booked.bookingId())).orElseThrow();
        assertEquals(0, new BigDecimal("6300000").compareTo(booking.getPriceAgreed()));
        assertEquals(0, new BigDecimal("630000").compareTo(booking.getDepositDue()));
        assertEquals(AppConstant.PLAN_INSTALMENTS, booking.getPaymentPlan());
        assertEquals("Agreed on the phone", booking.getNotes());
        assertEquals(2, jdbc.queryForObject("select count(*) from booking_instalments where booking_id = ?",
                Long.class, booking.getId()));
    }

    @Test
    @DisplayName("only an accepted offer converts, and a home already booked refuses a second offer's booking")
    void onlyAcceptedAndOnlyOnce() {
        PurchaseRequest waiting = offer(AppConstant.PURCHASE_SUBMITTED);
        HodiException notYet = assertThrows(HodiException.class, () -> offers.book(waiting.getReference(), null));
        assertEquals(HttpStatus.CONFLICT, notYet.getStatus());
        assertTrue(notYet.getMessage().contains("accepted"), notYet.getMessage());

        PurchaseRequest declined = offer(AppConstant.PURCHASE_DECLINED);
        assertThrows(HodiException.class, () -> offers.book(declined.getReference(), null));

        // Two buyers accepted on the same unit — a mistake the booking rule catches, not this one.
        PurchaseRequest first = offer(AppConstant.PURCHASE_ACCEPTED);
        PurchaseRequest second = offer(AppConstant.PURCHASE_ACCEPTED);
        offers.book(first.getReference(), null);
        HodiException taken = assertThrows(HodiException.class, () -> offers.book(second.getReference(), null));
        assertTrue(taken.getMessage().contains("booked by"), taken.getMessage());
        assertNull(offerRows.findById(second.getId()).orElseThrow().getBookingId());
    }
}
