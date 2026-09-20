package com.hodi.modules.disbursements;

import com.hodi.common.AppConstant;
import com.hodi.common.EncryptionUtil;
import com.hodi.common.exception.HodiException;
import com.hodi.infra.coop.CoopRoutes;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.approvals.ApprovalService.DecisionRequest;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.disbursements.DisbursementDtos.DisbursementResponse;
import com.hodi.modules.disbursements.DisbursementDtos.ProposeRequest;
import com.hodi.modules.disbursements.DisbursementDtos.ValidateRequest;
import com.hodi.modules.disbursements.DisbursementDtos.ValidateResponse;
import com.hodi.modules.payments.PaymentAccount;
import com.hodi.modules.payments.PaymentAccountRepository;
import com.hodi.modules.payments.PaymentType;
import com.hodi.modules.payments.PaymentTypeRepository;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.users.User;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.UserPrincipal;
import com.sun.net.httpserver.HttpExchange;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Money out, against a Co-op that is a small HTTP server in this JVM.
 *
 * <h2>What is being protected</h2>
 *
 * <p>A disbursement is the one flow here with no undo, so the tests are about the preconditions: a row
 * exists only once the bank has named the account; the maker cannot release their own; a release sends
 * exactly once and never again, whatever else happens; and the bank's answer is read per leg, so an
 * accepted envelope never reads as money moved.
 *
 * <h2>Why this class commits</h2>
 *
 * <p>The send runs after the approval commits and every settlement is {@code REQUIRES_NEW}, so the rows
 * commit under the test and are cleaned by hand. The Co-op settings are changed for the run and put back.
 */
@SpringBootTest
class DisbursementIT {

    @Autowired DisbursementService service;
    @Autowired DisbursementSweep sweep;
    @Autowired DisbursementRepository rows;
    @Autowired ApprovalService approvals;
    @Autowired PaymentAccountRepository accounts;
    @Autowired PaymentTypeRepository types;
    @Autowired ConfigurationService configs;
    @Autowired EncryptionUtil crypto;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private static final String SOURCE_NAME = "Disbursement source (IT)";
    private static final String PURPOSE_MARK = "IT: ";

    // ── the bank ──────────────────────────────────────────────────────────────

    private static HttpServer coop;
    private static final AtomicReference<String> validationAnswer = new AtomicReference<>();
    private static final AtomicReference<String> sendAnswer = new AtomicReference<>();
    private static final AtomicReference<String> statusAnswer = new AtomicReference<>();
    private static final List<String> validationBodies = new CopyOnWriteArrayList<>();
    private static final List<String> sendBodies = new CopyOnWriteArrayList<>();
    private static final List<String> statusBodies = new CopyOnWriteArrayList<>();

    private static final String HELD_BY_JANE = """
            {"MessageReference":"x","MessageCode":"0","MessageDescription":"SUCCESS",
             "RecipientName":"JANE W MWANGI","AccountNumber":"01102901454001"}""";
    private static final String ACCEPTED = """
            {"MessageReference":"%s","MessageDateTime":"2026-09-20T10:00:00","MessageCode":"0",
             "MessageDescription":"REQUEST ACCEPTED FOR PROCESSING"}""";

