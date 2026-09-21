package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CloseBookingRequest;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingService;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.developments.DevelopmentUnitRepository;
import com.hodi.modules.developments.DevelopmentUnitType;
import com.hodi.modules.developments.DevelopmentUnitTypeRepository;
import com.hodi.modules.payments.PayeeResolver.Resolution;
import com.hodi.modules.payments.PayeeResolver.Via;
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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What the payer quoted, resolved to the booking it names.
 *
 * <p>The resolver is the one place a bank's free text becomes a booking, for the notification, the biller's
 * validation and advice, and the CSV upload alike. What matters here is which of the three references it
 * understands, whether each needs a second fact to agree before money moves, and what it says when the
 * booking it finds is no longer live — because "cancelled" and "unknown" send the person working the queue
 * to different places.
 */
@SpringBootTest
@Transactional
class PayeeResolverIT {

    @Autowired PayeeResolver resolver;
    @Autowired BookingService bookings;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitRepository units;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired JdbcTemplate jdbc;

    private Development development;
    private Property unit;
    private BookingResponse booking;

    @BeforeEach
    void signInAndBuild() {
        Long tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        User user = User.builder().id(1L).username("resolver-test").password("x")
                .email("r@example.invalid").firstName("Res").lastName("Olver")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenantId).tenantName("Test Seller").status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("UNITS_MANAGE", "UNITS_SELL", "BOOKINGS_MANAGE", "PAYMENTS_RECEIVE"), List.of(tenantId), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Resolving Heights").developmentType("APARTMENT").build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(new BigDecimal("9500000")).build());
        unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("R-4-02")
                .saleState(AppConstant.UNIT_AVAILABLE).constructionStatus(AppConstant.BUILD_PLANNED).build());
        booking = bookings.create(HashIdUtil.encodeId(development.getId()), new CreateBookingRequest(
                HashIdUtil.encodeId(unit.getId()), "Asha Mwangi", "+254712345678", null, null,
                new BigDecimal("9500000"), new BigDecimal("950000"), null, 14, null, null));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the booking's own code resolves to it, however the payer wrote it, and asks for a second fact to agree")
    void theCodeNamesTheBooking() {
        for (String quoted : List.of(booking.payReference(), booking.payReference().toLowerCase(),
                "unit " + booking.payReference(), "  " + booking.payReference() + " ")) {
            Resolution match = resolver.resolve(quoted);
            assertTrue(match.found(), quoted + " → " + match.reason());
            assertEquals(booking.reference(), match.booking().getReference());
            assertEquals(unit.getId(), match.home().getId(), "the home comes with the booking, for the queue's wording");
            assertEquals(Via.PAY_CODE, match.via());
            assertTrue(match.needsCorroboration(), "four characters carry no redundancy");
        }
    }

    @Test
    @DisplayName("the booking reference and the listing reference resolve too, and need nothing else to agree")
    void theReferencesNameTheBooking() {
        Resolution byBooking = resolver.resolve("ref " + booking.reference() + " for Asha");
        assertTrue(byBooking.found(), byBooking.reason());
        assertEquals(Via.BOOKING_REFERENCE, byBooking.via());
        assertFalse(byBooking.needsCorroboration());

        Resolution byListing = resolver.resolve(unit.getReference());
        assertTrue(byListing.found(), byListing.reason());
        assertEquals(booking.reference(), byListing.booking().getReference(), "through the live booking on that home");
        assertEquals(Via.LISTING_REFERENCE, byListing.via());
        assertFalse(byListing.needsCorroboration());
    }

    @Test
    @DisplayName("a cancelled booking's code still finds it, and the answer says cancelled rather than unknown")
    void aClosedBookingIsFoundAndRefused() {
        bookings.cancel(HashIdUtil.encodeId(development.getId()), booking.id(),
                new CloseBookingRequest("The buyer changed their mind."));

        Resolution match = resolver.resolve(booking.payReference());

        assertFalse(match.found(), "nothing to credit");
        assertNull(match.booking());
        assertEquals(Via.PAY_CODE, match.via());
        assertNotNull(match.home(), "the home is still named, for the person reading the queue");
        assertTrue(match.reason().contains(booking.reference()), match.reason());
        assertTrue(match.reason().contains("cancelled"), match.reason());
    }

    @Test
    @DisplayName("once the home is booked again, the old code stays with the old buyer and the new code finds the new one")
    void twoBookingsTwoCodes() {
        bookings.cancel(HashIdUtil.encodeId(development.getId()), booking.id(), new CloseBookingRequest("Withdrawn."));
        BookingResponse second = bookings.create(HashIdUtil.encodeId(development.getId()), new CreateBookingRequest(
                HashIdUtil.encodeId(unit.getId()), "Brian Otieno", "+254712000333", null, null,
                null, null, null, null, null, null));

        Resolution old = resolver.resolve(booking.payReference());
        assertFalse(old.found(), "the first buyer's late payment does not land on the second buyer");
        assertTrue(old.reason().contains(booking.reference()), old.reason());

        Resolution current = resolver.resolve(second.payReference());
        assertTrue(current.found(), current.reason());
        assertEquals(second.reference(), current.booking().getReference());
    }

    @Test
    @DisplayName("a code nobody holds says so, in words that send the queue worker to the payer")
    void anUnknownCodeIsSaidToBeUnknown() {
        Resolution match = resolver.resolve("QQQQ");
        assertFalse(match.found());
        assertNull(match.via());
        assertTrue(match.reason().contains("No booking has the code \"QQQQ\""), match.reason());
    }
}
