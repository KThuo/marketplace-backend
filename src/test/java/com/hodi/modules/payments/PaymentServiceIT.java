package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.modules.bookings.BookingTermsService;
import com.hodi.modules.properties.Property;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.infra.coop.CoopStatement;
import com.hodi.infra.coop.CoopStatementRepository;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingDtos.InstalmentLine;
import com.hodi.modules.bookings.BookingService;
import com.hodi.modules.developments.*;
import com.hodi.modules.payments.PaymentDtos.BookingBalance;
import com.hodi.modules.payments.PaymentDtos.PaymentListRequest;
import com.hodi.modules.payments.PaymentDtos.PaymentResponse;
import com.hodi.modules.payments.PaymentDtos.ReceiveRequest;
import com.hodi.modules.payments.PaymentDtos.VoidRequest;
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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Receiving money against a booking, and voiding it.
 *
 * <p>What is worth a test rather than a reading: the two balance snapshots, because a receipt has to show the
 * subtraction it performed and both ends are read from the view at the moment they were true; the void, because
 * the row must stay, the balance must go back and the statement behind it must be freed; the channel stamp,
 * because a receipt that says "cash" where the account says "Petty cash — site office" is the bug the payment
 * type exists to fix; and the refusals — a dead booking, an account that does not collect for this development,
 * a caller who may not touch the units, and above all any attempt to key by hand a payment the bank should have
 * told us about.
 */
@SpringBootTest
@Transactional
class PaymentServiceIT {

    @Autowired PaymentService service;
    @Autowired PaymentQueryService queries;
    @Autowired BookingService bookings;
    @Autowired PaymentRepository payments;
    @Autowired UnitBookingRepository bookingRows;
    @Autowired CoopStatementRepository statements;
    @Autowired PaymentAccountRepository accounts;
    @Autowired PaymentTypeRepository types;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitRepository units;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private Development development;
    private Property unit;
    private BookingResponse booking;

    @BeforeEach
    void signInAndBuild() {
        tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        signIn(tenantId, "UNITS_MANAGE", "UNITS_SELL", "BOOKINGS_MANAGE", "PAYMENTS_VIEW",
                "PAYMENTS_RECEIVE", "PAYMENTS_VOID");
        // The KCB till channel ships switched off until somebody has a till on it. Rolled back with the test.
        jdbc.update("update payment_types set status = 1 where provider_type = 'BUNI_IPN_TILL'");

        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Receipt Heights").developmentType("APARTMENT").build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(new BigDecimal("9500000")).build());
        unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("R-2-04")
                .saleState(AppConstant.UNIT_AVAILABLE)
                .constructionStatus(AppConstant.BUILD_PLANNED).build());

