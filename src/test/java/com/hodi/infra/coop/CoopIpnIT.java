package com.hodi.infra.coop;

import com.hodi.common.AppConstant;
import com.hodi.modules.properties.Property;
import com.hodi.common.util.RrnGenerator;
import com.hodi.infra.coop.CoopIpnDtos.IpnPayload;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingService;
import com.hodi.modules.developments.*;
import com.hodi.modules.payments.PaymentAccount;
import com.hodi.modules.payments.PaymentAccountRepository;
import com.hodi.modules.payments.PaymentRepository;
import com.hodi.modules.payments.PaymentType;
import com.hodi.modules.payments.PaymentTypeRepository;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Taking in a payment Co-op says arrived.
 *
 * <h2>Why this class commits</h2>
 *
 * <p>{@code accept} is {@code REQUIRES_NEW} on purpose — the record of money arriving must survive a failure
 * while working out whose it is — so it takes a separate connection and cannot see uncommitted fixtures.
 * Inside a {@code @Transactional} test it would find no unit, no booking and no till, place nothing, and every
 * "it went to the queue" assertion would pass for the wrong reason.
 *
 * <h2>What is worth testing here</h2>
 *
 * <p>Idempotency, because Co-op retries anything it does not get a clean answer to and the same money arriving
 * twice must not be credited twice. The corroboration rule, because a four-character code has no redundancy
 * and one mistyped letter lands on another live unit about one time in two hundred. And the refusal to
 * auto-credit while unauthenticated, which is the only thing standing between a forged notification and
 * somebody's balance.
 */
@SpringBootTest
class CoopIpnIT {

    @Autowired CoopIpnService service;
    @Autowired BookingService bookings;
    @Autowired CoopStatementRepository statements;
    @Autowired PaymentAccountRepository accounts;
    @Autowired PaymentTypeRepository types;
    @Autowired PaymentRepository payments;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitRepository units;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.hodi.modules.configurations.ConfigurationService configs;

    private Long tenantId;
    private Development development;
    private Property unit;
    private PaymentAccount till;
    private BookingResponse booking;

    /** Unique per run, so two runs cannot collide on the till's account number. */
    private String account;

