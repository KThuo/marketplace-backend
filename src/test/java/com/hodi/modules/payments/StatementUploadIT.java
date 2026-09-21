package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.CsvRows;
import com.hodi.common.util.RrnGenerator;
import com.hodi.infra.coop.CoopStatement;
import com.hodi.infra.coop.CoopStatementRepository;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingService;
import com.hodi.modules.developments.*;
import com.hodi.modules.payments.StatementDtos.UploadLine;
import com.hodi.modules.payments.StatementDtos.UploadOutcome;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A bank statement uploaded as a file.
 *
 * <h2>Why this class commits</h2>
 *
 * <p>Every row is stored and placed in its own transaction — so one bad row cannot roll back the credits
 * before it — which means the rows commit underneath a {@code @Transactional} test and would not be
 * rolled back with it. Hence the hand-written clean-up, and the fixture names nothing else uses.
 *
 * <h2>What is worth testing</h2>
 *
 * <p>That one file produces the right mix — placed, queued, skipped, failed — each line saying which and
 * why; that the same file twice is harmless, because banks export the same week twice; that a bank's
 * own spellings of the headings are understood; and that the template is the spec.
 */
@SpringBootTest
class StatementUploadIT {

    @Autowired StatementUploadService uploads;
    @Autowired BookingService bookings;
    @Autowired PaymentRepository payments;
    @Autowired CoopStatementRepository statements;
    @Autowired PaymentAccountRepository accounts;
    @Autowired PaymentTypeRepository types;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitRepository units;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired JdbcTemplate jdbc;

    private static final String DEVELOPMENT = "Upload Heights";

    private Long tenantId;
    private Development development;
    private Property unit;
    private BookingResponse booking;
    private PaymentAccount till;
    private String account;

