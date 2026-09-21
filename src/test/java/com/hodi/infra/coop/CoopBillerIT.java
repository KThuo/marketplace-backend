package com.hodi.infra.coop;

import com.hodi.common.AppConstant;
import com.hodi.common.EncryptionUtil;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingDtos.InstalmentLine;
import com.hodi.modules.bookings.BookingService;
import com.hodi.modules.developments.*;
import com.hodi.modules.payments.PaymentAccount;
import com.hodi.modules.payments.PaymentAccountRepository;
import com.hodi.modules.payments.PaymentRepository;
import com.hodi.modules.payments.PaymentType;
import com.hodi.modules.payments.PaymentTypeRepository;
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
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Co-op's biller: validation, then advice, in the bank's own shapes.
 *
 * <h2>Why this class commits</h2>
 *
 * <p>{@code advise} is {@code REQUIRES_NEW} — the record of money arriving must survive on its own — so it
 * takes its own connection and cannot see uncommitted fixtures. Hence the hand-written clean-up.
 *
 * <h2>What is worth testing</h2>
 *
 * <p>The bodies are the bank's Postman examples, field for field, so a mismatch in a name shows here and
 * not on the day Co-op posts. The credentials are the boundary: wrong ones are refused, unset ones refuse
 * everybody. And the two answers a bank keeps asking for — what is owed on a reference, and whether a
 * transaction it advised twice was recorded twice.
 */
@SpringBootTest
class CoopBillerIT {

    @Autowired CoopBillerService biller;
    @Autowired BookingService bookings;
    @Autowired CoopStatementRepository statements;
    @Autowired PaymentAccountRepository accounts;
    @Autowired PaymentTypeRepository types;
    @Autowired PaymentRepository payments;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitRepository units;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired EncryptionUtil crypto;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private static final String DEVELOPMENT = "Biller Heights";
    private static final String SERVICE = "BREEZE ESTATE";
    private static final String CONNECTION_ID = "TEST";
    private static final String PASSWORD = "CoopTest";

    private Long tenantId;
    private Development development;
    private BookingResponse booking;
    private PaymentAccount account;
    private String institution;

    @BeforeEach
    void signInAndBuild() {
        purgeStaleFixtures();
        tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        User user = User.builder().id(7L).username("biller-test").password("x")
                .email("b@example.invalid").firstName("Bil").lastName("Ler")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(7L).userId(7L)
                .profileType(AppConstant.ACTOR_PLATFORM).userTypeCode("SUPER_ADMIN")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("BOOKINGS_MANAGE", "UNITS_SELL", "UNITS_MANAGE", "PAYMENTS_RECEIVE"),
                List.of(), true, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        jdbc.update("update payment_types set status = 1 where provider_type = 'COOP_BILLER'");

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
                .unitTypeId(typology.getId()).unitLabel("B-1-01")
                .saleState(AppConstant.UNIT_AVAILABLE)
                .constructionStatus(AppConstant.BUILD_PLANNED).build());
        booking = bookings.create(HashIdUtil.encodeId(development.getId()), new CreateBookingRequest(
                HashIdUtil.encodeId(unit.getId()), "Asha Mwangi", "+254712000111", null, null,
                new BigDecimal("9500000"), new BigDecimal("950000"), AppConstant.PLAN_INSTALMENTS, 14, null,
                List.of(new InstalmentLine("Deposit", LocalDate.now().minusDays(10), new BigDecimal("950000")),
                        new InstalmentLine("Balance", LocalDate.now().plusDays(90),
                                new BigDecimal("8550000")))));
        // The code a booking is given is random; this test quotes a known one, so it is pinned on the booking.
        jdbc.update("update unit_bookings set pay_reference = ? where reference = ?", "B1K1", booking.reference());

