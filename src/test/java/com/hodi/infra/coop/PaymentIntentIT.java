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
import com.hodi.modules.payments.PaymentIntent;
import com.hodi.modules.payments.PaymentIntentRepository;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A payment we started, and the answer arriving more than once.
 *
 * <h2>Not transactional, deliberately</h2>
 *
 * <p>{@code CoopIpnService.accept} is {@code REQUIRES_NEW} — the record of money arriving must survive a
 * failure while working out whose it is — so it takes its own connection and cannot see uncommitted
 * fixtures. Inside a {@code @Transactional} test it would find no booking and no account, place nothing,
 * and every assertion would pass for the wrong reason. Hence the hand-written clean-up below.
 *
 * <h2>What is worth testing here</h2>
 *
 * <p>Not the happy path: that the same money reported twice moves a balance once. Co-op retries a callback
 * it believes went unacknowledged, and the status query settles payments whose callback was lost, so both
 * paths routinely report the same payment. A double credit is invisible at the moment it happens — it
 * surfaces later as a buyer's balance being wrong, found by the buyer.
 *
 * <p>And the refusals: that silence never fails a payment, that a late failure never reverses a confirmed
 * one, and that being told again a payment is in flight does not restart the clock the sweep reads.
 */
@SpringBootTest
class PaymentIntentIT {

