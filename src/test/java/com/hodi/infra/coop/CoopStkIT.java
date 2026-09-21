package com.hodi.infra.coop;

import com.hodi.common.AppConstant;
import com.hodi.common.EncryptionUtil;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingDtos.InstalmentLine;
import com.hodi.modules.bookings.BookingService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.developments.*;
import com.hodi.modules.payments.*;
import com.hodi.modules.payments.PaymentIntentService.IntentResponse;
import com.hodi.modules.payments.PaymentIntentService.PromptRequest;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.properties.Property;
import com.hodi.modules.users.User;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.UserPrincipal;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The phone prompt, against a Co-op that is a small HTTP server in this JVM.
 *
 * <h2>Why a stub and not a mock</h2>
 *
 * <p>Nothing outbound had ever been exercised by a test: the token cache, the HTML page a firewall answers
 * with, the receipt buried in the enquiry's metadata, the two callback envelopes. Those are exactly the
 * places a bank's change breaks us, and they are all about bytes on the wire, so the test speaks HTTP to
 * a server it controls rather than mocking the client. The JDK's own server, because it is already here.
 *
 * <h2>Why this class commits</h2>
 *
 * <p>Every settlement is {@code REQUIRES_NEW}; the rows commit under the test and are cleaned by hand.
 * The Co-op settings and the two channels' endpoints are changed for the run and put back after it.
 */
@SpringBootTest
class CoopStkIT {

    @Autowired PaymentIntentService prompts;
    @Autowired CoopStkService stk;
    @Autowired CoopIntentSweep sweep;
    @Autowired BookingService bookings;
    @Autowired PaymentIntentRepository intents;
    @Autowired CoopStatementRepository statements;
    @Autowired PaymentRepository payments;
    @Autowired PaymentAccountRepository accounts;
    @Autowired PaymentTypeRepository types;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitRepository units;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired ConfigurationService configs;
    @Autowired EncryptionUtil crypto;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private static final String DEVELOPMENT = "Prompt Heights";

    // ── the bank ──────────────────────────────────────────────────────────────

    private static HttpServer coop;
    private static final AtomicInteger tokenCalls = new AtomicInteger();
    private static final AtomicReference<String> pushAnswer = new AtomicReference<>();
    private static final AtomicReference<String> statusAnswer = new AtomicReference<>();
    private static final List<String> pushBodies = new CopyOnWriteArrayList<>();

    private static final String ACCEPTED = """
            {"MessageReference":"%s","MessageDateTime":"2026-09-20T10:00:00","MessageCode":"0",
             "MessageDescription":"REQUEST ACCEPTED FOR PROCESSING"}""";

    @BeforeAll
    static void startTheBank() throws IOException {
        coop = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        coop.createContext("/token", exchange -> {
            tokenCalls.incrementAndGet();
            answer(exchange, 200, "application/json",
                    "{\"access_token\":\"tok-" + tokenCalls.get() + "\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
        });
        coop.createContext("/FT/stk/1.0.0", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            pushBodies.add(body);
            String reference = body.replaceAll("(?s).*\"MessageReference\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            String configured = pushAnswer.get();
            if (configured != null && configured.startsWith("<")) {
                answer(exchange, 200, "text/html", configured);
            } else {
                answer(exchange, 200, "application/json",
                        configured == null ? ACCEPTED.formatted(reference) : configured);
            }
        });
        coop.createContext("/Enquiry/STK/1.0.0/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            answer(exchange, 200, "application/json", statusAnswer.get() == null ? "{}" : statusAnswer.get());
        });
        coop.start();
    }

    @AfterAll
    static void stopTheBank() {
        if (coop != null) coop.stop(0);
    }