        booking = bookings.create(HashIdUtil.encodeId(development.getId()), new CreateBookingRequest(
                HashIdUtil.encodeId(unit.getId()), "Asha Mwangi", "+254712000111", null, null,
                new BigDecimal("9500000"), new BigDecimal("950000"), AppConstant.PLAN_INSTALMENTS, 14, null,
                List.of(new InstalmentLine("Deposit", LocalDate.now().minusDays(10), new BigDecimal("950000")),
                        new InstalmentLine("Balance", LocalDate.now().plusDays(90),
                                new BigDecimal("8550000")))));
        agree(booking);
    }

    private void signIn(Long tenant, String... permissions) {
        User user = User.builder().id(1L).username("pay-test").password("x")
                .email("p@example.invalid").firstName("Pa").lastName("Payer")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenant).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile, Set.of(permissions),
                List.of(tenant), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** A second organisation, so scope can be tested against a real row. Rolled back with the test. */
    private Long anotherTenant() {
        String ref = RrnGenerator.generate("TN");
        return jdbc.queryForObject(
                "insert into tenants (name, slug, tenant_ref, created_by) values (?, ?, ?, 'test') returning id",
                Long.class, "Elsewhere Ltd " + ref, "elsewhere-" + ref.toLowerCase(), ref);
    }

    private ReceiveRequest cash(String amount) {
        return new ReceiveRequest(booking.id(), new BigDecimal(amount), null, AppConstant.PAY_CASH, null,
                null, null, null, null, null);
    }

    /** A cash account on this tenant: a manual channel, so a hand-keyed payment can name it. */
    private PaymentAccount cashDesk(Long developmentId) {
        PaymentType channel = types.findByCode(AppConstant.PAY_CASH).orElseThrow();
        PaymentAccount row = PaymentAccount.builder()
                .tenantId(tenantId).developmentId(developmentId).createdBy("test").build();
        row.stampChannel(channel);
        return accounts.save(row);
    }

    /** A live KCB till on this tenant: an inbound channel, whose money arrives as a notification. */
    private PaymentAccount till(Long developmentId) {
        PaymentType channel = types.findByProviderType("BUNI_IPN_TILL").orElseThrow();
        PaymentAccount row = PaymentAccount.builder()
                .accountNo("TILL" + Long.toString(System.nanoTime(), 36).toUpperCase()).accountName("Seller's till")
                .tenantId(tenantId).developmentId(developmentId).createdBy("test").build();
        row.stampChannel(channel);
        return accounts.save(row);
    }

    // ── receiving ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a receipt carries the balance before and after, read from the view either side of the insert")
    void receiptCarriesBothEndsOfTheSubtraction() {
        PaymentResponse first = service.receive(cash("950000"));

        assertEquals(0, first.balanceBefore().compareTo(new BigDecimal("9500000")));
        assertEquals(0, first.balanceAfter().compareTo(new BigDecimal("8550000")));
        assertEquals("Received", first.statusLabel());
        assertEquals("R-2-04", first.unitLabel(), "the unit is stamped on the receipt");
        assertEquals("Asha Mwangi", first.payerName(), "the payer defaults to the buyer");
        assertEquals("Cash", first.arrivedAs(), "no channel, so the method's own label");

        PaymentResponse second = service.receive(cash("50000"));
        assertEquals(0, second.balanceBefore().compareTo(new BigDecimal("8550000")),
                "the second payment starts where the first left off");
        assertEquals(0, second.balanceAfter().compareTo(new BigDecimal("8500000")));
    }

    @Test
    @DisplayName("naming a manual account fixes the method and stamps the channel's name on the receipt")
    void channelStampsTheReceipt() {
        PaymentAccount desk = cashDesk(null);

        PaymentResponse paid = service.receive(new ReceiveRequest(booking.id(), new BigDecimal("950000"),
                null, AppConstant.PAY_CHEQUE, HashIdUtil.encodeId(desk.getId()),
                "R2K4", null, null, null, null));

        assertEquals(AppConstant.PAY_CASH, paid.method(),
                "the channel decides the method — a cheque was asked for and the cash desk said cash");
        assertEquals(HashIdUtil.encodeId(desk.getPaymentTypeId()), paid.paymentTypeId());
        assertEquals(types.findByCode(AppConstant.PAY_CASH).orElseThrow().getName(), paid.arrivedAs());
    }

    @Test
    @DisplayName("money that arrives through the bank cannot be keyed by hand, with or without its account")
    void bankMoneyCannotBeKeyedByHand() {
        /*
         * The hole this closes: a payment recorded as "Co-op phone prompt" or "bank transfer" with no prompt,
         * no notification and no statement — only an operator's word and a free-text reference. It moved a
         * buyer's balance and nothing could check it afterwards. Such money exists only as a statement row,
         * and it is placed from there.
         */
        PaymentAccount till = till(null);
        HodiException viaAccount = assertThrows(HodiException.class, () -> service.receive(new ReceiveRequest(
                booking.id(), new BigDecimal("950000"), null, null, HashIdUtil.encodeId(till.getId()),
                "R2K4", "FT1234", null, null, null)));
        assertTrue(viaAccount.getMessage().contains("notification from the bank"), viaAccount.getMessage());
        assertTrue(viaAccount.getMessage().contains("bank reference"),
                "and it says where the payment actually is");

        HodiException viaMethod = assertThrows(HodiException.class, () -> service.receive(new ReceiveRequest(
                booking.id(), new BigDecimal("950000"), null, AppConstant.PAY_BANK_TRANSFER, null,
                null, "FT1234", null, null, null)));
        assertTrue(viaMethod.getMessage().contains("notification from the bank"), viaMethod.getMessage());

        HodiException blank = assertThrows(HodiException.class, () -> service.receive(new ReceiveRequest(
                booking.id(), new BigDecimal("950000"), null, null, null, null, null, null, null, null)));
        assertTrue(blank.getMessage().contains("cash or a cheque"),
                "nothing defaults to a bank transfer any more: " + blank.getMessage());

        assertEquals(0, payments.findForBooking(HashIdUtil.decodeId(booking.id())).size());
    }

    @Test
    @DisplayName("the database itself refuses an electronic payment with no statement behind it")
    void theDatabaseRefusesAnElectronicPaymentWithoutAStatement() {
        // Underneath the service, which is the only way to reach a path the service does not have.
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("""
                insert into payments (reference, booking_id, development_id, property_id, paid_on, amount,
                    currency, source, method, status, created_by)
                values (?, ?, ?, ?, current_date, 1000, 'KES', 'MANUAL', 'BANK_TRANSFER', 1, 'test')
                """, RrnGenerator.generate("PY"), HashIdUtil.decodeId(booking.id()), development.getId(),
                unit.getId()));
    }

    @Test
    @DisplayName("an account that collects for another development is refused")
    void accountMustReachTheDevelopment() {
        Development other = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId)
                .name("Other Court").developmentType("APARTMENT").build());
        developments.flush();
        PaymentAccount till = till(other.getId());

        HodiException e = assertThrows(HodiException.class, () -> service.receive(new ReceiveRequest(
                booking.id(), new BigDecimal("1000"), null, null, HashIdUtil.encodeId(till.getId()),
                null, null, null, null, null)));
        assertTrue(e.getMessage().contains("does not collect for"), e.getMessage());
    }

    @Test
    @DisplayName("money cannot arrive in the future, and cannot arrive against a cancelled booking")
    void refusals() {
        HodiException future = assertThrows(HodiException.class, () -> service.receive(new ReceiveRequest(
                booking.id(), new BigDecimal("1000"), LocalDate.now().plusDays(1), null, null,
                null, null, null, null, null)));
        assertTrue(future.getMessage().contains("future"), future.getMessage());

        bookings.cancel(HashIdUtil.encodeId(development.getId()), booking.id(),
                new com.hodi.modules.bookings.BookingDtos.CloseBookingRequest("Buyer withdrew."));
        HodiException dead = assertThrows(HodiException.class, () -> service.receive(cash("1000")));
        assertTrue(dead.getMessage().contains("cancelled"), dead.getMessage());
    }

    @Test
    @DisplayName("somebody outside the development cannot record money against it, and cannot see it")
    void scopedToTheDevelopment() {
        service.receive(cash("950000"));
        Long otherTenant = anotherTenant();

        signIn(otherTenant, "PAYMENTS_VIEW", "PAYMENTS_RECEIVE");
        assertThrows(RuntimeException.class, () -> service.receive(cash("1000")),
                "a booking outside the caller's developments is not found");

        PaymentListRequest all = new PaymentListRequest();
        assertEquals(0, queries.list(all).getTotalElements(), "and the list shows nothing of it");
    }

    // ── voiding ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a void keeps the row, records why, and puts the balance back")
    void voidKeepsTheRowAndRestoresTheBalance() {
        PaymentResponse paid = service.receive(cash("950000"));

        PaymentResponse voided = service.voidPayment(paid.id(), new VoidRequest("The cheque bounced."));

        assertEquals("Voided", voided.statusLabel());
        assertEquals("The cheque bounced.", voided.voidReason());
        assertNotNull(voided.voidedAt());
        assertEquals(0, voided.amount().compareTo(new BigDecimal("950000")), "the amount is not edited away");

        BookingBalance balance = queries.balanceOf(booking.id());
        assertEquals(0, balance.paid().compareTo(BigDecimal.ZERO), "the view stops counting it");
        assertEquals(0, balance.balance().compareTo(new BigDecimal("9500000")));
        assertEquals(1, queries.forBooking(HashIdUtil.decodeId(booking.id())).size(),
                "and the drawer still lists it, marked");
    }

    @Test
    @DisplayName("voiding a payment placed from a statement puts the statement back in the queue, saying why")
    void voidReleasesTheStatement() {
        PaymentAccount till = till(null);
        UnitBooking row = bookingRows.findById(HashIdUtil.decodeId(booking.id())).orElseThrow();
        CoopStatement statement = statements.saveAndFlush(CoopStatement.builder()
                .refNo("VOID-" + RrnGenerator.generate("RF")).ourReference(RrnGenerator.generate("PS"))
                .transType("BUNI_IPN_TILL").paymentAccountId(till.getId()).accountIdentifier(till.getAccountNo())
                .reference("R2K4").amount(new BigDecimal("950000")).phoneNo("254712000111")
                .customerName("Asha Mwangi").tenantId(tenantId).createdBy("test").build());
        Payment placed = service.recordFromGateway(row, statement, till);
        statement.placedOn(placed.getId(), row.getId(), "test");
        statements.saveAndFlush(statement);
        assertEquals(statement.getId(), placed.getStatementId(), "a gateway payment names its statement");

        service.voidPayment(HashIdUtil.encodeId(placed.getId()), new VoidRequest("Wrong unit."));

        CoopStatement released = statements.findById(statement.getId()).orElseThrow();
        assertEquals(AppConstant.STATEMENT_UNMAPPED, released.getState(),
                "the bank still says the money arrived; only our decision about whose it was is withdrawn");
        assertNull(released.getMappedPaymentId());
        assertNull(released.getMappedBookingId());
        assertTrue(released.getUnmappedReason().contains("voided"), released.getUnmappedReason());
        assertTrue(released.getUnmappedReason().contains("Wrong unit."), "and it carries the reason given");
        assertEquals(statement.getId(), payments.findById(placed.getId()).orElseThrow().getStatementId(),
                "the voided receipt still says which statement it was written from");
    }

    @Test
    @DisplayName("a payment cannot be voided twice")
    void doubleVoidRefused() {
        PaymentResponse paid = service.receive(cash("100000"));
        service.voidPayment(paid.id(), new VoidRequest("Wrong unit."));

        HodiException e = assertThrows(HodiException.class,
                () -> service.voidPayment(paid.id(), new VoidRequest("Again.")));
        assertTrue(e.getMessage().contains("already voided"), e.getMessage());
    }

    // ── reading ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the balance panel names the home, the buyer, the schedule and what is owed")
    void balancePanel() {
        BookingBalance balance = queries.balanceOf(booking.id());

        assertEquals("R-2-04", balance.unitLabel());
        assertEquals(booking.payReference(), balance.payReference(), "the booking's own code, on the balance panel");
        assertEquals("Receipt Heights", balance.developmentName());
        assertEquals(2, balance.schedule().size());
        assertTrue(balance.schedule().get(0).past(), "the deposit was due ten days ago");
        assertEquals(0, balance.overdue().compareTo(new BigDecimal("950000")));
    }

    @Test
    @DisplayName("the list filters by status and by channel, and the receipt is found by its number")
    void listAndReceipt() {
        PaymentAccount desk = cashDesk(null);
        PaymentResponse viaTill = service.receive(new ReceiveRequest(booking.id(), new BigDecimal("500000"),
                null, null, HashIdUtil.encodeId(desk.getId()), null, null, null, null, null));
        PaymentResponse cash = service.receive(new ReceiveRequest(booking.id(), new BigDecimal("1000"), null,
                AppConstant.PAY_CHEQUE, null, null, "CHQ 0042", null, null, null));
        service.voidPayment(cash.id(), new VoidRequest("Keyed twice."));

        PaymentListRequest received = new PaymentListRequest();
        received.setStatus(AppConstant.PAYMENT_RECEIVED);
        received.setBookingId(booking.id());
        assertEquals(1, queries.list(received).getTotalElements());

        PaymentListRequest voided = new PaymentListRequest();
        voided.setStatus(AppConstant.PAYMENT_VOIDED);
        voided.setBookingId(booking.id());
        assertEquals(1, queries.list(voided).getTotalElements());

        PaymentListRequest byChannel = new PaymentListRequest();
        byChannel.setPaymentTypeId(HashIdUtil.encodeId(desk.getPaymentTypeId()));
        byChannel.setBookingId(booking.id());
        assertEquals(1, queries.list(byChannel).getTotalElements());

        assertEquals(viaTill.id(), queries.byReference(viaTill.reference()).payment().id());
        assertEquals(types.findByCode(AppConstant.PAY_CASH).orElseThrow().getName(),
                queries.detail(viaTill.id()).payment().arrivedAs());
        assertNotNull(queries.detail(viaTill.id()).booking(), "the receipt carries the booking as it stands");
    }

    /** The buyer accepted the terms: every fixture here is about what happens after that. */
    private void agree(BookingResponse b) {
        // Through the entity, not JDBC: the booking is already in the persistence context and would read stale.
        UnitBooking row = bookingRows.findById(HashIdUtil.decodeId(b.id())).orElseThrow();
        row.setTermsState(BookingTermsService.TERMS_ACCEPTED);
        bookingRows.saveAndFlush(row);
    }
}