    @Autowired CoopIpnService service;
    @Autowired BookingService bookings;
    @Autowired CoopStatementRepository statements;
    @Autowired PaymentAccountRepository accounts;
    @Autowired PaymentTypeRepository types;
    @Autowired PaymentRepository payments;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitRepository units;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired CoopIntentSettlement settlement;
    @Autowired PaymentIntentRepository intents;
    @Autowired JdbcTemplate jdbc;

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
        User user = User.builder().id(1L).username("intent-test").password("x")
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
                .name("Intent Heights").developmentType("APARTMENT").build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(new BigDecimal("9500000")).build());
        unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("C-3-07")
                .payReference("Z4XP")
                .saleState(AppConstant.UNIT_AVAILABLE)
                .constructionStatus(AppConstant.BUILD_PLANNED).build());

        account = "INT" + Long.toString(System.nanoTime(), 36).toUpperCase();
        PaymentType channel = types.findByProviderType("BUNI_IPN_TILL").orElseThrow();
        PaymentAccount row = PaymentAccount.builder()
                .accountNo(account).accountName("Intent till")
                .tenantId(tenantId).createdBy("test").build();
        row.stampChannel(channel);
        till = accounts.save(row);

        booking = bookings.create(HashIdUtil.encodeId(development.getId()),
                new CreateBookingRequest(HashIdUtil.encodeId(unit.getId()), "Asha Mwangi",
                        "+254 712 345 678", null, null, new BigDecimal("9500000"),
                        new BigDecimal("950000"), null, 14, null, null));
    }

    /**
     * Removes whatever an earlier, interrupted run left behind under this class's own fixture names.
     *
     * <p>The statement and the payment point at each other, so the link is broken before either is deleted.
     */
    private void purgeStaleFixtures() {
        // Intents point at payments and statements, so they go first or the deletes below are refused.
        jdbc.update("delete from payment_intents where property_id in (select id from properties "
                + "where development_id in (select id from developments where name = 'Intent Heights'))");
        jdbc.update("update payments set statement_id = null where development_id in "
                + "(select id from developments where name = 'Intent Heights')");
        jdbc.update("delete from coop_statements where mapped_payment_id in (select id from payments "
                + "where development_id in (select id from developments where name = 'Intent Heights')) "
                + "or account_identifier like 'INT%'");
        jdbc.update("delete from payments where development_id in "
                + "(select id from developments where name = 'Intent Heights')");
        jdbc.update("delete from booking_instalments where booking_id in (select id from unit_bookings "
                + "where development_id in (select id from developments where name = 'Intent Heights'))");
        jdbc.update("delete from unit_bookings where development_id in "
                + "(select id from developments where name = 'Intent Heights')");
        jdbc.update("delete from properties where listing_kind = 'UNIT' and development_id in "
                + "(select id from developments where name = 'Intent Heights')");
        jdbc.update("delete from development_unit_types where development_id in "
                + "(select id from developments where name = 'Intent Heights')");
        jdbc.update("delete from developments where name = 'Intent Heights'");
        jdbc.update("delete from payment_accounts where account_no like 'INT%' and account_name = 'Intent till'");
    }

    @AfterEach
    void cleanUp() {
        try {
            // The payment names the statement and the statement names the payment: unlink, then delete.
            jdbc.update("delete from payment_intents where booking_id in "
                    + "(select id from unit_bookings where development_id = ?)", development.getId());
            jdbc.update("update payments set statement_id = null where booking_id in "
                    + "(select id from unit_bookings where development_id = ?)", development.getId());
            jdbc.update("delete from coop_statements where payment_account_id = ? "
                    + "or account_identifier = ?", till.getId(), account);
            jdbc.update("delete from payments where booking_id in "
                    + "(select id from unit_bookings where development_id = ?)", development.getId());
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

    // ── what we asked for ─────────────────────────────────────────────────────

    /** An intent as the STK service writes one: ours, against this booking, awaiting an answer. */
    private PaymentIntent anIntentFor(BigDecimal amount) {
        return settlement.open(PaymentIntent.builder()
                .reference(RrnGenerator.generate("IN"))
                .paymentTypeId(till.getPaymentTypeId())
                .paymentAccountId(till.getId())
                .bookingId(HashIdUtil.decodeId(booking.id()))
                .propertyId(unit.getId())
                .amount(amount)
                .currency("KES")
                .phoneNo("+254712345678")
                .narration("Test prompt")
                .state(PaymentIntent.PENDING)
                .callbackTimeoutSeconds(60)
                .tenantId(tenantId)
                .createdBy("test")
                .build());
    }

    private long paymentsOnTheBooking() {
        return jdbc.queryForObject("select count(*) from payments where booking_id = ? and status <> 5",
                Long.class, HashIdUtil.decodeId(booking.id()));
    }

    @Test
    @DisplayName("the status query credits the booking once, and saying so again changes nothing")
    void aConfirmedPaymentIsCreditedOnce() {
        PaymentIntent intent = anIntentFor(new BigDecimal("950000"));
        settlement.inFlight(intent.getId(), "COOP-REF-1", "Prompt sent.");

        settlement.succeeded(intent.getId(), "COOP-REF-1", "RCPT-1", "Success", "tester");
        assertEquals(1, paymentsOnTheBooking(), "the money is recorded");

        // Co-op answering twice, or a sweep racing an operator's button. Both happen.
        settlement.succeeded(intent.getId(), "COOP-REF-1", "RCPT-1", "Success", "tester");
        settlement.succeeded(intent.getId(), "COOP-REF-1", "RCPT-1", "Success", "tester");
        assertEquals(1, paymentsOnTheBooking(),
                "and a repeat does not double a buyer's balance");

        PaymentIntent settled = intents.findById(intent.getId()).orElseThrow();
        assertEquals(PaymentIntent.SUCCEEDED, settled.getState());
        assertEquals("RCPT-1", settled.getReceipt());
        assertNotNull(settled.getPaymentId());
    }

    @Test
    @DisplayName("a notification for a payment the query already settled is linked, not credited again")
    void aLateCallbackDoesNotCreditTwice() {
        PaymentIntent intent = anIntentFor(new BigDecimal("950000"));
        settlement.inFlight(intent.getId(), "COOP-REF-2", "Prompt sent.");
        settlement.succeeded(intent.getId(), "COOP-REF-2", "RCPT-2", "Success", "tester");
        assertEquals(1, paymentsOnTheBooking());

        // Co-op calls back about the same payment afterwards, quoting our reference.
        CoopStatement stored = service.accept(payload(intent.getReference(), "950000", "+254712345678"),
                true);

        assertEquals(1, paymentsOnTheBooking(), "still one payment");
        assertEquals(AppConstant.STATEMENT_MAPPED, stored.getState(),
                "and the notification is accounted for rather than left in the unmatched queue");
        PaymentIntent settled = intents.findById(intent.getId()).orElseThrow();
        assertEquals(stored.getId(), settled.getStatementId(),
                "the intent records which notification answered it");
    }

    @Test
    @DisplayName("a notification quoting an intent credits it without needing the unit code to corroborate")
    void aCallbackAloneCanCreditAnIntent() {
        PaymentIntent intent = anIntentFor(new BigDecimal("500000"));
        settlement.inFlight(intent.getId(), "COOP-REF-3", "Prompt sent.");

        CoopStatement stored = service.accept(payload(intent.getReference(), "500000", "+254712345678"),
                true);

        assertEquals(AppConstant.STATEMENT_MAPPED, stored.getState());
        assertEquals(1, paymentsOnTheBooking(),
                "we chose the booking when we asked, so nothing else has to agree");
        PaymentIntent settled = intents.findById(intent.getId()).orElseThrow();
        assertEquals(PaymentIntent.SUCCEEDED, settled.getState());
        assertNotNull(settled.getPaymentId());
    }

    @Test
    @DisplayName("the same notification delivered twice is stored once and credited once")
    void theSameDeliveryTwiceIsOnePayment() {
        PaymentIntent intent = anIntentFor(new BigDecimal("500000"));
        settlement.inFlight(intent.getId(), "COOP-REF-4", "Prompt sent.");

        IpnPayload delivery = payload(intent.getReference(), "500000", "+254712345678");
        CoopStatement first = service.accept(delivery, true);
        CoopStatement again = service.accept(delivery, true);

        assertEquals(first.getId(), again.getId(), "the retry answers with the row we already have");
        assertEquals(1, paymentsOnTheBooking());
    }

    @Test
    @DisplayName("a failure never overrides a payment the bank already confirmed")
    void aLateFailureDoesNotReverseACredit() {
        PaymentIntent intent = anIntentFor(new BigDecimal("950000"));
        settlement.inFlight(intent.getId(), "COOP-REF-5", "Prompt sent.");
        settlement.succeeded(intent.getId(), "COOP-REF-5", "RCPT-5", "Success", "tester");

        settlement.failed(intent.getId(), "COOP-REF-5", "Co-op says it did not go through");

        PaymentIntent settled = intents.findById(intent.getId()).orElseThrow();
        assertEquals(PaymentIntent.SUCCEEDED, settled.getState(),
                "the money is what it is; a person decides, not a background job");
        assertEquals(1, paymentsOnTheBooking());
    }

    @Test
    @DisplayName("being told again that it is in flight does not restart its deadline")
    void theClockStartsOnce() {
        PaymentIntent intent = anIntentFor(new BigDecimal("950000"));
        settlement.inFlight(intent.getId(), "COOP-REF-6", "Prompt sent.");
        OffsetDateTime first = intents.findById(intent.getId()).orElseThrow().getProcessedAt();
        assertNotNull(first);

        settlement.inFlight(intent.getId(), "COOP-REF-6", "Asked again.");

        assertEquals(first, intents.findById(intent.getId()).orElseThrow().getProcessedAt(),
                "otherwise a payment that keeps being retried is never old enough for the sweep to chase");
    }

    @Test
    @DisplayName("an unanswered payment is chased only up to its cap, and never failed by the sweep")
    void theSweepStopsAtTheCap() {
        PaymentIntent intent = anIntentFor(new BigDecimal("950000"));
        settlement.inFlight(intent.getId(), "COOP-REF-7", "Prompt sent.");

        settlement.stillWaiting(intent.getId(), 1, "Co-op says it is still in progress.");
        settlement.stillWaiting(intent.getId(), 2, "Asked twice, no final answer. Awaiting a person.");

        PaymentIntent waiting = intents.findById(intent.getId()).orElseThrow();
        assertEquals(PaymentIntent.PROCESSING, waiting.getState(),
                "silence is never failure — a person settles it, the sweep does not");
        assertEquals(2, waiting.getStatusQueryAttempts());
        assertTrue(waiting.getProcessingReason().contains("person"),
                "and the row says what is waiting on whom");
        assertEquals(0, paymentsOnTheBooking(), "nothing is credited on a payment nobody confirmed");
    }
}