    @BeforeAll
    static void startTheBank() throws IOException {
        coop = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        coop.createContext("/token", exchange -> {
            exchange.getRequestBody().readAllBytes();
            answer(exchange, "{\"access_token\":\"tok\",\"expires_in\":3600,\"token_type\":\"Bearer\"}");
        });
        coop.createContext("/Enquiry/Validation/IPSL/1.0.0/", exchange -> {
            validationBodies.add(body(exchange));
            answer(exchange, validationAnswer.get() == null ? HELD_BY_JANE : validationAnswer.get());
        });
        coop.createContext("/FundsTransfer/External/A2A/PesaLink_v2/2.0.0", exchange -> {
            String body = body(exchange);
            sendBodies.add(body);
            String reference = body.replaceAll("(?s).*\"MessageReference\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            answer(exchange, sendAnswer.get() == null ? ACCEPTED.formatted(reference) : sendAnswer.get());
        });
        coop.createContext("/Enquiry/TransactionStatus_V3/3.0.0/", exchange -> {
            statusBodies.add(body(exchange));
            answer(exchange, statusAnswer.get() == null ? "{}" : statusAnswer.get());
        });
        coop.start();
    }

    @AfterAll
    static void stopTheBank() {
        if (coop != null) coop.stop(0);
    }

    private static String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void answer(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String bankUrl() {
        return "http://127.0.0.1:" + coop.getAddress().getPort();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private long makerUser;
    private long checkerUser;
    private Long tenantId;
    private String tenantName;
    private PaymentAccount source;
    private final Map<String, String> previousConfig = new HashMap<>();
    private final Map<String, Integer> previousTypeStatus = new HashMap<>();

    @BeforeEach
    void build() {
        purge();
        validationAnswer.set(null);
        sendAnswer.set(null);
        statusAnswer.set(null);
        validationBodies.clear();
        sendBodies.clear();
        statusBodies.clear();

        makerUser = jdbc.queryForObject("select id from users where status <> 5 order by id limit 1", Long.class);
        checkerUser = jdbc.queryForObject("select id from users where status <> 5 order by id offset 1 limit 1",
                Long.class);
        tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        tenantName = jdbc.queryForObject("select name from tenants where id = ?", String.class, tenantId);

        remember("coop.base.url", "coop.token.path", "coop.consumer.key", "coop.consumer.secret",
                "coop.user.id", "platform.public.url", "coop.status.query.max.attempts");
        set("coop.base.url", bankUrl(), false);
        set("coop.token.path", "/token", false);
        set("coop.consumer.key", "consumer-key", false);
        set("coop.consumer.secret", "consumer-secret", true);
        set("coop.user.id", "hodi-it", false);
        set("platform.public.url", "https://hodi.example.invalid/", false);
        set("coop.status.query.max.attempts", "2", false);
        for (String channel : List.of("COOP_PESALINK", "COOP_FT_STATUS", "COOP_ACCOUNT_VALIDATION")) {
            previousTypeStatus.put(channel, jdbc.queryForObject(
                    "select status from payment_types where provider_type = ?", Integer.class, channel));
            jdbc.update("update payment_types set status = 1 where provider_type = ?", channel);
        }
        configs.evictAll();

        // The platform's own PesaLink account, live: where the money leaves.
        PaymentType pesalink = types.findByProviderType("COOP_PESALINK").orElseThrow();
        PaymentAccount row = PaymentAccount.builder()
                .accountNo("01100" + Long.toString(System.nanoTime() % 100_000_000L))
                .accountName(SOURCE_NAME).config(Map.of()).createdBy("test").build();
        row.stampChannel(pesalink);
        source = accounts.save(row);

        signInAsMaker();
    }

    @AfterEach
    void restore() {
        try {
            purge();
            previousConfig.forEach((key, value) -> jdbc.update(
                    "update configurations set config_value = ? where config_key = ?", value, key));
            previousTypeStatus.forEach((channel, status) -> jdbc.update(
                    "update payment_types set status = ? where provider_type = ?", status, channel));
            configs.evictAll();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void purge() {
        jdbc.update("delete from approval_workflows where entity_type = 'DISBURSEMENT' and entity_id in "
                + "(select id from disbursements where purpose like ?)", PURPOSE_MARK + "%");
        jdbc.update("delete from disbursements where purpose like ?", PURPOSE_MARK + "%");
        jdbc.update("delete from payment_accounts where account_name = ?", SOURCE_NAME);
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

    private void signInAsMaker() {
        signInAsPlatform(makerUser, "disburse-maker", "DISBURSEMENTS_VIEW", "DISBURSEMENTS_MAKE");
    }

    private void signInAsChecker() {
        signInAsPlatform(checkerUser, "disburse-checker", "DISBURSEMENTS_VIEW", "DISBURSEMENTS_APPROVE",
                "APPROVALS_VIEW");
    }

    private void signInAsPlatform(long userId, String username, String... permissions) {
        User user = User.builder().id(userId).username(username).password("x")
                .email(username + "@example.invalid").firstName("Dis").lastName("Burse")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(userId).userId(userId)
                .profileType(AppConstant.ACTOR_PLATFORM).userTypeCode("SUPER_ADMIN")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile, Set.of(permissions), List.of(), true, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private ProposeRequest toJane(String amount, String purpose) {
        return new ProposeRequest(Disbursement.PAYEE_OTHER, null, "Jane Mwangi Contractors", "11",
                "01102901454001", new BigDecimal(amount), PURPOSE_MARK + purpose, null);
    }

    /** Proposes as the maker and returns the raw id, decoded while the maker's salt is still in force. */
    private Long propose(ProposeRequest request) {
        signInAsMaker();
        DisbursementResponse proposed = service.propose(request);
        return HashIdUtil.decodeId(proposed.id());
    }

    private void approve(Long id) {
        signInAsChecker();
        approvals.decideFor(AppConstant.APPROVAL_ENTITY_DISBURSEMENT, id, AppConstant.APPROVAL_ACTION_SEND,
                new DecisionRequest(AppConstant.APPROVAL_APPROVED, null));
    }

    private Disbursement reload(Long id) {
        return rows.findById(id).orElseThrow();
    }

    private Map<String, Object> json(String text) throws Exception {
        return mapper.readValue(text, Map.class);
    }

    private String settled(String reference, String legCode, String legText, String transactionId) {
        return """
                {"MessageReference":"%s","MessageCode":"0","MessageDescription":"SUCCESS",
                 "Source":{"AccountNumber":"%s","ResponseCode":"0","ResponseDescription":"Success"},
                 "Destinations":[{"ReferenceNumber":"%s_1","ResponseCode":"%s","ResponseDescription":"%s",
                   "TransactionID":"%s"}]}"""
                .formatted(reference, source.getAccountNo(), reference, legCode, legText, transactionId);
    }

    // ── proposing ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a proposal is written in the bank's name for the account, waits for a second person, and sends nothing")
    void aProposalWaitsInTheBanksName() {
        ValidateResponse asked = service.validate(new ValidateRequest("11", "01102901454001"));
        assertTrue(asked.valid());
        assertEquals("JANE W MWANGI", asked.holderName());
        assertTrue(validationBodies.get(0).contains("\"RecipientBankIdentifier\":\"0011\""),
                "Co-op's validation wants four digits: " + validationBodies.get(0));

        Long id = propose(new ProposeRequest(Disbursement.PAYEE_SELLER, HashIdUtil.encodeId(tenantId), null,
                null, " 01102901454001 ", new BigDecimal("450000"), PURPOSE_MARK + "Stage 2 certificate", null));

        Disbursement row = reload(id);
        assertEquals(Disbursement.AWAITING_APPROVAL, row.getState());
        assertEquals("JANE W MWANGI", row.getValidatedName(), "the name the server got from the bank, not the form's");
        assertEquals("01102901454001", row.getAccountNo(), "trimmed");
        assertEquals("0011", row.getBankCode(), "Co-op itself, when no bank was named");
        assertEquals(tenantName, row.getPayeeName(), "a seller organisation is named by its row");
        assertEquals(tenantId, row.getTenantId());
        assertEquals(0, new BigDecimal("450000.00").compareTo(row.getAmount()));
        assertEquals("disburse-maker", row.getMadeBy());
        assertEquals(source.getId(), row.getSourceAccountId());
        assertTrue(row.getReference().startsWith("DB"));
        assertEquals(2, validationBodies.size(), "validated again on the server, whatever the form showed");
        assertTrue(approvals.pendingFor(AppConstant.APPROVAL_ENTITY_DISBURSEMENT, id,
                AppConstant.APPROVAL_ACTION_SEND).isPresent(), "in the checker's queue");
        assertTrue(sendBodies.isEmpty(), "nothing leaves on a proposal");
    }

    @Test
    @DisplayName("an account the bank cannot name is refused before a row exists")
    void anUnnamedAccountIsRefused() {
        validationAnswer.set("{\"MessageCode\":\"1\",\"MessageDescription\":\"Account not found\"}");
        HodiException refused = assertThrows(HodiException.class, () -> propose(toJane("1000", "Nobody")));
        assertEquals(HttpStatus.BAD_REQUEST, refused.getStatus());
        assertTrue(refused.getMessage().contains("Account not found"), refused.getMessage());

        // Confirmed, but named nobody: the same refusal, because a blank name is nothing to check against.
        validationAnswer.set("{\"MessageCode\":\"0\",\"MessageDescription\":\"SUCCESS\"}");
        assertThrows(HodiException.class, () -> propose(toJane("1000", "Nobody")));

        assertEquals(0, jdbc.queryForObject("select count(*) from disbursements where purpose like ?",
                Long.class, PURPOSE_MARK + "%"));
        assertTrue(sendBodies.isEmpty());
    }

    @Test
    @DisplayName("who is paid must be said: a seller by its row, anybody else by name, and never nothing")
    void thePayeeMustBeNamed() {
        assertThrows(HodiException.class, () -> propose(new ProposeRequest(Disbursement.PAYEE_OTHER, null, " ",
                "11", "01102901454001", new BigDecimal("1000"), PURPOSE_MARK + "x", null)));
        assertThrows(HodiException.class, () -> propose(new ProposeRequest("SOMEBODY", null, "x",
                "11", "01102901454001", new BigDecimal("1000"), PURPOSE_MARK + "x", null)));
        assertThrows(HodiException.class, () -> propose(new ProposeRequest(Disbursement.PAYEE_OTHER, null, "x",
                "11", "01102901454001", new BigDecimal("0"), PURPOSE_MARK + "x", null)));
        assertTrue(validationBodies.isEmpty(), "the bank is not asked about a proposal that cannot stand");
    }

    // ── the decision ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the maker cannot release their own transfer")
    void theMakerCannotReleaseTheirOwn() {
        Long id = propose(toJane("25000", "Own release"));

        HodiException refused = assertThrows(HodiException.class, () -> approvals.decideFor(
                AppConstant.APPROVAL_ENTITY_DISBURSEMENT, id, AppConstant.APPROVAL_ACTION_SEND,
                new DecisionRequest(AppConstant.APPROVAL_APPROVED, null)));
        assertNotNull(refused.getMessage());
        assertEquals(Disbursement.AWAITING_APPROVAL, reload(id).getState());
        assertTrue(sendBodies.isEmpty(), "and so nothing was sent");
    }

    @Test
    @DisplayName("a refusal records why and sends nothing, then and later")
    void aRefusalSendsNothing() {
        Long id = propose(toJane("25000", "Refused"));
        signInAsChecker();
        approvals.decideFor(AppConstant.APPROVAL_ENTITY_DISBURSEMENT, id, AppConstant.APPROVAL_ACTION_SEND,
                new DecisionRequest(AppConstant.APPROVAL_REJECTED, "Invoice not on file"));

        Disbursement row = reload(id);
        assertEquals(Disbursement.REFUSED, row.getState());
        assertTrue(row.isTerminal());
        assertEquals("disburse-checker", row.getCheckedBy());
        assertEquals("Invoice not on file", row.getDecisionReason());
        assertTrue(row.getProcessingReason().contains("Invoice not on file"), row.getProcessingReason());

        service.dispatch(id);
        sweep.pass();
        assertTrue(sendBodies.isEmpty(), "a refused row is never sent, by anybody");
        assertEquals(Disbursement.REFUSED, reload(id).getState());
    }

    // ── sending ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("approval sends once, with our reference and the callback address, and never sends again")
    void approvalSendsOnce() throws Exception {
        Long id = propose(toJane("450000", "Sent once"));
        approve(id);

        Disbursement row = reload(id);
        assertEquals(Disbursement.SENT, row.getState(), row.getProcessingReason());
        assertEquals("disburse-checker", row.getCheckedBy());
        assertNotNull(row.getSentAt());
        assertNull(row.getSettledAt(), "accepted for processing is not paid");
        assertFalse(row.isTerminal());
        assertTrue(row.getProcessingReason().contains("Accepted"), row.getProcessingReason());
        assertNotNull(row.getRawResponse(), "the bank's acknowledgement, kept verbatim");

        assertEquals(1, sendBodies.size());
        Map<String, Object> sent = json(sendBodies.get(0));
        assertEquals(row.getReference(), sent.get("MessageReference"));
        assertEquals("https://hodi.example.invalid" + CoopRoutes.FT_CALLBACK, sent.get("CallBackUrl"));
        Map<String, Object> from = (Map<String, Object>) sent.get("Source");
        assertEquals(source.getAccountNo(), from.get("AccountNumber"), "the platform's own account");
        Map<String, Object> to = ((List<Map<String, Object>>) sent.get("Destinations")).get(0);
        assertEquals("01102901454001", to.get("AccountNumber"));
        assertEquals("11", to.get("BankCode"), "two digits on the transfer, four on the validation");
        assertEquals(450000, ((Number) to.get("Amount")).intValue());

        // Nothing re-sends: not a second dispatch, not the sweep, not a second approval attempt.
        service.dispatch(id);
        sweep.pass();
        signInAsChecker();
        assertThrows(HodiException.class, () -> approvals.decideFor(AppConstant.APPROVAL_ENTITY_DISBURSEMENT, id,
                AppConstant.APPROVAL_ACTION_SEND, new DecisionRequest(AppConstant.APPROVAL_APPROVED, null)));
        assertEquals(1, sendBodies.size(), "one approval, one request to the bank, ever");
    }

    @Test
    @DisplayName("an envelope that refuses the transfer fails it there and then, with the bank's reason")
    void aRefusedEnvelopeFailsAtOnce() {
        sendAnswer.set("{\"MessageReference\":\"x\",\"MessageCode\":\"2\",\"MessageDescription\":\"FULL FAILURE\","
                + "\"Destinations\":[{\"ResponseCode\":\"-5\",\"ResponseDescription\":\"Insufficient balance\","
                + "\"TransactionID\":\"NULL\"}]}");
        Long id = propose(toJane("450000", "Refused envelope"));
        approve(id);

        Disbursement row = reload(id);
        assertEquals(Disbursement.FAILED, row.getState());
        assertTrue(row.isTerminal());
        assertNotNull(row.getSettledAt());
        assertEquals("-5", row.getResponseCode(), "the leg's code, which is the one about the money");
        assertNull(row.getBankReference(), "Co-op's literal NULL is no reference");
        assertTrue(row.getProcessingReason().contains("FULL FAILURE"), row.getProcessingReason());
        assertEquals(1, sendBodies.size());
    }

    @Test
    @DisplayName("a firewall's HTML page means nothing reached the bank, so the transfer fails without a send")
    void aFirewallPageIsNeverSent() {
        sendAnswer.set("<html><head><title>Request Rejected</title></head><body>blocked</body></html>");
        Long id = propose(toJane("1000", "Firewall"));
        approve(id);

        Disbursement row = reload(id);
        assertEquals(Disbursement.FAILED, row.getState(), row.getProcessingReason());
        assertTrue(row.getProcessingReason().startsWith("Not sent"), row.getProcessingReason());
    }

    // ── the answer ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the enquiry reads the leg: done is paid with the bank's id, a code is failed, no leg is still waiting")
    void theEnquiryReadsTheLeg() {
        Long paid = propose(toJane("1000", "Enquiry paid"));
        approve(paid);
        Long failed = propose(toJane("2000", "Enquiry failed"));
        approve(failed);
        Long waiting = propose(toJane("3000", "Enquiry waiting"));
        approve(waiting);
        String paidRef = reload(paid).getReference();
        String failedRef = reload(failed).getReference();

        // No legs yet: an accepted envelope says nothing about the money. Counted for the sweep, not for a person.
        statusAnswer.set("{\"MessageReference\":\"x\",\"MessageCode\":\"0\",\"MessageDescription\":\"SUCCESS\"}");
        assertEquals(Disbursement.SENT, service.query(waiting, true, null));
        assertEquals(1, reload(waiting).getStatusQueryAttempts());
        assertEquals(Disbursement.SENT, service.query(waiting, false, "operator"));
        assertEquals(1, reload(waiting).getStatusQueryAttempts(), "a person's question is not counted");
        assertTrue(reload(waiting).getProcessingReason().contains("still in progress"));

        statusAnswer.set(settled(paidRef, "0", "Success", "FT26092000012345"));
        assertEquals(Disbursement.SUCCEEDED, service.query(paid, false, "operator"));
        Disbursement done = reload(paid);
        assertEquals("FT26092000012345", done.getBankReference());
        assertEquals("0", done.getResponseCode());
        assertNotNull(done.getSettledAt());
        assertEquals("operator", done.getUpdatedBy());
        assertTrue(done.getProcessingReason().startsWith("Confirmed by Co-op"), done.getProcessingReason());

        statusAnswer.set(settled(failedRef, "-5", "Insufficient balance", "NULL"));
        assertEquals(Disbursement.FAILED, service.query(failed, true, null));
        Disbursement lost = reload(failed);
        assertEquals("-5", lost.getResponseCode());
        assertNull(lost.getBankReference());
        assertTrue(lost.getProcessingReason().contains("Insufficient balance"), lost.getProcessingReason());

        assertTrue(statusBodies.get(0).contains("\"UserID\":\"hodi-it\""), statusBodies.get(0));
        assertEquals(Disbursement.SUCCEEDED, service.query(paid, true, null), "a settled row is not asked about again");
        assertEquals(4, statusBodies.size());
    }

    @Test
    @DisplayName("a callback settles the transfer once; an untrusted one settles nothing; a success is never reversed")
    void callbacksSettleOnce() throws Exception {
        Long id = propose(toJane("1000", "Callback"));
        approve(id);
        String reference = reload(id).getReference();

        assertNotNull(service.callback(json(settled(reference, "0", "Success", "FT1")), false), "matched");
        assertEquals(Disbursement.SENT, reload(id).getState(), "and ignored: nobody authenticated");
        assertNull(reload(id).getBankReference());

        Disbursement after = service.callback(json(settled(reference, "0", "Success", "FT1")), true);
        assertEquals(Disbursement.SUCCEEDED, after.getState());
        assertEquals("FT1", after.getBankReference());
        assertEquals("callback", after.getUpdatedBy());

        // The bank's id now correlates too, and a later contradiction changes nothing.
        service.callback(json(settled("FT1", "-5", "Insufficient balance", "FT1")), true);
        assertEquals(Disbursement.SUCCEEDED, reload(id).getState(), "money that moved is not un-moved by a message");

        assertNull(service.callback(json("{\"MessageReference\":\"DB-NOBODY\",\"MessageCode\":\"0\"}"), true),
                "a callback about nothing we sent is nobody's");
    }

    // ── the sweep ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the sweep sends an approved row the hook never reached, chases out rows past their deadline to the cap, and re-sends nothing")
    void theSweepSendsOnceAndChasesToTheCap() {
        // Approved by a process that died before the after-commit hook: APPROVED, checked a while ago, never sent.
        Long orphan = propose(toJane("1000", "Sweep orphan"));
        jdbc.update("update disbursements set state = 'APPROVED', checked_by = 'disburse-checker', "
                + "checked_at = now() - interval '10 minutes' where id = ?", orphan);
        // Approved just now: inside the grace, so the hook still owns it.
        Long fresh = propose(toJane("1000", "Sweep fresh"));
        jdbc.update("update disbursements set state = 'APPROVED', checked_by = 'disburse-checker', "
                + "checked_at = now() where id = ?", fresh);

        sweep.pass();

        assertEquals(Disbursement.SENT, reload(orphan).getState(), reload(orphan).getProcessingReason());
        assertEquals(Disbursement.APPROVED, reload(fresh).getState(), "not the sweep's yet");
        assertEquals(1, sendBodies.size());

        // Out, unanswered, and past its deadline: asked about, up to the cap, and then left to a person.
        statusAnswer.set("{\"MessageCode\":\"S_001\",\"MessageDescription\":\"PROCESSING\"}");
        jdbc.update("update disbursements set sent_at = now() - interval '1 day' where id = ?", orphan);
        int cap = configs.getInt(com.hodi.enums.ConfigKey.COOP_STATUS_QUERY_MAX_ATTEMPTS);
        for (int i = 0; i < cap + 2; i++) sweep.pass();

        Disbursement chased = reload(orphan);
        assertEquals(Disbursement.SENT, chased.getState(), "silence is never failure");
        assertEquals(cap, chased.getStatusQueryAttempts(), "and the bank is not asked forever");
        assertEquals(cap, statusBodies.size());
        assertEquals(1, sendBodies.size(), "chasing is asking, never sending again");

        // A SENDING row — claimed, then the process died — is chased the same way, never re-sent.
        jdbc.update("update disbursements set state = 'SENDING', status_query_attempts = 0 where id = ?", orphan);
        statusAnswer.set(settled(chased.getReference(), "0", "Success", "FT-SWEEP"));
        sweep.pass();
        assertEquals(Disbursement.SUCCEEDED, reload(orphan).getState());
        assertEquals("FT-SWEEP", reload(orphan).getBankReference());
        assertEquals(1, sendBodies.size());
    }

    // ── reading ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the list filters by state and searches the bank's name; the detail carries the raw answer")
    void listingAndDetail() {
        Long a = propose(toJane("1000", "List a"));
        Long b = propose(toJane("2000", "List b"));
        approve(b);
        signInAsMaker();

        DisbursementDtos.ListRequest awaiting = new DisbursementDtos.ListRequest();
        awaiting.setState("awaiting_approval");
        awaiting.setSearch("jane w mwangi");
        List<DisbursementResponse> found = service.list(awaiting).getContent();
        assertTrue(found.stream().anyMatch(d -> d.reference().equals(reload(a).getReference())));
        assertTrue(found.stream().noneMatch(d -> d.reference().equals(reload(b).getReference())));
        assertEquals("Awaiting approval", found.get(0).stateLabel());

        DisbursementDtos.DisbursementDetail detail = service.find(HashIdUtil.encodeId(b));
        assertEquals(Disbursement.SENT, detail.disbursement().state());
        assertEquals(source.getAccountNo(), detail.disbursement().sourceAccountNo());
        assertNotNull(detail.rawResponse());
        assertTrue(detail.rawResponse().contains("REQUEST ACCEPTED FOR PROCESSING"));
    }
}