    private static void answer(com.sun.net.httpserver.HttpExchange exchange, int status, String type,
                               String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String bankUrl() {
        return "http://127.0.0.1:" + coop.getAddress().getPort();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private Long tenantId;
    private long buyerUser;
    private Development development;
    private BookingResponse booking;
    private Long bookingRaw;
    private PaymentAccount account;
    private final Map<String, String> previousConfig = new java.util.HashMap<>();
    private String previousStkConfig;
    private String previousStatusConfig;

    @BeforeEach
    void build() {
        purge();
        tokenCalls.set(0);
        pushAnswer.set(null);
        statusAnswer.set(null);
        pushBodies.clear();
        tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        buyerUser = jdbc.queryForObject("select id from users where status <> 5 order by id limit 1", Long.class);
        signInAsPlatform();

        // Co-op is the server above, for the length of this test.
        remember("coop.base.url", "coop.token.path", "coop.consumer.key", "coop.consumer.secret",
                "coop.callback.timeout.seconds");
        set("coop.base.url", bankUrl(), false);
        set("coop.token.path", "/token", false);
        set("coop.consumer.key", "consumer-key", false);
        set("coop.consumer.secret", "consumer-secret", true);
        set("coop.callback.timeout.seconds", "0", false);
        previousStkConfig = jdbc.queryForObject(
                "select config::text from payment_types where provider_type = 'COOP_STK_PUSH'", String.class);
        previousStatusConfig = jdbc.queryForObject(
                "select config::text from payment_types where provider_type = 'COOP_STK_STATUS'", String.class);
        jdbc.update("update payment_types set status = 1, config = '{\"endpoint\":\"/FT/stk/1.0.0\"}'::jsonb "
                + "where provider_type = 'COOP_STK_PUSH'");
        jdbc.update("update payment_types set status = 1, config = '{\"endpoint\":\"/Enquiry/STK/1.0.0/\"}'::jsonb "
                + "where provider_type = 'COOP_STK_STATUS'");
        configs.evictAll();

        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name(DEVELOPMENT).developmentType("APARTMENT").build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(new BigDecimal("9500000")).build());
        Property unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("P-1-01")
                .saleState(AppConstant.UNIT_AVAILABLE).constructionStatus(AppConstant.BUILD_PLANNED).build());
        booking = bookings.create(HashIdUtil.encodeId(development.getId()), new CreateBookingRequest(
                HashIdUtil.encodeId(unit.getId()), "Asha Mwangi", "+254712000111", null, null,
                new BigDecimal("9500000"), new BigDecimal("950000"), AppConstant.PLAN_INSTALMENTS, 14, null,
                List.of(new InstalmentLine("Deposit", LocalDate.now().minusDays(10), new BigDecimal("950000")),
                        new InstalmentLine("Balance", LocalDate.now().plusDays(90), new BigDecimal("8550000")))));
        bookingRaw = HashIdUtil.decodeId(booking.id());
        jdbc.update("update unit_bookings set buyer_user_id = ? where id = ?", buyerUser, bookingRaw);

        // The seller's own prompt account, approved: the operator code Co-op issued. The seller's, rather
        // than the platform's, because this database may already hold a platform account and the rule
        // prefers the booking's own organisation — which is the rule this test then relies on.
        PaymentType channel = types.findByProviderType("COOP_STK_PUSH").orElseThrow();
        String operator = "PRM" + Long.toString(System.nanoTime(), 36).toUpperCase();
        PaymentAccount row = PaymentAccount.builder()
                .accountNo(operator).accountName("Prompt operator").tenantId(tenantId)
                .config(Map.of("accountNumber", operator)).createdBy("test").build();
        row.stampChannel(channel);
        account = accounts.save(row);
    }

