package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.infra.coop.CoopStatement;
import com.hodi.infra.coop.CoopStatementRepository;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingService;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.developments.*;
import com.hodi.modules.payments.StatementDtos.SetAsideRequest;
import com.hodi.modules.payments.StatementDtos.SlipResult;
import com.hodi.modules.payments.StatementDtos.StatementResponse;
import com.hodi.modules.payments.StatementDtos.TakeSlipRequest;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A reference off a slip, looked up and applied.
 *
 * <p>What is worth a test: that only an unused credit is ever offered, and that a used one is still
 * <em>explained</em> — naming the booking and the receipt — because "not found" is what makes a clerk key
 * the same money twice; that the take is the bank's figure and produces exactly the payment an automatic
 * match would; and the two halves of who may — staff always, the buyer only by the setting and only for
 * their own booking, told less than staff are.
 */
@SpringBootTest
@Transactional
class SlipValidationIT {

    @Autowired SlipValidationService slips;
    @Autowired StatementService reconciliation;
    @Autowired PaymentAccountService accountService;
    @Autowired PaymentQueryService queries;
    @Autowired BookingService bookings;
    @Autowired UnitBookingRepository bookingRows;
    @Autowired PaymentRepository payments;
    @Autowired CoopStatementRepository statements;
    @Autowired PaymentAccountRepository accounts;
    @Autowired PaymentTypeRepository types;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitRepository units;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired ConfigurationService configs;
    @Autowired JdbcTemplate jdbc;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager em;

    /** A real user, because the booking's buyer column is a foreign key. Chosen per run from the table. */
    private long buyerUser;

    private Long tenantId;
    private Development development;
    private BookingResponse booking;
    /** The booking's raw id: a hash is encoded under the caller's own salt, so a buyer needs their own. */
    private Long bookingRaw;
    private PaymentAccount till;

