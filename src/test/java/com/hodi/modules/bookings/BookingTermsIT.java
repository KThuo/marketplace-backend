package com.hodi.modules.bookings;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingPolicyService.BookingPolicy;
import com.hodi.modules.bookings.BookingTermsService.DeclineRequest;
import com.hodi.modules.bookings.BookingTermsService.TermsView;
import com.hodi.modules.developments.*;
import com.hodi.modules.payments.PaymentDtos.ReceiveRequest;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A booking is made under terms the buyer agreed to: presented with the booking, filled from the
 * development's policy, kept as version and figures, accepted or declined by the buyer or signed on paper —
 * and nothing is paid until then.
 */
@SpringBootTest
@Transactional
class BookingTermsIT {

    @Autowired BookingService bookings;
    @Autowired BookingTermsService terms;
    @Autowired BookingTermsRepository termsRows;
    @Autowired BookingPolicyService policies;
    @Autowired UnitBookingRepository rows;
    @Autowired PaymentService payments;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitRepository units;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private long buyer;
    private Development development;
    private Property unit;
    private Property second;

    @BeforeEach
    void build() {
        tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        buyer = jdbc.queryForObject("select id from users where status <> 5 order by id limit 1", Long.class);
        signInAsPlatform();
        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).tenantName("Terms Seller").sellingTenantId(tenantId)
                .name("Terms Heights").developmentType("APARTMENT")
                .refundPenaltyBasis(BookingPolicyService.BASIS_PERCENT_OF_PAID).refundPenaltyRate(new BigDecimal("5"))
                .refundPenaltyCap(new BigDecimal("100000")).reviveWithinDays(45)
                .bookingPolicyNote("Deposits are refundable less 5% within 90 days.")
                .build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(new BigDecimal("9500000")).build());
        unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("T-1-01")
                .saleState(AppConstant.UNIT_AVAILABLE).constructionStatus(AppConstant.BUILD_PLANNED).build());
        second = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("T-1-02")
                .saleState(AppConstant.UNIT_AVAILABLE).constructionStatus(AppConstant.BUILD_PLANNED).build());
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    @DisplayName("the terms are presented with the booking, filled from the development's policy, and nothing is paid until accepted")
    void presentedAcceptedThenPaid() {
        Long id = book(unit, buyer);

        BookingTerms row = termsRows.findCurrent(id).orElseThrow();
        assertEquals(1, row.getTemplateVersion());
        Map<String, Object> figures = row.getFigures();
        assertEquals("T-1-01, Terms Heights", figures.get("home"));
        assertEquals("PERCENT_OF_PAID", figures.get("penaltyBasis"));
        assertEquals("within 45 days", figures.get("reviveWindow"));
        assertTrue(String.valueOf(figures.get("penalty")).contains("5% of what you paid"), figures.get("penalty").toString());
        assertTrue(String.valueOf(figures.get("penalty")).contains("never more than KES 100000"));

        TermsView staffView = bookings.termsOf(hash(id));
        assertTrue(staffView.body().contains("T-1-01, Terms Heights"), "the page is filled");
        assertTrue(staffView.body().contains("Deposits are refundable less 5% within 90 days."));
        assertFalse(staffView.body().contains("{{"), "every placeholder was filled");
        assertTrue(staffView.mayRecordOnPaper());
        assertFalse(staffView.mayAccept(), "staff do not accept for the buyer");

        HodiException refused = assertThrows(HodiException.class, () -> payments.receive(new ReceiveRequest(
                hash(id), new BigDecimal("100000"), null, AppConstant.PAY_CASH, null, null, null, null, null, null)));
        assertTrue(refused.getMessage().contains("not accepted the booking terms"), refused.getMessage());

        signInAsBuyer(buyer);
        TermsView mine = bookings.myTerms(hash(id));
        assertTrue(mine.mayAccept() && mine.mayDecline());
        BookingResponse agreed = bookings.acceptTerms(hash(id));
        assertEquals(BookingTermsService.TERMS_ACCEPTED, agreed.termsState());
        assertEquals(BookingTerms.CHANNEL_PORTAL, termsRows.findCurrent(id).orElseThrow().getChannel());
        assertThrows(HodiException.class, () -> bookings.acceptTerms(hash(id)), "not twice");

        signInAsPlatform();
        payments.receive(new ReceiveRequest(hash(id), new BigDecimal("100000"), null, AppConstant.PAY_CASH,
                null, null, null, null, null, null));
        assertEquals(AppConstant.BOOKING_AGREED, rows.findById(id).orElseThrow().getState());
    }

    @Test
    @DisplayName("a buyer who declines closes the booking and releases the home, owing nothing")
    void declined() {
        Long id = book(unit, buyer);
        signInAsBuyer(buyer);
        BookingResponse closed = bookings.declineTerms(hash(id), new DeclineRequest("The penalty is too steep"));
        assertEquals(AppConstant.BOOKING_CANCELLED, closed.state());
        assertEquals(BookingTermsService.TERMS_DECLINED, closed.termsState());
        assertTrue(closed.closeReason().contains("Buyer declined the terms"));
        assertEquals(AppConstant.UNIT_AVAILABLE, units.findById(unit.getId()).orElseThrow().getSaleState());
        assertEquals("The penalty is too steep", termsRows.findCurrent(id).orElseThrow().getDeclineReason());
    }

    @Test
    @DisplayName("a walk-in buyer signs on paper: the form is required, goes into the vault, and unlocks payment")
    void signedOnPaper() {
        Long id = book(second, null);
        assertThrows(HodiException.class, () -> bookings.recordTermsOnPaper(hash(id),
                new MockMultipartFile("file", "", "application/pdf", new byte[0])), "a checkbox is not a signature");

        BookingResponse signed = bookings.recordTermsOnPaper(hash(id),
                new MockMultipartFile("file", "signed.pdf", "application/pdf", "%PDF signed".getBytes(StandardCharsets.UTF_8)));
        assertEquals(BookingTermsService.TERMS_ACCEPTED, signed.termsState());
        TermsView view = bookings.termsOf(hash(id));
        assertEquals(BookingTerms.CHANNEL_PAPER, view.channel());
        assertEquals("signed.pdf", view.documentName());
        assertEquals("%PDF signed", new String(bookings.signedTerms(hash(id)).bytes(), StandardCharsets.UTF_8));

        payments.receive(new ReceiveRequest(hash(id), new BigDecimal("50000"), null, AppConstant.PAY_CASH,
                null, null, null, null, null, null));

        // The buyer, once they have an account, sees what was signed and may confirm it.
        UnitBooking row = rows.findById(id).orElseThrow();
        row.setBuyerUserId(buyer);
        rows.saveAndFlush(row);
        signInAsBuyer(buyer);
        assertTrue(bookings.myTerms(hash(id)).mayConfirm());
        bookings.confirmTerms(hash(id));
        assertNotNull(bookings.myTerms(hash(id)).confirmedAt());
    }

    @Test
    @DisplayName("the policy is the development's where set and the platform's where not; the arithmetic follows the basis")
    void policyAndArithmetic() {
        BookingPolicy here = policies.policyFor(development);
        assertEquals(new BigDecimal("5"), here.penaltyRate());
        assertEquals(45, here.reviveWithinDays());
        assertEquals(0, new BigDecimal("50000.00").compareTo(here.penaltyOn(new BigDecimal("1000000"), null)), "5% of 1m");
        assertEquals(0, new BigDecimal("100000.00").compareTo(here.penaltyOn(new BigDecimal("5000000"), null)), "capped");

        BookingPolicy fixed = new BookingPolicy(BookingPolicyService.BASIS_FIXED, new BigDecimal("20000"), null,
                BigDecimal.ZERO, 0, 30, "");
        assertEquals(0, new BigDecimal("20000.00").compareTo(fixed.penaltyOn(new BigDecimal("1000000"), null)));
        assertEquals(0, new BigDecimal("15000.00").compareTo(fixed.penaltyOn(new BigDecimal("15000"), null)), "never above what was paid");

        BookingPolicy ofDeposit = new BookingPolicy(BookingPolicyService.BASIS_PERCENT_OF_DEPOSIT, new BigDecimal("10"), null,
                BigDecimal.ZERO, 0, 30, "");
        assertEquals(0, new BigDecimal("95000.00").compareTo(ofDeposit.penaltyOn(new BigDecimal("1000000"), new BigDecimal("950000"))));

        BookingPolicy platform = policies.platformDefaults();
        assertNotNull(platform.penaltyBasis());
        assertEquals(0, BigDecimal.ZERO.compareTo(platform.penaltyOn(new BigDecimal("1000000"), null)), "the platform keeps nothing by default");

        // A listing's page renders the same template from today's policy.
        TermsView forListing = terms.viewForListing(unit, development);
        assertTrue(forListing.body().contains("Terms Heights"));
        assertEquals(BookingTermsService.TERMS_NONE, forListing.state());
        assertFalse(forListing.body().contains("{{"));
    }

    @Test
    @DisplayName("a new template version is what new bookings are made under; old bookings keep theirs")
    void templateVersions() {
        Long before = book(unit, buyer);
        int v = terms.activeTemplate().version();
        terms.newTemplate(new BookingTermsService.NewTemplateRequest("## Short terms\n\nYou are booking {{home}} at {{price}}. {{penalty}}", "Shorter"));
        assertEquals(v + 1, terms.activeTemplate().version());
        Long after = book(second, buyer);
        assertEquals(v, termsRows.findCurrent(before).orElseThrow().getTemplateVersion());
        assertEquals(v + 1, termsRows.findCurrent(after).orElseThrow().getTemplateVersion());
        assertTrue(bookings.termsOf(hash(after)).body().startsWith("## Short terms"));
        assertFalse(bookings.termsOf(hash(before)).body().startsWith("## Short terms"), "the old booking reads its own version");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** A booking's raw id: hashes are salted per signed-in user, so tests keep the id and re-encode as whoever acts. */
    private Long book(Property home, Long buyerUserId) {
        signInAsPlatform();
        BookingResponse booking = bookings.create(HashIdUtil.encodeId(development.getId()), new CreateBookingRequest(
                HashIdUtil.encodeId(home.getId()), "Asha Mwangi", "+254712000111", "asha@example.invalid", null,
                new BigDecimal("9500000"), new BigDecimal("950000"), AppConstant.PLAN_INSTALMENTS, 14, null, List.of()));
        Long id = HashIdUtil.decodeId(booking.id());
        assertEquals(BookingTermsService.TERMS_PRESENTED, booking.termsState());
        if (buyerUserId != null) {
            UnitBooking row = rows.findById(id).orElseThrow();
            row.setBuyerUserId(buyerUserId);
            rows.saveAndFlush(row);
        }
        return id;
    }

    private static String hash(Long id) { return HashIdUtil.encodeId(id); }

    private void signInAsPlatform() {
        User user = User.builder().id(7L).username("terms-admin").password("x")
                .email("t@example.invalid").firstName("Terms").lastName("Admin")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(7L).userId(7L)
                .profileType(AppConstant.ACTOR_PLATFORM).userTypeCode("SUPER_ADMIN")
                .status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of("PAYMENTS_VIEW", "PAYMENTS_RECEIVE", "BOOKINGS_VIEW", "BOOKINGS_MANAGE",
                "UNITS_SELL", "UNITS_MANAGE", "APP_SETTINGS_VIEW", "APP_SETTINGS_UPDATE"), List.of(), true, true));
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
}
