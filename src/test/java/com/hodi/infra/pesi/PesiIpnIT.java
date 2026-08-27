package com.hodi.infra.pesi;

import com.hodi.common.AppConstant;
import com.hodi.common.util.RrnGenerator;
import com.hodi.infra.pesi.PesiIpnDtos.IpnPayload;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingPaymentRepository;
import com.hodi.modules.bookings.BookingService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Taking in a payment Pesi says arrived.
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
 * <p>Idempotency, because Pesi retries anything it does not get a clean answer to and the same money arriving
 * twice must not be credited twice. The corroboration rule, because a four-character code has no redundancy
 * and one mistyped letter lands on another live unit about one time in two hundred. And the refusal to
 * auto-credit while unauthenticated, which is the only thing standing between a forged notification and
 * somebody's balance.
 */
@SpringBootTest
class PesiIpnIT {

    @Autowired PesiIpnService service;
    @Autowired BookingService bookings;
    @Autowired PesiStatementRepository statements;
    @Autowired PesiPaymentMethodRepository methods;
    @Autowired PesiSuperTypeRepository superTypes;
    @Autowired BookingPaymentRepository payments;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitRepository units;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private Development development;
    private DevelopmentUnit unit;
    private PesiPaymentMethod till;
    private BookingResponse booking;

    /** Unique per run, so two runs cannot collide on the till's account number. */
    private String account;

    @BeforeEach
    void signInAndBuild() {
        tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        User user = User.builder().id(1L).username("pesi-test").password("x")
                .email("p@example.invalid").firstName("Pia").lastName("Pesi")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenantId).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("UNITS_MANAGE", "UNITS_SELL", "BOOKINGS_MANAGE", "BOOKINGS_PAYMENTS"),
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
        unit = units.save(DevelopmentUnit.builder()
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("C-3-07")
                .payReference("Z4XP")
                .saleState(AppConstant.UNIT_AVAILABLE)
                .constructionStatus(AppConstant.BUILD_PLANNED).build());

        account = "TILL" + RrnGenerator.generate("T").substring(0, 8);
        Long superTypeId = superTypes.findByCode("BUNI_IPN_TILL").orElseThrow().getId();
        till = methods.save(PesiPaymentMethod.builder()
                .superTypeId(superTypeId).accountNumber(account).accountName("Seller's till")
                .tenantId(tenantId).createdBy("test").build());