    @BeforeEach
    void signInAndBuild() {
        tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        buyerUser = jdbc.queryForObject("select id from users where status <> 5 order by id limit 1", Long.class);
        signInAsPlatform();
        jdbc.update("update payment_types set status = 1 where provider_type = 'BUNI_IPN_TILL'");

        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Slip Heights").developmentType("APARTMENT").build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(new BigDecimal("9500000")).build());
        Property unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("S-2-02")
                .payReference("S2K2")
                .saleState(AppConstant.UNIT_AVAILABLE)
                .constructionStatus(AppConstant.BUILD_PLANNED).build());
        booking = bookings.create(HashIdUtil.encodeId(development.getId()), new CreateBookingRequest(
                HashIdUtil.encodeId(unit.getId()), "Asha Mwangi", "+254712000111", null, null,
                new BigDecimal("9500000"), new BigDecimal("950000"), null, 14, null, null));
        // The booking is this buyer's, by identity as well as by name.
        bookingRaw = HashIdUtil.decodeId(booking.id());
        UnitBooking row = bookingRows.findById(bookingRaw).orElseThrow();
        row.setBuyerUserId(buyerUser);
        bookingRows.saveAndFlush(row);
        till = till(tenantId, null);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        // The row is rolled back with the test; the cache is not, so it is put back by hand.
        buyersMayValidate(false);
    }

    private void signInAsPlatform() {
        User user = User.builder().id(7L).username("slip-admin").password("x")
                .email("q@example.invalid").firstName("Slip").lastName("Admin")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(7L).userId(7L)
                .profileType(AppConstant.ACTOR_PLATFORM).userTypeCode("SUPER_ADMIN")
                .status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of("STATEMENTS_VIEW", "STATEMENTS_RECONCILE",
                "PAYMENTS_VIEW", "PAYMENTS_RECEIVE", "BOOKINGS_MANAGE", "UNITS_SELL", "UNITS_MANAGE"),
                List.of(), true, true));
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

    /** Flips {@code payments.buyer.slip.validation} and evicts the caches that would hide the change. */
    private void buyersMayValidate(boolean may) {
        jdbc.update("update configurations set config_value = ? where config_key = ?",
                may ? "true" : "false", "payments.buyer.slip.validation");
        em.flush();
        em.clear();
        configs.evictAll();
    }

    private PaymentAccount till(Long tenant, Long developmentId) {
        PaymentType channel = types.findByProviderType("BUNI_IPN_TILL").orElseThrow();
        PaymentAccount row = PaymentAccount.builder()
                .accountNo("ST" + Long.toString(System.nanoTime(), 36).toUpperCase()).accountName("Slip till")
                .tenantId(tenant).developmentId(developmentId).createdBy("test").build();
        row.stampChannel(channel);
        return accounts.save(row);
    }

    private CoopStatement unused(PaymentAccount account, String quoted, String amount) {
        return statements.saveAndFlush(CoopStatement.builder()
                .refNo("SLIP" + RrnGenerator.generate("RF")).ourReference(RrnGenerator.generate("PS"))
                .transType("BUNI_IPN_TILL").paymentAccountId(account.getId())
                .accountIdentifier(account.getAccountNo())
                .reference(quoted).amount(new BigDecimal(amount)).phoneNo("254700000000")
                .customerName("Asha Mwangi").paidAt(OffsetDateTime.now().minusHours(3))
                .tenantId(account.getTenantId()).institutionId(account.getInstitutionId())
                .state(AppConstant.STATEMENT_UNMAPPED)
                .unmappedReason("No unit has the code \"" + quoted + "\".")
                .createdBy("system").build());
    }

    // ── the lookup ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("too short, and never seen, are each said plainly")
    void tooShortAndUnknown() {
        SlipResult short_ = slips.validate("AB12", booking.id());
        assertFalse(short_.valid());
        assertTrue(short_.message().contains("six characters"), short_.message());

        SlipResult unknown = slips.validate("NEVERSEEN99", booking.id());
        assertFalse(unknown.valid());
        assertTrue(unknown.message().contains("has reached us yet"), unknown.message());
    }

    @Test
    @DisplayName("an unused credit is found by the bank's reference or by the payer's words, with the bank's figures")
    void anUnusedCreditIsFoundAndDescribed() {
        CoopStatement credit = unused(till, "REF-S2K2-JULY", "125000");

        SlipResult byBankRef = slips.validate(" " + credit.getRefNo().toLowerCase() + " ", booking.id());
        assertTrue(byBankRef.valid(), byBankRef.message());
        assertEquals(0, byBankRef.amount().compareTo(new BigDecimal("125000")), "the bank's figure");
        assertEquals("KCB Till", byBankRef.paymentTypeName(), "the channel it came through");
        assertEquals("Asha Mwangi", byBankRef.payerName());
        assertEquals("REF-S2K2-JULY", byBankRef.quoted());
        assertEquals(HashIdUtil.encodeId(credit.getId()), byBankRef.statementId());
        assertTrue(byBankRef.message().contains("KCB Till"), byBankRef.message());

        SlipResult byQuoted = slips.validate("ref-s2k2-july", booking.id());
        assertTrue(byQuoted.valid(), "what the payer typed is a reference too");
        assertEquals(byBankRef.statementId(), byQuoted.statementId());
    }

    // ── the take ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("taking the slip applies the bank's figure once, and the reference then says where it went")
    void takeAppliesOnceAndThenExplainsItself() {
        CoopStatement credit = unused(till, "S2K2", "950000");

        StatementResponse applied = slips.take(new TakeSlipRequest(booking.id(), credit.getRefNo()));

        assertEquals("Used", applied.stateLabel());
        assertEquals(booking.reference(), applied.bookingReference());
        Payment payment = payments.findById(HashIdUtil.decodeId(applied.paymentId())).orElseThrow();
        assertEquals(credit.getId(), payment.getStatementId());
        assertEquals(0, payment.getAmount().compareTo(new BigDecimal("950000")));
        assertEquals(0, queries.balanceOf(booking.id()).paid().compareTo(new BigDecimal("950000")));

        SlipResult again = slips.validate(credit.getRefNo(), booking.id());
        assertFalse(again.valid(), "a used credit is never offered again");
        assertTrue(again.message().contains("already been applied to this booking"), again.message());
        assertTrue(again.message().contains(applied.paymentReference()), "and names the receipt");

        HodiException twice = assertThrows(HodiException.class,
                () -> slips.take(new TakeSlipRequest(booking.id(), credit.getRefNo())));
        assertEquals(HttpStatus.CONFLICT, twice.getStatus());
        assertEquals(1, payments.findForBooking(HashIdUtil.decodeId(booking.id())).size());
    }

    @Test
    @DisplayName("staff are told what a set-aside or out-of-reach credit is; it is still not offered")
    void staffAreToldWhy() {
        CoopStatement aside = unused(till, "ASIDE1", "1000");
        reconciliation.setAside(HashIdUtil.encodeId(aside.getId()), new SetAsideRequest("Our own refund."));
        SlipResult setAside = slips.validate(aside.getRefNo(), booking.id());
        assertFalse(setAside.valid());
        assertTrue(setAside.message().contains("set aside"), setAside.message());
        assertTrue(setAside.message().contains("Our own refund."), setAside.message());

        Development other = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId)
                .name("Other Court").developmentType("APARTMENT").build());
        developments.flush();
        CoopStatement elsewhere = unused(till(tenantId, other.getId()), "S2K2", "950000");
        SlipResult outOfReach = slips.validate(elsewhere.getRefNo(), booking.id());
        assertFalse(outOfReach.valid());
        assertTrue(outOfReach.message().contains("does not collect for"), outOfReach.message());
        assertTrue(outOfReach.message().contains("Slip till"), "names the account it landed in");
    }

    // ── who may ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a buyer may only when the setting says so, only for their own booking, and is told less")
    void aBuyerBySettingAndIdentity() {
        CoopStatement mine = unused(till, "S2K2", "125000");

        signInAsBuyer(buyerUser);
        String asBuyer = HashIdUtil.encodeId(bookingRaw);
        HodiException off = assertThrows(HodiException.class, () -> slips.validate(mine.getRefNo(), asBuyer));
        assertEquals(HttpStatus.FORBIDDEN, off.getStatus());
        assertTrue(off.getMessage().contains("not switched on"), off.getMessage());

        buyersMayValidate(true);
        SlipResult found = slips.validate(mine.getRefNo(), asBuyer);
        assertTrue(found.valid(), found.message());
        StatementResponse applied = slips.take(new TakeSlipRequest(asBuyer, mine.getRefNo()));
        assertEquals(booking.reference(), applied.bookingReference());

        // Somebody else's booking is not found for them, whatever the setting says.
        signInAsBuyer(buyerUser + 100_000);
        String asStranger = HashIdUtil.encodeId(bookingRaw);
        assertThrows(RuntimeException.class, () -> slips.validate(mine.getRefNo(), asStranger));

        // Back as the buyer: a used credit says it was used, but not where it went.
        signInAsPlatform();
        CoopStatement other = unused(till, "S2K2", "50000");
        Development otherDevelopment = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Other Slip Court").developmentType("APARTMENT").build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(otherDevelopment.getId())
                .code("1B").name("One bedroom").propertyType("APARTMENT").bedrooms((short) 1)
                .listPrice(new BigDecimal("5000000")).build());
        Property otherUnit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(otherDevelopment.getId())
                .unitTypeId(typology.getId()).unitLabel("O-1-01").payReference("O1K1")
                .saleState(AppConstant.UNIT_AVAILABLE).constructionStatus(AppConstant.BUILD_PLANNED).build());
        BookingResponse otherBooking = bookings.create(HashIdUtil.encodeId(otherDevelopment.getId()),
                new CreateBookingRequest(HashIdUtil.encodeId(otherUnit.getId()), "Someone Else",
                        "+254700000999", null, null, new BigDecimal("5000000"), new BigDecimal("500000"),
                        null, 14, null, null));
        slips.take(new TakeSlipRequest(otherBooking.id(), other.getRefNo()));

        signInAsBuyer(buyerUser);
        SlipResult used = slips.validate(other.getRefNo(), HashIdUtil.encodeId(bookingRaw));
        assertFalse(used.valid());
        assertEquals("That reference has already been used.", used.message(),
                "whose booking it went to is not the buyer's to learn");
    }

    @Test
    @DisplayName("an inbound channel is offered as slip validation to staff, and to a buyer only by the setting")
    void offeredAsSlip() {
        var forStaff = accountService.offered(booking.id());
        assertTrue(forStaff.stream().anyMatch(a -> "VALIDATE".equals(a.renderAs())),
                "the till is offered as a slip to find, not as a way to pay here");
        assertTrue(forStaff.stream().noneMatch(a -> "TRANSFER".equals(a.renderAs())));
    }
}