        // The biller, as the account screen would create it: keyed on the pair Co-op advises with, the
        // password encrypted at rest.
        institution = "21" + RrnGenerator.generate("A").substring(2, 9);
        PaymentType channel = types.findByProviderType("COOP_BILLER").orElseThrow();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("institutionCode", institution);
        config.put("serviceName", SERVICE);
        config.put("institutionName", "Breeze Estate Ltd");
        config.put("connectionID", CONNECTION_ID);
        config.put("connectionPassword", crypto.encryptString(PASSWORD));
        PaymentAccount row = PaymentAccount.builder()
                .accountNo(CoopBillerService.accountKeyFor(channel, institution, SERVICE))
                .accountName("Breeze Estate biller").tenantId(tenantId).config(config).createdBy("test").build();
        row.stampChannel(channel);
        account = accounts.save(row);
    }

    private void purgeStaleFixtures() {
        jdbc.update("update coop_statements set state = 'UNMAPPED', mapped_payment_id = null, "
                + "mapped_booking_id = null, mapped_at = null where mapped_payment_id in "
                + "(select id from payments where development_id in "
                + "(select id from developments where name = ?))", DEVELOPMENT);
        jdbc.update("delete from payments where development_id in "
                + "(select id from developments where name = ?)", DEVELOPMENT);
        jdbc.update("delete from coop_statements where payment_account_id in "
                + "(select id from payment_accounts where account_name = 'Breeze Estate biller')");
        jdbc.update("delete from booking_instalments where booking_id in (select id from unit_bookings "
                + "where development_id in (select id from developments where name = ?))", DEVELOPMENT);
        jdbc.update("delete from unit_bookings where development_id in "
                + "(select id from developments where name = ?)", DEVELOPMENT);
        jdbc.update("delete from properties where listing_kind = 'UNIT' and development_id in "
                + "(select id from developments where name = ?)", DEVELOPMENT);
        jdbc.update("delete from development_unit_types where development_id in "
                + "(select id from developments where name = ?)", DEVELOPMENT);
        jdbc.update("delete from developments where name = ?", DEVELOPMENT);
        jdbc.update("delete from payment_accounts where account_name = 'Breeze Estate biller'");
    }

    @AfterEach
    void cleanUp() {
        try {
            purgeStaleFixtures();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ── the bank's bodies ─────────────────────────────────────────────────────

    private Map<String, Object> validation(String reference, String password) throws Exception {
        return mapper.readValue("""
                {
                  "header": {
                    "connectionID": "%s",
                    "connectionPassword": "%s",
                    "messageID": "d36aa236-5651-46bb-abcs-0a3ff30dbb",
                    "serviceName": "%s"
                  },
                  "request": {
                    "TransactionReferenceCode": "%s",
                    "TransactionDate": "2026-05-28T14:43:19.763+03:00",
                    "InstitutionCode": "%s"
                  }
                }
                """.formatted(CONNECTION_ID, password, SERVICE, reference, institution), Map.class);
    }

    private Map<String, Object> advice(String transactionRef, String reference, String amount) throws Exception {
        return mapper.readValue("""
                {
                  "header": {
                    "connectionID": "%s",
                    "connectionPassword": "%s",
                    "messageID": "d36aa236-5651-46bb-abcs-0a3ff30dbc1",
                    "serviceName": "%s"
                  },
                  "request": {
                    "TransactionReferenceCode": "%s",
                    "TransactionDate": "2025-06-30T16:26:43.0198942+03:00",
                    "TotalAmount": "%s",
                    "Currency": "KES",
                    "DocumentReferenceNumber": "%s",
                    "BankCode": "11",
                    "BranchCode": "00011001",
                    "PaymentDate": "2025-06-30T16:26:43.0198942+03:00",
                    "PaymentReferenceCode": "4T13S8S117P11",
                    "PaymentCode": "1",
                    "PaymentMode": "1",
                    "PaymentAmount": "%s",
                    "AdditionalInfo": "%s",
                    "AccountNumber": "%s",
                    "AccountName": "",
                    "InstitutionCode": "%s",
                    "InstitutionName": "%s"
                  }
                }
                """.formatted(CONNECTION_ID, PASSWORD, SERVICE, transactionRef, amount, reference, amount,
                reference, reference, institution, SERVICE), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> header(Map<String, Object> answer) {
        return (Map<String, Object>) answer.get("header");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> response(Map<String, Object> answer) {
        return (Map<String, Object>) answer.get("response");
    }

    private long paymentsOnTheBooking() {
        return payments.findForBooking(HashIdUtil.decodeId(booking.id())).size();
    }

    // ── validation ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a booking reference validates to the buyer's name and what is overdue, in Co-op's shape")
    void validationAnswersWhatIsOwed() throws Exception {
        Map<String, Object> answer = biller.validate(validation(booking.reference(), PASSWORD));

        assertEquals("200", header(answer).get("statusCode"), answer.toString());
        assertEquals("d36aa236-5651-46bb-abcs-0a3ff30dbb", header(answer).get("messageID"), "echoed");
        Map<String, Object> body = response(answer);
        assertEquals("Asha Mwangi", body.get("AccountName"));
        assertEquals("Asha Mwangi", body.get("AdditionalInfo"));
        assertEquals(booking.reference(), body.get("AccountNumber"), "the reference, echoed as the account");
        assertEquals(0, new BigDecimal(body.get("TotalAmount").toString()).compareTo(new BigDecimal("950000")),
                "the deposit is ten days overdue, so that is what is asked for");
        assertEquals("KES", body.get("Currency"));
        assertEquals(institution, body.get("InstitutionCode"));
        assertEquals("Breeze Estate Ltd", body.get("InstitutionName"), "from the biller's configuration");
    }

    @Test
    @DisplayName("the four-character code validates too, and an unknown reference is a 404")
    void validationByCodeAndNotFound() throws Exception {
        Map<String, Object> byCode = biller.validate(validation("b1k1", PASSWORD));
        assertEquals("200", header(byCode).get("statusCode"), byCode.toString());

        Map<String, Object> unknown = biller.validate(validation("ZZZZ", PASSWORD));
        assertEquals("404", header(unknown).get("statusCode"));
        assertEquals("Customer reference not found", header(unknown).get("statusDescription"));
        assertNull(unknown.get("response"), "and nothing about anybody's booking goes back");
    }

    @Test
    @DisplayName("wrong credentials are refused, and a biller Co-op does not name is not found")
    void credentialsAreTheBoundary() throws Exception {
        Map<String, Object> wrong = biller.validate(validation(booking.reference(), "not-the-password"));
        assertEquals("401", header(wrong).get("statusCode"));

        Map<String, Object> stranger = validation(booking.reference(), PASSWORD);
        ((Map<String, Object>) stranger.get("request")).put("InstitutionCode", "000000000");
        assertEquals("404", header(biller.validate(stranger)).get("statusCode"));

        // Unset is closed, not open: blank the password and even the right one no longer works.
        jdbc.update("update payment_accounts set config = config - 'connectionPassword' where id = ?",
                account.getId());
        Map<String, Object> closed = biller.validate(validation(booking.reference(), PASSWORD));
        assertEquals("405", header(closed).get("statusCode"), closed.toString());
        assertEquals("Incomplete biller configuration", header(closed).get("statusDescription"));
    }

    // ── advice ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an advice quoting the bare code is placed without the amount or phone agreeing: the bank validated it with the payer")
    void adviceOnABareCodeIsPlacedWithoutCorroboration() throws Exception {
        String transactionRef = "4T19" + RrnGenerator.generate("S");

        // 37,500 is neither the deposit nor the price — the corroboration a free-text transfer would need.
        Map<String, Object> answer = biller.advise(advice(transactionRef, "b1k1", "37500"));

        assertEquals("200", header(answer).get("statusCode"), answer.toString());
        CoopStatement stored = statements.findByRefNo(transactionRef).orElseThrow();
        assertEquals(AppConstant.STATEMENT_MAPPED, stored.getState(),
                "the code alone places a biller advice: " + stored.getUnmappedReason());
        assertEquals(HashIdUtil.decodeId(booking.id()), stored.getMappedBookingId());
        assertEquals(1, paymentsOnTheBooking());
        assertEquals(0, payments.totalPaid(HashIdUtil.decodeId(booking.id())).compareTo(new BigDecimal("37500")));
    }

    @Test
    @DisplayName("an advice is stored as a statement and placed on the booking it names; a repeat is a duplicate")
    void adviceIsRecordedOnceAndPlaced() throws Exception {
        String transactionRef = "4T19" + RrnGenerator.generate("S");

        Map<String, Object> answer = biller.advise(advice(transactionRef, booking.reference(), "950000"));

        assertEquals("200", header(answer).get("statusCode"), answer.toString());
        assertEquals("Payment successfully received", header(answer).get("statusDescription"));
        assertEquals(transactionRef, response(answer).get("TransactionReferenceCode"));
        assertEquals("Breeze Estate Ltd", response(answer).get("InstitutionName"));

        CoopStatement stored = statements.findByRefNo(transactionRef).orElseThrow();
        assertEquals(CoopBillerService.TRANS_TYPE, stored.getTransType());
        assertEquals(account.getId(), stored.getPaymentAccountId(), "on the biller's own account");
        assertEquals(booking.reference(), stored.getReference(), "the customer's reference, from DocumentReferenceNumber");
        assertEquals("4T13S8S117P11", stored.getTraceId());
        assertEquals(AppConstant.STATEMENT_MAPPED, stored.getState(), stored.getUnmappedReason());
        assertEquals(2025, stored.getPaidAt().getYear(), "the bank's date, with its seven-digit fraction");
        assertFalse(stored.getRawPayload().contains(PASSWORD), "the password is not evidence");
        assertTrue(stored.getRawPayload().contains("4T13S8S117P11"), "the rest of the message is");
        assertEquals(1, paymentsOnTheBooking());
        assertEquals(0, payments.totalPaid(HashIdUtil.decodeId(booking.id()))
                .compareTo(new BigDecimal("950000")));

        Map<String, Object> again = biller.advise(advice(transactionRef, booking.reference(), "950000"));
        assertEquals("402", header(again).get("statusCode"), "which is what stops the bank retrying");
        assertEquals("Duplicate transaction", header(again).get("statusDescription"));
        assertEquals(1, paymentsOnTheBooking(), "and nothing was credited twice");
    }

    @Test
    @DisplayName("an advice for a reference nobody has is still received, and waits in the queue")
    void adviceForAnUnknownReferenceIsQueued() throws Exception {
        String transactionRef = "4T19" + RrnGenerator.generate("S");

        Map<String, Object> answer = biller.advise(advice(transactionRef, "QQQQ", "5000"));

        assertEquals("200", header(answer).get("statusCode"), "the money is real; whose it is, is our problem");
        CoopStatement stored = statements.findByRefNo(transactionRef).orElseThrow();
        assertEquals(AppConstant.STATEMENT_UNMAPPED, stored.getState());
        assertTrue(stored.getUnmappedReason().contains("QQQQ"), stored.getUnmappedReason());
        assertEquals(0, paymentsOnTheBooking());

        Map<String, Object> bad = biller.advise(advice("4T19" + RrnGenerator.generate("S"), "QQQQ", "abc"));
        assertEquals("400", header(bad).get("statusCode"));
        assertEquals("Invalid amount", header(bad).get("statusDescription"));
    }
}
