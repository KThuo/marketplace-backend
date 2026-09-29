package com.hodi.modules.bookings;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingDtos.MarkSoldRequest;
import com.hodi.modules.developments.DevelopmentUnitRepository;
import com.hodi.modules.payments.PaymentDtos.ReceiveRequest;
import com.hodi.modules.payments.PaymentService;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyService;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A house is booked, paid for and sold the way a unit is.
 *
 * <p>This is the other half of "one source": a house used to be sold by a flag with no buyer, no price and
 * nowhere for money to land. Now its sale is a booking on its property row, and the same balance view that
 * refuses to complete a unit with money outstanding refuses a house.
 */
@SpringBootTest
@Transactional
class HouseBookingIT {

    @Autowired BookingService bookings;
    @Autowired UnitBookingRepository bookingRows;
    @Autowired PaymentService payments;
    @Autowired PropertyService properties;
    @Autowired DevelopmentUnitRepository rows;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private Long otherTenantId;
    private Property house;

    @BeforeEach
    void build() {
        List<Long> tenants = jdbc.queryForList("select id from tenants where status <> 5 order by id limit 2", Long.class);
        tenantId = tenants.getFirst();
        otherTenantId = tenants.get(1);
        house = rows.save(Property.builder()
                .tenantId(tenantId).reference(RrnGenerator.generate("PR")).title("4-bed maisonette, Karen")
                .description("A house.").propertyType("HOUSE").listingType(AppConstant.LISTING_TYPE_SALE)
                .price(new BigDecimal("32000000")).county("Nairobi").town("Nairobi")
                .listingState(AppConstant.LISTING_LIVE).publishedAt(OffsetDateTime.now()).build());
        signInAsSeller(tenantId);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signInAsSeller(Long tenant) {
        User user = User.builder().id(1L).username("house-seller").password("x").email("h@example.invalid")
                .firstName("Hal").lastName("House").status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L).profileType(AppConstant.ACTOR_SELLER)
                .userTypeCode("SELLER_OWNER").tenantId(tenant).tenantName("Seller").status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("BOOKINGS_VIEW", "BOOKINGS_MANAGE", "PAYMENTS_RECEIVE", "PROPERTIES_MARK_SOLD"),
                List.of(tenant), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private String hash() {
        return HashIdUtil.encodeId(house.getId());
    }

    private CreateBookingRequest booking(BigDecimal price) {
        return new CreateBookingRequest(null, "Ada Buyer", "+254700000010", null, null, price, null,
                AppConstant.PLAN_LUMP_SUM, 14, null, null);
    }

    @Test
    @DisplayName("a house is booked on its own row, paid for, and completes to SOLD")
    void bookPayComplete() {
        BookingResponse booked = bookings.createForProperty(hash(), booking(new BigDecimal("30000000")));
        agree(booked);
        assertEquals(AppConstant.BOOKING_RESERVED, booked.state());
        assertNull(booked.developmentName(), "a house has no project");
        assertEquals("4-bed maisonette, Karen", booked.propertyTitle());
        assertEquals(AppConstant.LISTING_KIND_HOUSE, booked.listingKind());

        Property held = rows.findById(house.getId()).orElseThrow();
        assertEquals(AppConstant.UNIT_HELD, held.getSaleState());
        assertEquals(AppConstant.LISTING_LIVE, held.getListingState(), "a held house stays on the marketplace");
        assertEquals("Ada Buyer", held.getBuyerName());

        // A second booking on the same house is refused, by the check and by the index behind it.
        assertThrows(HodiException.class, () -> bookings.createForProperty(hash(), booking(null)));

        // Completing with money outstanding is refused; paying it clears the way.
        assertThrows(HodiException.class, () -> bookings.complete(booked.id()));
        payments.receive(new ReceiveRequest(booked.id(), new BigDecimal("30000000"), LocalDate.now(),
                AppConstant.PAY_CHEQUE, null, null, "TRF-1", null, null, null));
        BookingResponse done = bookings.complete(booked.id());
        assertEquals(AppConstant.BOOKING_COMPLETED, done.state());
        assertEquals(0, new BigDecimal("30000000").compareTo(done.paid()));

        Property sold = rows.findById(house.getId()).orElseThrow();
        assertEquals(AppConstant.LISTING_SOLD, sold.getListingState());
        assertEquals(AppConstant.UNIT_SOLD, sold.getSaleState());
        assertEquals(0, new BigDecimal("30000000").compareTo(sold.getSoldPrice()));
        assertNotNull(sold.getSoldAt());

        // The booking is reachable by its own id, and the payments hang off it.
        assertEquals(1, bookings.paymentsFor(done.id()).size());
        assertEquals(1, bookings.forProperty(hash()).size());
    }

    @Test
    @DisplayName("marking a house sold writes a completed booking rather than flipping a flag")
    void markSoldIsABooking() {
        properties.markSold(hash(), new MarkSoldRequest("Bea Buyer", "+254700000011", null, null, "Sold at the office"));

        List<BookingResponse> all = bookings.forProperty(hash());
        assertEquals(1, all.size());
        BookingResponse sale = all.getFirst();
        assertEquals(AppConstant.BOOKING_COMPLETED, sale.state());
        assertEquals("Bea Buyer", sale.buyerName());
        assertEquals(0, new BigDecimal("32000000").compareTo(sale.priceAgreed()), "the home's own price when none is given");

        Property sold = rows.findById(house.getId()).orElseThrow();
        assertEquals(AppConstant.LISTING_SOLD, sold.getListingState());
        assertEquals(AppConstant.UNIT_SOLD, sold.getSaleState());

        // Sold twice is a conflict, not a second booking.
        assertThrows(HodiException.class, () -> bookings.createForProperty(hash(), booking(null)));
    }

    @Test
    @DisplayName("a cancelled booking puts the house back on offer with the buyer's details gone")
    void cancelFrees() {
        BookingResponse booked = bookings.createForProperty(hash(), booking(null));
        bookings.cancel(booked.id(), new BookingDtos.CloseBookingRequest("Changed their mind."));
        Property freed = rows.findById(house.getId()).orElseThrow();
        assertEquals(AppConstant.UNIT_AVAILABLE, freed.getSaleState());
        assertEquals(AppConstant.LISTING_LIVE, freed.getListingState());
        assertNull(freed.getBuyerName());
        assertNotNull(bookings.createForProperty(hash(), booking(null)));
    }

    @Test
    @DisplayName("another seller can neither see nor book this house")
    void otherSellersAreNotFound() {
        BookingResponse booked = bookings.createForProperty(hash(), booking(null));
        signInAsSeller(otherTenantId);
        assertThrows(ResourceNotFoundException.class, () -> bookings.createForProperty(hash(), booking(null)));
        assertThrows(ResourceNotFoundException.class, () -> bookings.find(booked.id()));
        assertThrows(ResourceNotFoundException.class, () -> bookings.forProperty(hash()));
    }

    /** The buyer accepted the terms: every fixture here is about what happens after that. */
    private void agree(BookingResponse b) {
        // Through the entity, not JDBC: the booking is already in the persistence context and would read stale.
        UnitBooking row = bookingRows.findById(HashIdUtil.decodeId(b.id())).orElseThrow();
        row.setTermsState(BookingTermsService.TERMS_ACCEPTED);
        bookingRows.saveAndFlush(row);
    }
}