        booking = bookings.create(HashIdUtil.encodeId(development.getId()),
                new CreateBookingRequest(HashIdUtil.encodeId(unit.getId()), "Asha Mwangi",
                        "+254 712 345 678", null, null, new BigDecimal("9500000"),
                        new BigDecimal("950000"), null, 14, null, null));
    }

    @AfterEach
    void cleanUp() {
        try {
            jdbc.update("delete from pesi_statements where payment_method_id = ? "
                    + "or account_identifier = ?", till.getId(), account);
            jdbc.update("delete from booking_payments where booking_id in "
                    + "(select id from unit_bookings where development_id = ?)", development.getId());
            jdbc.update("delete from booking_instalments where booking_id in "
                    + "(select id from unit_bookings where development_id = ?)", development.getId());
            jdbc.update("delete from unit_bookings where development_id = ?", development.getId());
            jdbc.update("delete from development_units where development_id = ?", development.getId());
            jdbc.update("delete from development_unit_types where development_id = ?", development.getId());
            jdbc.update("delete from developments where id = ?", development.getId());
            jdbc.update("delete from pesi_payment_methods where id = ?", till.getId());
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
        PesiStatement stored = service.accept(payload("Z4XP", "950000.00", "254700000000"), true);

        assertEquals(AppConstant.STATEMENT_MAPPED, stored.getState(), stored.getUnmappedReason());
        assertNotNull(stored.getMappedPaymentId());
        assertEquals(0, payments.totalPaid(HashIdUtil.decodeId(booking.id()))
                .compareTo(new BigDecimal("950000")));
        assertEquals(tenantId, stored.getTenantId(), "whose money it is, from the till it landed in");
    }

    @Test
    @DisplayName("the buyer's own number corroborates it even when the amount is a part payment")
    void phoneCorroboratesTheCode() {
        // Pesi sends 254…; the booking holds "+254 712 345 678". Same phone, written three ways.
        PesiStatement stored = service.accept(payload("Z4XP", "125000.00", "254712345678"), true);

        assertEquals(AppConstant.STATEMENT_MAPPED, stored.getState(), stored.getUnmappedReason());
        assertEquals(0, payments.totalPaid(HashIdUtil.decodeId(booking.id()))
                .compareTo(new BigDecimal("125000")));
    }

    @Test
    @DisplayName("a reference with the payer's own words around it still finds the code")
    void referenceIsCleanedBeforeMatching() {
        PesiStatement stored = service.accept(payload("unit z4xp", "950000.00", "254700000000"), true);
        assertEquals(AppConstant.STATEMENT_MAPPED, stored.getState(), stored.getUnmappedReason());
    }

    // ── sent to the queue ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a code match on its own is never enough, and the reason says why")
    void bareCodeMatchGoesToTheQueue() {
        /*
         * The whole point of the corroboration rule. Four characters carry no redundancy, so one mistyped
         * letter produces another well-formed code — and at five thousand units the chance it is a live one is
         * about one in two hundred. Too high to move a balance on.
         */
        PesiStatement stored = service.accept(payload("Z4XP", "37500.00", "254700000000"), true);

        assertEquals(AppConstant.STATEMENT_UNMAPPED, stored.getState());
        assertTrue(stored.getUnmappedReason().contains("C-3-07"), stored.getUnmappedReason());
        assertTrue(stored.getUnmappedReason().contains(booking.reference()),
                "and it names the booking, so a person can go and check it");
        assertEquals(0, payments.totalPaid(HashIdUtil.decodeId(booking.id()))
                .compareTo(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("a code nobody has goes to the queue, with the code it looked for")
    void unknownCodeGoesToTheQueue() {
        PesiStatement stored = service.accept(payload("QQQQ", "950000.00", "254712345678"), true);

        assertEquals(AppConstant.STATEMENT_UNMAPPED, stored.getState());
        assertTrue(stored.getUnmappedReason().contains("QQQQ"), stored.getUnmappedReason());
    }

    @Test
    @DisplayName("no reference at all is stored rather than refused")
    void missingReferenceIsStored() {
        PesiStatement stored = service.accept(payload(null, "950000.00", "254712345678"), true);

        assertEquals(AppConstant.STATEMENT_UNMAPPED, stored.getState());
        assertNotNull(stored.getOurReference(), "and it still has our reference to echo back to Pesi");
        assertTrue(stored.getUnmappedReason().contains("no reference"), stored.getUnmappedReason());
    }

    @Test
    @DisplayName("a till we do not recognise is stored, named, and left for a person")
    void unknownTillIsStored() {
        IpnPayload elsewhere = new IpnPayload(RrnGenerator.generate("RF"), null,
                "2026-08-27 10:30:00", "950000.00", "KES", "Z4XP", "Asha Mwangi", "254712345678",
                "999999", "BUNI_IPN_TILL");

        PesiStatement stored = service.accept(elsewhere, true);
        assertEquals(AppConstant.STATEMENT_UNMAPPED, stored.getState());
        assertTrue(stored.getUnmappedReason().contains("999999"), stored.getUnmappedReason());
        assertNull(stored.getTenantId(), "and nobody owns money that arrived in an account we do not know");

        jdbc.update("delete from pesi_statements where ref_no = ?", stored.getRefNo());
    }

    // ── the safety property ───────────────────────────────────────────────────

    @Test
    @DisplayName("without the shared secret nothing is credited, however well it matches")
    void untrustedNotificationIsNeverPlaced() {
        /*
         * The only thing standing between a forged notification and somebody's balance. A four-character code
         * and a round amount are both guessable; this test is what stops a guess from crediting a buyer.
         */
        PesiStatement stored = service.accept(payload("Z4XP", "950000.00", "254712345678"), false);

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
    }

    // ── idempotency ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("the same notification twice is one statement and one payment")
    void deliveredTwiceIsCreditedOnce() {
        IpnPayload once = payload("Z4XP", "950000.00", "254712345678");

        PesiStatement first = service.accept(once, true);
        PesiStatement again = service.accept(once, true);

        assertEquals(first.getId(), again.getId());
        assertEquals(first.getOurReference(), again.getOurReference(),
                "and the same reference goes back, so Pesi's own record still matches ours");
        assertEquals(1, payments.findForBooking(HashIdUtil.decodeId(booking.id())).size(),
                "Pesi retries anything it does not get a clean answer to within thirty seconds");
    }

    @Test
    @DisplayName("the unique index is what guarantees that, not the check")
    void indexRefusesADuplicateRefNo() {
        PesiStatement first = service.accept(payload("Z4XP", "950000.00", "254712345678"), true);

        /*
         * Inserted underneath the service, which is the only way to reach the state the check cannot prevent:
         * two deliveries of the same money in flight at once, both seeing nothing recorded. If this insert
         * succeeds, the guarantee was only ever a check — and that check loses this race in production.
         */
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("""
                        insert into pesi_statements (ref_no, our_reference, trans_type, amount, currency,
                            state, created_by)
                        values (?, ?, 'BUNI_IPN_TILL', 100, 'KES', 'UNMAPPED', 'test')
                        """, first.getRefNo(), RrnGenerator.generate("PS")));
    }

    private static <T extends Throwable> void assertThrows(
            Class<T> type, org.junit.jupiter.api.function.Executable e) {
        org.junit.jupiter.api.Assertions.assertThrows(type, e);
    }
}