    @BeforeEach
    void signInAndBuild() {
        purgeStaleFixtures();
        tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        User user = User.builder().id(7L).username("upload-admin").password("x")
                .email("u@example.invalid").firstName("Up").lastName("Load")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(7L).userId(7L)
                .profileType(AppConstant.ACTOR_PLATFORM).userTypeCode("SUPER_ADMIN")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("STATEMENTS_VIEW", "STATEMENTS_RECONCILE", "PAYMENTS_RECEIVE", "BOOKINGS_MANAGE",
                        "UNITS_SELL", "UNITS_MANAGE"),
                List.of(), true, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
        jdbc.update("update payment_types set status = 1 where provider_type = 'BUNI_IPN_TILL'");

        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name(DEVELOPMENT).developmentType("APARTMENT").build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(new BigDecimal("9500000")).build());
        unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("U-4-04")
                .saleState(AppConstant.UNIT_AVAILABLE)
                .constructionStatus(AppConstant.BUILD_PLANNED).build());

        account = "UPL" + Long.toString(System.nanoTime(), 36).toUpperCase();
        PaymentType channel = types.findByProviderType("BUNI_IPN_TILL").orElseThrow();
        PaymentAccount row = PaymentAccount.builder()
                .accountNo(account).accountName("Upload till")
                .tenantId(tenantId).createdBy("test").build();
        row.stampChannel(channel);
        till = accounts.save(row);

        booking = bookings.create(HashIdUtil.encodeId(development.getId()),
                new CreateBookingRequest(HashIdUtil.encodeId(unit.getId()), "Asha Mwangi",
                        "+254 712 345 678", null, null, new BigDecimal("9500000"),
                        new BigDecimal("950000"), null, 14, null, null));
    }

    private void purgeStaleFixtures() {
        jdbc.update("update coop_statements set state = 'UNMAPPED', mapped_payment_id = null, "
                + "mapped_booking_id = null, mapped_at = null where mapped_payment_id in "
                + "(select id from payments where development_id in "
                + "(select id from developments where name = ?))", DEVELOPMENT);
        jdbc.update("delete from payments where development_id in "
                + "(select id from developments where name = ?)", DEVELOPMENT);
        jdbc.update("delete from coop_statements where account_identifier like 'UPL%'");
        jdbc.update("delete from booking_instalments where booking_id in (select id from unit_bookings "
                + "where development_id in (select id from developments where name = ?))", DEVELOPMENT);
        jdbc.update("delete from unit_bookings where development_id in "
                + "(select id from developments where name = ?)", DEVELOPMENT);
        jdbc.update("delete from properties where listing_kind = 'UNIT' and development_id in "
                + "(select id from developments where name = ?)", DEVELOPMENT);
        jdbc.update("delete from development_unit_types where development_id in "
                + "(select id from developments where name = ?)", DEVELOPMENT);
        jdbc.update("delete from developments where name = ?", DEVELOPMENT);
        jdbc.update("delete from payment_accounts where account_no like 'UPL%' and account_name = 'Upload till'");
        jdbc.update("delete from payment_accounts where category = 'CASH' and created_by = 'upload-test'");
    }

    @AfterEach
    void cleanUp() {
        try {
            purgeStaleFixtures();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static MockMultipartFile csv(String text) {
        return new MockMultipartFile("file", "statement.csv", "text/csv", text.getBytes(StandardCharsets.UTF_8));
    }

    private static UploadLine line(UploadOutcome outcome, int number) {
        return outcome.lines().stream().filter(l -> l.line() == number).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("one file: booking and listing references place, an unknown code queues, a repeat skips, a bad amount fails")
    void oneFileFourOutcomes() {
        String refA = "UPA" + RrnGenerator.generate("RF");
        String refB = "UPB" + RrnGenerator.generate("RF");
        String refC = "UPC" + RrnGenerator.generate("RF");
        String refE = "UPE" + RrnGenerator.generate("RF");
        // A bank's own headings, in its own order, with a comma inside a quoted name.
        String file = "Transaction ID,Credited On,Amount,Booking Ref,Payer,Phone No,Description\n"
                + refA + ",2026-09-18,\"950,000.00\"," + booking.reference() + ",\"Mwangi, Asha\",254712345678,Deposit\n"
                + refB + ",18/09/2026 09:30,1000," + unit.getReference().toLowerCase() + ",Asha Mwangi,,Top-up\n"
                + refC + ",,500,QQQQ,Somebody,,Mystery\n"
                + refA + ",2026-09-18,950000," + booking.reference() + ",Asha Mwangi,,Same row again\n"
                + refE + ",2026-09-18,abc," + booking.reference() + ",Asha Mwangi,,Typo\n";

        UploadOutcome outcome = uploads.upload(HashIdUtil.encodeId(till.getId()), csv(file));

        assertEquals(5, outcome.rows());
        assertEquals(2, outcome.placed(), outcome.toString());
        assertEquals(1, outcome.queued());
        assertEquals(1, outcome.skipped());
        assertEquals(1, outcome.failed());

        assertEquals("PLACED", line(outcome, 2).outcome(), "the booking reference places it on its own");
        assertEquals(booking.reference(), line(outcome, 2).bookingReference());
        assertEquals("PLACED", line(outcome, 3).outcome(), "so does the listing reference, however it is typed");
        assertEquals("QUEUED", line(outcome, 4).outcome());
        assertTrue(line(outcome, 4).message().contains("QQQQ"), line(outcome, 4).message());
        assertEquals("SKIPPED", line(outcome, 5).outcome());
        assertTrue(line(outcome, 5).message().contains("earlier in this file"), line(outcome, 5).message());
        assertEquals("FAILED", line(outcome, 6).outcome());
        assertTrue(line(outcome, 6).message().contains("not a number"), line(outcome, 6).message());

        assertEquals(2, payments.findForBooking(HashIdUtil.decodeId(booking.id())).size());
        assertEquals(0, payments.totalPaid(HashIdUtil.decodeId(booking.id()))
                .compareTo(new BigDecimal("951000")), "the bank's figures, commas and all");

        CoopStatement placed = statements.findByRefNo(refA).orElseThrow();
        assertEquals(AppConstant.STATEMENT_MAPPED, placed.getState());
        assertEquals(StatementUploadService.TRANS_TYPE, placed.getTransType());
        assertEquals("Mwangi, Asha", placed.getCustomerName(), "the quoted comma survived");
        assertEquals("upload-admin", placed.getCreatedBy(), "a person uploaded it, and the row says who");
        assertEquals(18, placed.getPaidAt().getDayOfMonth(), "the bank's date, not today's");
        assertNotNull(placed.getRawPayload(), "the row as it arrived is kept");
        Payment payment = payments.findById(placed.getMappedPaymentId()).orElseThrow();
        assertEquals(placed.getId(), payment.getStatementId());
        assertEquals("KCB Till", payment.getPaymentTypeName());

        CoopStatement queued = statements.findByRefNo(refC).orElseThrow();
        assertEquals(AppConstant.STATEMENT_UNMAPPED, queued.getState());
        assertTrue(queued.getUnmappedReason().contains("QQQQ"));

        // The same export again, as banks send it. Nothing moves, and every line says why.
        UploadOutcome again = uploads.upload(HashIdUtil.encodeId(till.getId()), csv(file));
        assertEquals(0, again.placed());
        assertEquals(0, again.queued());
        assertEquals(4, again.skipped());
        assertEquals(1, again.failed());
        assertTrue(line(again, 2).message().contains("applied to booking " + booking.reference()),
                line(again, 2).message());
        assertTrue(line(again, 4).message().contains("unused queue"), line(again, 4).message());
        assertEquals(2, payments.findForBooking(HashIdUtil.decodeId(booking.id())).size(), "still two");
    }

    @Test
    @DisplayName("the template is the spec, and a file missing a required column is refused by name")
    void templateAndRequiredColumns() {
        List<List<String>> template = CsvRows.parse(uploads.template());
        assertEquals(2, template.size(), "a header and one example row");
        assertEquals(uploads.spec().columns().stream().map(c -> c.key()).toList(), template.get(0));
        assertTrue(uploads.spec().columns().stream().filter(c -> c.required()).map(c -> c.key()).toList()
                .containsAll(List.of(StatementUploadService.REF_NO, StatementUploadService.AMOUNT)));

        HodiException e = assertThrows(HodiException.class, () -> uploads.upload(
                HashIdUtil.encodeId(till.getId()), csv("AMOUNT,PAID BY\n100,Someone\n")));
        assertTrue(e.getMessage().contains("REF NO"), e.getMessage());
    }

    @Test
    @DisplayName("a bank statement is for a bank account: a cash desk is refused")
    void aCashAccountIsRefused() {
        PaymentType cash = types.findByCode(AppConstant.PAY_CASH).orElseThrow();
        // A manual account carries no number and no name; the check constraint insists.
        PaymentAccount unsaved = PaymentAccount.builder().tenantId(tenantId).createdBy("upload-test").build();
        unsaved.stampChannel(cash);
        PaymentAccount desk = accounts.save(unsaved);

        HodiException e = assertThrows(HodiException.class, () -> uploads.upload(
                HashIdUtil.encodeId(desk.getId()),
                csv("REF NO,AMOUNT\nUPX" + RrnGenerator.generate("RF") + ",100\n")));
        assertTrue(e.getMessage().contains("not an account a bank statement comes from"), e.getMessage());
    }
}