    @BeforeEach
    void signInAndBuild() {
        // A run that died before its own clean-up leaves a unit holding the code this one needs.
        purgeStaleFixtures();
        tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        User user = User.builder().id(1L).username("coop-test").password("x")
                .email("p@example.invalid").firstName("Pia").lastName("Co-op")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenantId).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("UNITS_MANAGE", "UNITS_SELL", "BOOKINGS_MANAGE", "PAYMENTS_RECEIVE"),
                List.of(tenantId), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Paying Heights").developmentType("APARTMENT").build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(new BigDecimal("9500000")).build());
        unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("C-3-07")
                .saleState(AppConstant.UNIT_AVAILABLE)
                .constructionStatus(AppConstant.BUILD_PLANNED).build());

        account = "TILL" + Long.toString(System.nanoTime(), 36).toUpperCase();
        PaymentType channel = types.findByProviderType("BUNI_IPN_TILL").orElseThrow();
        PaymentAccount row = PaymentAccount.builder()
                .accountNo(account).accountName("Seller's till")
                .tenantId(tenantId).createdBy("test").build();
        row.stampChannel(channel);
        till = accounts.save(row);

        booking = bookings.create(HashIdUtil.encodeId(development.getId()),
                new CreateBookingRequest(HashIdUtil.encodeId(unit.getId()), "Asha Mwangi",
                        "+254 712 345 678", null, null, new BigDecimal("9500000"),
                        new BigDecimal("950000"), null, 14, null, null));
        // The code a booking is given is random; this test quotes a known one, so it is pinned on the booking.
        jdbc.update("update unit_bookings set pay_reference = ? where reference = ?", "Z4XP", booking.reference());
    }

    /**
     * Removes whatever an earlier, interrupted run left behind under this class's own fixture names.
     *
     * <p>The statement and the payment point at each other, so the link is broken before either is deleted.
     */
    private void purgeStaleFixtures() {
        // A received gateway payment must keep its statement (ck_payment_statement), so the link is
        // broken from the statement's side: release it, then the payment can go, then the statement.
        jdbc.update("update coop_statements set state = 'UNMAPPED', mapped_payment_id = null, "
                + "mapped_booking_id = null, mapped_at = null where mapped_payment_id in "
                + "(select id from payments where development_id in "
                + "(select id from developments where name = 'Paying Heights'))");
        jdbc.update("delete from payments where development_id in "
                + "(select id from developments where name = 'Paying Heights')");
        jdbc.update("delete from coop_statements where account_identifier like 'TILL%'");
        jdbc.update("delete from booking_instalments where booking_id in (select id from unit_bookings "
                + "where development_id in (select id from developments where name = 'Paying Heights'))");
        jdbc.update("delete from unit_bookings where development_id in "
                + "(select id from developments where name = 'Paying Heights')");
        jdbc.update("delete from properties where listing_kind = 'UNIT' and development_id in "
                + "(select id from developments where name = 'Paying Heights')");
        jdbc.update("delete from development_unit_types where development_id in "
                + "(select id from developments where name = 'Paying Heights')");
        jdbc.update("delete from developments where name = 'Paying Heights'");
        jdbc.update("delete from payment_accounts where account_no like 'TILL%' and account_name = 'Seller''s till'");
    }

    @AfterEach
    void cleanUp() {
        try {
            // The payment names the statement and the statement names the payment: unlink, then delete.
            jdbc.update("update coop_statements set state = 'UNMAPPED', mapped_payment_id = null, "
                    + "mapped_booking_id = null, mapped_at = null where mapped_payment_id in "
                    + "(select id from payments where booking_id in "
                    + "(select id from unit_bookings where development_id = ?))", development.getId());
            jdbc.update("delete from payments where booking_id in "
                    + "(select id from unit_bookings where development_id = ?)", development.getId());
            jdbc.update("delete from coop_statements where payment_account_id = ? "
                    + "or account_identifier = ?", till.getId(), account);
            jdbc.update("delete from booking_instalments where booking_id in "
                    + "(select id from unit_bookings where development_id = ?)", development.getId());
            jdbc.update("delete from unit_bookings where development_id = ?", development.getId());
            jdbc.update("delete from properties where listing_kind = 'UNIT' and development_id = ?", development.getId());
            jdbc.update("delete from development_unit_types where development_id = ?", development.getId());
            jdbc.update("delete from developments where id = ?", development.getId());
            jdbc.update("delete from payment_accounts where id = ?", till.getId());
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private IpnPayload payload(String reference, String amount, String phone) {
        return new IpnPayload(RrnGenerator.generate("RF"), "PS-" + RrnGenerator.generate("TR"),
                "2026-08-27 10:30:00", amount, "KES", reference, "Asha Mwangi", phone, account,
                "BUNI_IPN_TILL");
    }

    // ── placed automatically ──────────────────────────────────────────────────

    @Test
    @DisplayName("the deposit amount corroborates the code, so the payment is placed")
    void amountCorroboratesTheCode() {
        CoopStatement stored = service.accept(payload("Z4XP", "950000.00", "254700000000"), true);

        assertEquals(AppConstant.STATEMENT_MAPPED, stored.getState(), stored.getUnmappedReason());
        assertNotNull(stored.getMappedPaymentId());
        assertEquals(0, payments.totalPaid(HashIdUtil.decodeId(booking.id()))
                .compareTo(new BigDecimal("950000")));
        assertEquals(tenantId, stored.getTenantId(), "whose money it is, from the till it landed in");
    }

    @Test
    @DisplayName("the buyer's own number corroborates it even when the amount is a part payment")
    void phoneCorroboratesTheCode() {
        // Co-op sends 254…; the booking holds "+254 712 345 678". Same phone, written three ways.
        CoopStatement stored = service.accept(payload("Z4XP", "125000.00", "254712345678"), true);

        assertEquals(AppConstant.STATEMENT_MAPPED, stored.getState(), stored.getUnmappedReason());
        assertEquals(0, payments.totalPaid(HashIdUtil.decodeId(booking.id()))
                .compareTo(new BigDecimal("125000")));
    }

    @Test
    @DisplayName("a reference with the payer's own words around it still finds the code")
    void referenceIsCleanedBeforeMatching() {
        CoopStatement stored = service.accept(payload("unit z4xp", "950000.00", "254700000000"), true);
        assertEquals(AppConstant.STATEMENT_MAPPED, stored.getState(), stored.getUnmappedReason());
    }

    @Test
    @DisplayName("the listing's own reference places the money on its own, whatever the amount or phone")
    void aListingReferencePlacesWithoutCorroboration() {
        /*
         * Sixteen characters from a 32-letter alphabet. One mistyped letter is not somebody else's listing,
         * it is nothing — so, unlike the four-character code, a match needs nothing else to agree with it.
         */
        CoopStatement stored = service.accept(payload("ref " + unit.getReference().toLowerCase(),
                "37500.00", "254700000000"), true);

        assertEquals(AppConstant.STATEMENT_MAPPED, stored.getState(), stored.getUnmappedReason());
        assertEquals(HashIdUtil.decodeId(booking.id()), stored.getMappedBookingId());
        assertEquals(0, payments.totalPaid(HashIdUtil.decodeId(booking.id()))
                .compareTo(new BigDecimal("37500")));
    }

    // ── sent to the queue ─────────────────────────────────────────────────────

    /** The institution's choice of what a code must agree with; put back by every test that changes it. */
    private void codeMatch(String rule) {
        jdbc.update("update configurations set config_value = ? where config_key = 'payments.code.match'", rule);
        configs.evictAll();
    }

    @Test
    @DisplayName("under CODE_AND_CONTACT a code match on its own is not enough, and the reason says why")
    void bareCodeMatchGoesToTheQueueWhenContactIsRequired() {
        codeMatch("CODE_AND_CONTACT");
        try {
            // Four characters carry no redundancy, so one mistyped letter produces another well-formed code;
            // an institution that would rather a person looked first can say so.
            CoopStatement stored = service.accept(payload("Z4XP", "37500.00", "254700000000"), true);

            assertEquals(AppConstant.STATEMENT_UNMAPPED, stored.getState());
            assertTrue(stored.getUnmappedReason().contains("C-3-07"), stored.getUnmappedReason());
            assertTrue(stored.getUnmappedReason().contains(booking.reference()),
                    "and it names the booking, so a person can go and check it");
            assertEquals(0, payments.totalPaid(HashIdUtil.decodeId(booking.id()))
                    .compareTo(BigDecimal.ZERO));
        } finally {
            codeMatch("CODE");
        }
    }

    @Test
    @DisplayName("under CODE, the default, the code alone places the money: any phone, any amount")
    void theCodeAloneIsEnoughByDefault() {
        codeMatch("CODE");
        // A stranger's phone and an amount that is neither the deposit nor the price.
        CoopStatement stored = service.accept(payload("Z4XP", "37500.00", "254700000000"), true);

        assertEquals(AppConstant.STATEMENT_MAPPED, stored.getState(), stored.getUnmappedReason());
        assertEquals(HashIdUtil.decodeId(booking.id()), stored.getMappedBookingId());
        assertEquals(0, payments.totalPaid(HashIdUtil.decodeId(booking.id()))
                .compareTo(new BigDecimal("37500.00")));
    }

    /** A notification into an account nobody has registered yet, quoting this code. */
    private IpnPayload intoUnknownAccount(String unknownAccount, String reference) {
        return new IpnPayload(RrnGenerator.generate("RF"), "PS-" + RrnGenerator.generate("TR"),
                "2026-08-27 10:30:00", "950000.00", "KES", reference, "Asha Mwangi", "254700000000",
                unknownAccount, "BUNI_IPN_TILL");
    }

    /** Registers that account, live, the way the account screen would once approved. */
    private PaymentAccount register(String accountNo) {
        PaymentType channel = types.findByProviderType("BUNI_IPN_TILL").orElseThrow();
        PaymentAccount row = PaymentAccount.builder()
                .accountNo(accountNo).accountName("Seller's till").tenantId(tenantId).createdBy("test").build();
        row.stampChannel(channel);
        return accounts.save(row);
    }

    @Test
    @DisplayName("a credit into an unregistered account waits for the account — not in the queue — and is placed when the account goes live")
    void anUnregisteredAccountWaitsThenPlaces() {
        String unknown = "TILLNEW" + Long.toString(System.nanoTime(), 36).toUpperCase();
        long queued = statements.countUnmapped();

        CoopStatement stored = service.accept(intoUnknownAccount(unknown, "Z4XP"), true);

        assertEquals(AppConstant.STATEMENT_NO_ACCOUNT, stored.getState());
        assertNull(stored.getPaymentAccountId());
        assertTrue(stored.getUnmappedReason().contains(unknown), stored.getUnmappedReason());
        assertTrue(stored.getUnmappedReason().contains("Register it"), stored.getUnmappedReason());
        assertEquals(queued, statements.countUnmapped(), "it is not unused money: nobody can place it yet");

        // Still nothing to do while the account is missing — the retry says so and the row stays put.
        CoopStatement retried = service.retry(statements.findById(stored.getId()).orElseThrow(), "test");
        assertEquals(AppConstant.STATEMENT_NO_ACCOUNT, retried.getState());
        assertTrue(retried.getUnmappedReason().contains("still not registered"), retried.getUnmappedReason());

        PaymentAccount registered = register(unknown);
        service.onAccountLive(new com.hodi.modules.payments.PaymentAccountWentLive(registered.getId()));

        CoopStatement after = statements.findById(stored.getId()).orElseThrow();
        assertEquals(AppConstant.STATEMENT_MAPPED, after.getState(), after.getUnmappedReason());
        assertEquals(registered.getId(), after.getPaymentAccountId(), "it took the account it was waiting for");
        assertEquals(tenantId, after.getTenantId(), "and the account's owner");
        assertEquals(HashIdUtil.decodeId(booking.id()), after.getMappedBookingId(), "the code and the amount agreed");
    }

    @Test
    @DisplayName("once the account is live, a credit whose code matches nothing goes to the queue like any other")
    void anUnregisteredAccountRetriedIntoTheQueue() {
        String unknown = "TILLNEW" + Long.toString(System.nanoTime(), 36).toUpperCase();
        CoopStatement stored = service.accept(intoUnknownAccount(unknown, "QQQQ"), true);
        assertEquals(AppConstant.STATEMENT_NO_ACCOUNT, stored.getState());

        PaymentAccount registered = register(unknown);
        service.onAccountLive(new com.hodi.modules.payments.PaymentAccountWentLive(registered.getId()));

        CoopStatement after = statements.findById(stored.getId()).orElseThrow();
        assertEquals(AppConstant.STATEMENT_UNMAPPED, after.getState());
        assertEquals(registered.getId(), after.getPaymentAccountId());
        assertTrue(after.getUnmappedReason().contains("QQQQ"), after.getUnmappedReason());
    }

    @Test
    @DisplayName("a code nobody has goes to the queue, with the code it looked for")
    void unknownCodeGoesToTheQueue() {
        CoopStatement stored = service.accept(payload("QQQQ", "950000.00", "254712345678"), true);

        assertEquals(AppConstant.STATEMENT_UNMAPPED, stored.getState());
        assertTrue(stored.getUnmappedReason().contains("QQQQ"), stored.getUnmappedReason());
    }

    @Test
    @DisplayName("no reference at all is stored rather than refused")
    void missingReferenceIsStored() {
        CoopStatement stored = service.accept(payload(null, "950000.00", "254712345678"), true);

        assertEquals(AppConstant.STATEMENT_UNMAPPED, stored.getState());
        assertNotNull(stored.getOurReference(), "and it still has our reference to echo back to Co-op");
        assertTrue(stored.getUnmappedReason().contains("no reference"), stored.getUnmappedReason());
    }

    @Test
    @DisplayName("a till we do not recognise is stored, named, and left waiting for the account — not in the queue")
    void unknownTillIsStored() {
        IpnPayload elsewhere = new IpnPayload(RrnGenerator.generate("RF"), null,
                "2026-08-27 10:30:00", "950000.00", "KES", "Z4XP", "Asha Mwangi", "254712345678",
                "999999", "BUNI_IPN_TILL");

        CoopStatement stored = service.accept(elsewhere, true);
        assertEquals(AppConstant.STATEMENT_NO_ACCOUNT, stored.getState(),
                "nobody can place money whose account is unknown, so it is not unused money");
        assertTrue(stored.getUnmappedReason().contains("999999"), stored.getUnmappedReason());
        assertNull(stored.getTenantId(), "and nobody owns money that arrived in an account we do not know");

        jdbc.update("delete from coop_statements where ref_no = ?", stored.getRefNo());
    }

    // ── the safety property ───────────────────────────────────────────────────

    @Test
    @DisplayName("without the shared secret nothing is credited, however well it matches")
    void untrustedNotificationIsNeverPlaced() {
        /*
         * The only thing standing between a forged notification and somebody's balance. A four-character code
         * and a round amount are both guessable; this test is what stops a guess from crediting a buyer.
         */
        CoopStatement stored = service.accept(payload("Z4XP", "950000.00", "254712345678"), false);

        assertEquals(AppConstant.STATEMENT_UNMAPPED, stored.getState());
        assertTrue(stored.getUnmappedReason().contains("not authenticated"), stored.getUnmappedReason());
        assertEquals(0, payments.totalPaid(HashIdUtil.decodeId(booking.id()))
                .compareTo(BigDecimal.ZERO), "the money is on the books, and on nobody's balance");
    }

    @Test
    @DisplayName("with no secret configured, no header is trusted — including a plausible one")
    void nothingIsTrustedWhileTheSecretIsUnset() {
        assertFalse(service.isTrusted(null));
        assertFalse(service.isTrusted(""));
        assertFalse(service.isTrusted("probably-the-secret"));
        // Even a well-formed Basic header: unconfigured means closed, not "match anything".
        assertFalse(service.isTrusted("Basic "
                + java.util.Base64.getEncoder().encodeToString(
                        "coop:secret".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    // ── idempotency ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("the same notification twice is one statement and one payment")
    void deliveredTwiceIsCreditedOnce() {
        IpnPayload once = payload("Z4XP", "950000.00", "254712345678");

        CoopStatement first = service.accept(once, true);
        CoopStatement again = service.accept(once, true);

        assertEquals(first.getId(), again.getId());
        assertEquals(first.getOurReference(), again.getOurReference(),
                "and the same reference goes back, so Co-op's own record still matches ours");
        assertEquals(1, payments.findForBooking(HashIdUtil.decodeId(booking.id())).size(),
                "Co-op retries anything it does not get a clean answer to within thirty seconds");
    }

    @Test
    @DisplayName("the unique index is what guarantees that, not the check")
    void indexRefusesADuplicateRefNo() {
        CoopStatement first = service.accept(payload("Z4XP", "950000.00", "254712345678"), true);

        /*
         * Inserted underneath the service, which is the only way to reach the state the check cannot prevent:
         * two deliveries of the same money in flight at once, both seeing nothing recorded. If this insert
         * succeeds, the guarantee was only ever a check — and that check loses this race in production.
         */
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        insert into coop_statements (ref_no, our_reference, trans_type, amount, currency,
                            state, created_by)
                        values (?, ?, 'BUNI_IPN_TILL', 100, 'KES', 'UNMAPPED', 'test')
                        """, first.getRefNo(), RrnGenerator.generate("PS")));
    }

    private static <T extends Throwable> void assertThrows(
            Class<T> type, org.junit.jupiter.api.function.Executable e) {
        org.junit.jupiter.api.Assertions.assertThrows(type, e);
    }
}