    @AfterEach
    void restore() {
        try {
            purge();
            previousConfig.forEach((key, value) -> jdbc.update(
                    "update configurations set config_value = ? where config_key = ?", value, key));
            jdbc.update("update payment_types set config = ?::jsonb where provider_type = 'COOP_STK_PUSH'",
                    previousStkConfig);
            jdbc.update("update payment_types set config = ?::jsonb where provider_type = 'COOP_STK_STATUS'",
                    previousStatusConfig);
            configs.evictAll();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void purge() {
        jdbc.update("delete from payment_intents where booking_id in (select id from unit_bookings "
                + "where development_id in (select id from developments where name = ?))", DEVELOPMENT);
        jdbc.update("update coop_statements set state = 'UNMAPPED', mapped_payment_id = null, "
                + "mapped_booking_id = null, mapped_at = null where mapped_payment_id in "
                + "(select id from payments where development_id in "
                + "(select id from developments where name = ?))", DEVELOPMENT);
        jdbc.update("delete from payments where development_id in "
                + "(select id from developments where name = ?)", DEVELOPMENT);
        jdbc.update("delete from coop_statements where account_identifier like 'PRM%' or trace_id in "
                + "(select reference from payment_intents where property_id in (select id from properties "
                + "where development_id in (select id from developments where name = ?)))", DEVELOPMENT);
        jdbc.update("delete from booking_instalments where booking_id in (select id from unit_bookings "
                + "where development_id in (select id from developments where name = ?))", DEVELOPMENT);
        jdbc.update("delete from unit_bookings where development_id in "
                + "(select id from developments where name = ?)", DEVELOPMENT);
        jdbc.update("delete from properties where listing_kind = 'UNIT' and development_id in "
                + "(select id from developments where name = ?)", DEVELOPMENT);
        jdbc.update("delete from development_unit_types where development_id in "
                + "(select id from developments where name = ?)", DEVELOPMENT);
        jdbc.update("delete from developments where name = ?", DEVELOPMENT);
        jdbc.update("delete from payment_accounts where account_no like 'PRM%' and account_name = 'Prompt operator'");
    }

    private void remember(String... keys) {
        for (String key : keys) {
            previousConfig.put(key, jdbc.queryForObject(
                    "select config_value from configurations where config_key = ?", String.class, key));
        }
    }

    private void set(String key, String value, boolean secret) {
        jdbc.update("update configurations set config_value = ? where config_key = ?",
                secret ? crypto.encryptString(value) : value, key);
    }

    private void signInAsPlatform() {
        User user = User.builder().id(7L).username("prompt-admin").password("x")
                .email("p@example.invalid").firstName("Prompt").lastName("Admin")
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

    private void signInAsSeller() {
        User user = User.builder().id(8L).username("seller-staff").password("x")
                .email("s@example.invalid").firstName("Sel").lastName("Ler")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(8L).userId(8L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenantId).tenantName("Seller").status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of("PAYMENTS_VIEW", "BOOKINGS_MANAGE"), List.of(tenantId),
                false, true));
    }

    private static void signIn(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private PaymentIntent reload(String hashId) {
        return intents.findById(HashIdUtil.decodeId(hashId)).orElseThrow();
    }

    private Map<String, Object> json(String text) throws Exception {
        return mapper.readValue(text, Map.class);
    }

    private long paymentsOnTheBooking() {
        return payments.findForBooking(bookingRaw).size();
    }

    // ── asking ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a prompt is accepted and left in flight, for what is owed, with one token for many calls")
    void aPromptIsAnAcknowledgement() {
        IntentResponse first = prompts.prompt(new PromptRequest(booking.id(), null, null, null));

        assertEquals(PaymentIntent.PROCESSING, first.state(), first.processingReason());
        assertFalse(first.settled(), "the customer has not decided; nothing says paid");
        assertEquals(0, first.amount().compareTo(new BigDecimal("950000")), "what is overdue, when nothing was named");
        assertEquals("+254712000111", first.phoneNo(), "the buyer's own phone, when nobody named another");
        assertEquals(first.reference(), first.bankReference(), "Co-op echoed our reference as theirs");
        assertTrue(pushBodies.get(0).contains("\"MessageReference\":\"" + first.reference() + "\""));
        assertTrue(pushBodies.get(0).contains("\"OperatorCode\":\"" + account.getAccountNo() + "\""),
                "the seller's own account before the platform's, and its operator code: " + pushBodies.get(0));

        IntentResponse second = prompts.prompt(new PromptRequest(booking.id(), new BigDecimal("1000"),
                "0722000000", "Top up"));
        assertEquals(PaymentIntent.PROCESSING, second.state());
        assertEquals("0722000000", second.phoneNo(), "staff may name who to prompt");
        assertTrue(tokenCalls.get() <= 1, "one token serves both calls (or one already cached serves all); "
                + "asking per call is how a rate limit is met — asked " + tokenCalls.get() + " times");

        HodiException tooMuch = assertThrows(HodiException.class, () -> prompts.prompt(new PromptRequest(
                booking.id(), new BigDecimal("99999999"), null, null)));
        assertTrue(tooMuch.getMessage().contains("more than"), tooMuch.getMessage());
        assertEquals(0, paymentsOnTheBooking(), "nothing is credited on an acknowledgement");
    }

    @Test
    @DisplayName("a firewall's HTML page means nothing reached the bank, so the prompt fails at once with the reason")
    void aFirewallPageIsNeverSent() {
        pushAnswer.set("<html><head><title>Request Rejected</title></head><body>blocked</body></html>");

        IntentResponse intent = prompts.prompt(new PromptRequest(booking.id(), null, null, null));

        assertEquals(PaymentIntent.FAILED, intent.state(), "no customer was prompted, so nothing is waited for");
        assertTrue(intent.settled());
        assertEquals(CoopStkService.NOT_SENT, intent.processingReason(),
                "the firewall's page is in the log under the trace id, not on the screen — for anybody");
        assertNotNull(intent.traceId(), "and the row carries the handle the log lines were written under");
        assertTrue(intent.traceId().startsWith("HDI"), intent.traceId());

        // The customer is told it did not go through, and given the handle — never the plumbing.
        Long raw = HashIdUtil.decodeId(intent.id());
        signInAsBuyer(buyerUser);
        IntentResponse asTheBuyer = prompts.find(HashIdUtil.encodeId(raw));
        assertEquals(PaymentIntentService.BUYER_FAILED, asTheBuyer.processingReason());
        assertFalse(asTheBuyer.processingReason().toLowerCase().contains("firewall"));
        assertNull(asTheBuyer.traceId(), "the handle is the sales office's, not the customer's");
        String theirs = HashIdUtil.encodeId(bookingRaw);
        HodiException notTheirs = assertThrows(HodiException.class, () -> prompts.forBooking(theirs));
        assertEquals(HttpStatus.FORBIDDEN, notTheirs.getStatus(), "the history of attempts is staff's");
    }

    // ── the answer ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the enquiry confirms the payment, reads the receipt out of the narration, and writes the statement")
    void theEnquiryConfirmsAndReadsTheReceipt() {
        IntentResponse intent = prompts.prompt(new PromptRequest(booking.id(), null, null, null));
        statusAnswer.set("""
                {"MessageReference":"%s","MessageCode":"0","MessageDescription":"Success",
                 "TransactionMetadata":{"Items":[
                   {"Name":"Amount","Value":"950000"},
                   {"Name":"Narration","Value":"Coop Test HODI~TIP6V5IRAG~2026-09-20 10:05"}]}}"""
                .formatted(intent.reference()));

        String state = stk.query(HashIdUtil.decodeId(intent.id()), true, "tester");

        assertEquals(PaymentIntent.SUCCEEDED, state);
        PaymentIntent settled = reload(intent.id());
        assertEquals("TIP6V5IRAG", settled.getReceipt(), "second field of the narration");
        assertNotNull(settled.getPaymentId());
        CoopStatement evidence = statements.findById(settled.getStatementId()).orElseThrow();
        assertEquals("TIP6V5IRAG", evidence.getRefNo(), "keyed on the receipt a later notification would quote");
        assertEquals(CoopIntentSettlement.STK_QUERY, evidence.getTransType());
        assertNotNull(evidence.getRawPayload(), "the bank's whole answer, kept");
        assertEquals(1, paymentsOnTheBooking());
        assertTrue(tokenCalls.get() <= 1, "the enquiry reused the prompt's token; asked " + tokenCalls.get() + " times");
    }

    @Test
    @DisplayName("cancelled by the customer is failed; an answer with no code is still pending and counts an attempt")
    void cancelledFailsAndSilenceWaits() {
        IntentResponse intent = prompts.prompt(new PromptRequest(booking.id(), null, null, null));
        Long id = HashIdUtil.decodeId(intent.id());

        statusAnswer.set("{}");
        assertEquals(PaymentIntent.PROCESSING, stk.query(id, true, null));
        assertEquals(1, reload(intent.id()).getStatusQueryAttempts(), "the sweep's attempt is counted");
        assertEquals(PaymentIntent.PROCESSING, stk.query(id, false, "operator"));
        assertEquals(1, reload(intent.id()).getStatusQueryAttempts(), "a person's is not");

        statusAnswer.set("""
                {"MessageReference":"%s","MessageCode":"1032","MessageDescription":"Request Cancelled by user."}"""
                .formatted(intent.reference()));
        assertEquals(PaymentIntent.FAILED, stk.query(id, true, null));
        assertTrue(reload(intent.id()).getProcessingReason().contains("Cancelled"),
                reload(intent.id()).getProcessingReason());
        assertEquals(0, paymentsOnTheBooking());
    }

    @Test
    @DisplayName("a callback in either envelope settles the prompt once; an untrusted one settles nothing")
    void callbacksSettleOnce() throws Exception {
        IntentResponse intent = prompts.prompt(new PromptRequest(booking.id(), null, null, null));

        // Untrusted first: matched, ignored.
        stk.callback(json("""
                {"MessageReference":"%s","MessageCode":"0","MessageDescription":"Processed"}"""
                .formatted(intent.reference())), false);
        assertEquals(PaymentIntent.PROCESSING, reload(intent.id()).getState());

        // Co-op's envelope, trusted.
        stk.callback(json("""
                {"MessageReference":"%s","MessageCode":"0","MessageDescription":"Processed"}"""
                .formatted(intent.reference())), true);
        assertEquals(PaymentIntent.SUCCEEDED, reload(intent.id()).getState());
        assertEquals(1, paymentsOnTheBooking());

        // M-Pesa's envelope about the same prompt, arriving later. Linked, not credited again.
        stk.callback(json("""
                {"Body":{"stkCallback":{"MerchantRequestID":"m1","CheckoutRequestID":"%s","ResultCode":0,
                 "ResultDesc":"The service request is processed successfully.",
                 "CallbackMetadata":{"Item":[{"Name":"Amount","Value":950000},
                   {"Name":"MpesaReceiptNumber","Value":"TIP6V5IRAK"},{"Name":"PhoneNumber","Value":254712000111}]}}}}"""
                .formatted(intent.reference())), true);
        assertEquals(1, paymentsOnTheBooking(), "the same money reported twice moves a balance once");

        // A second prompt, declined on the handset, in M-Pesa's envelope.
        IntentResponse declined = prompts.prompt(new PromptRequest(booking.id(), new BigDecimal("500"), null, null));
        stk.callback(json("""
                {"Body":{"stkCallback":{"CheckoutRequestID":"%s","ResultCode":1032,
                 "ResultDesc":"Request cancelled by user"}}}""".formatted(declined.reference())), true);
        assertEquals(PaymentIntent.FAILED, reload(declined.id()).getState());
        assertEquals(1, paymentsOnTheBooking());

        assertNull(stk.callback(json("{\"MessageReference\":\"IN-NOBODY\",\"MessageCode\":\"0\"}"), true),
                "a callback about nothing we asked for is nobody's");
    }

    @Test
    @DisplayName("the sweep chases an unanswered prompt up to the cap and then leaves it to a person")
    void theSweepChasesToTheCap() {
        IntentResponse intent = prompts.prompt(new PromptRequest(booking.id(), null, null, null));
        statusAnswer.set("{\"MessageCode\":\"S_001\",\"MessageDescription\":\"PROCESSING\"}");
        int cap = configs.getInt(com.hodi.enums.ConfigKey.COOP_STATUS_QUERY_MAX_ATTEMPTS);

        for (int i = 0; i < cap + 2; i++) sweep.chaseUnanswered();

        PaymentIntent waiting = reload(intent.id());
        assertEquals(PaymentIntent.PROCESSING, waiting.getState(), "silence is never failure");
        assertEquals(cap, waiting.getStatusQueryAttempts(), "and the bank is not asked forever");
    }

    // ── who may ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the buyer may prompt their own phone for their own booking; nobody else's, and not a seller's staff")
    void theBuyerMayAskForTheirOwn() {
        signInAsBuyer(buyerUser);
        String mine = HashIdUtil.encodeId(bookingRaw);
        IntentResponse intent = prompts.prompt(new PromptRequest(mine, null, "0700000000", null));
        assertEquals(PaymentIntent.PROCESSING, intent.state());
        assertEquals("+254712000111", intent.phoneNo(), "their own phone, whatever the request said");
        assertEquals(intent.id(), prompts.find(intent.id()).id(), "and they may poll it");
        assertThrows(HodiException.class, () -> prompts.forBooking(mine), "but not list every attempt");

        signInAsBuyer(buyerUser + 100_000);
        String notMine = HashIdUtil.encodeId(bookingRaw);
        assertThrows(RuntimeException.class, () -> prompts.prompt(new PromptRequest(notMine, null, null, null)));

        signInAsSeller();
        String asSeller = HashIdUtil.encodeId(bookingRaw);
        HodiException refused = assertThrows(HodiException.class,
                () -> prompts.prompt(new PromptRequest(asSeller, null, null, null)));
        assertEquals(HttpStatus.FORBIDDEN, refused.getStatus());
    }
}
