package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.infra.coop.CoopStatement;
import com.hodi.infra.coop.CoopStatementRepository;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CloseBookingRequest;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingService;
import com.hodi.modules.developments.*;
import com.hodi.modules.payments.StatementDtos.AttachRequest;
import com.hodi.modules.payments.StatementDtos.SetAsideRequest;
import com.hodi.modules.payments.StatementDtos.StatementListRequest;
import com.hodi.modules.payments.StatementDtos.StatementResponse;
import com.hodi.modules.payments.StatementDtos.Waiting;
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
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The unused queue, read and worked.
 *
 * <p>What is worth a test: that applying a credit by hand produces exactly the payment an automatic match
 * would, once, with the statement behind it; that the refusals name what they refuse — a credit already
 * used says which booking, a set-aside one says why; that an account's money cannot be applied to a
 * booking it does not collect for; and that a seller sees only the money that landed in their own accounts.
 */
@SpringBootTest
@Transactional
class StatementServiceIT {

    @Autowired StatementService service;
    @Autowired PaymentQueryService queries;
    @Autowired BookingService bookings;
    @Autowired PaymentRepository payments;
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
    private PaymentAccount till;

    @BeforeEach
    void signInAndBuild() {
        tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        signInAsPlatform();
        jdbc.update("update payment_types set status = 1 where provider_type = 'BUNI_IPN_TILL'");

        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Queue Heights").developmentType("APARTMENT").build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(new BigDecimal("9500000")).build());
        unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("Q-1-01")
                .payReference("Q1K1")
                .saleState(AppConstant.UNIT_AVAILABLE)
                .constructionStatus(AppConstant.BUILD_PLANNED).build());
        booking = bookings.create(HashIdUtil.encodeId(development.getId()), new CreateBookingRequest(
                HashIdUtil.encodeId(unit.getId()), "Asha Mwangi", "+254712000111", null, null,
                new BigDecimal("9500000"), new BigDecimal("950000"), null, 14, null, null));
        till = till(tenantId, null);
    }

    private void signInAsPlatform() {
        User user = User.builder().id(7L).username("queue-admin").password("x")
                .email("q@example.invalid").firstName("Queue").lastName("Admin")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(7L).userId(7L)
                .profileType(AppConstant.ACTOR_PLATFORM).userTypeCode("SUPER_ADMIN")
                .status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of("STATEMENTS_VIEW", "STATEMENTS_RECONCILE",
                "PAYMENTS_VIEW", "PAYMENTS_RECEIVE", "BOOKINGS_MANAGE", "UNITS_SELL", "UNITS_MANAGE"),
                List.of(), true, true));
    }

    private void signInAsSeller(Long tenant) {
        User user = User.builder().id(8L).username("seller-eyes").password("x")
                .email("s@example.invalid").firstName("Sel").lastName("Ler")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(8L).userId(8L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenant).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of("STATEMENTS_VIEW"), List.of(tenant), false, true));
    }

    private static void signIn(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private PaymentAccount till(Long tenant, Long developmentId) {
        PaymentType channel = types.findByProviderType("BUNI_IPN_TILL").orElseThrow();
        PaymentAccount row = PaymentAccount.builder()
                .accountNo("QT" + Long.toString(System.nanoTime(), 36).toUpperCase()).accountName("Queue till")
                .tenantId(tenant).developmentId(developmentId).createdBy("test").build();
        row.stampChannel(channel);
        return accounts.save(row);
    }

    /** A credit the matcher could not place, as the notification handler leaves one. */
    private CoopStatement unused(PaymentAccount account, String quoted, String amount, OffsetDateTime paidAt) {
        return statements.saveAndFlush(CoopStatement.builder()
                .refNo("QREF" + RrnGenerator.generate("RF")).ourReference(RrnGenerator.generate("PS"))
                .transType("BUNI_IPN_TILL").paymentAccountId(account.getId())
                .accountIdentifier(account.getAccountNo())
                .reference(quoted).amount(new BigDecimal(amount)).phoneNo("254700000000")
                .customerName("Walk-in Payer").paidAt(paidAt)
                .tenantId(account.getTenantId()).institutionId(account.getInstitutionId())
                .state(AppConstant.STATEMENT_UNMAPPED)
                .unmappedReason("No unit has the code \"" + quoted + "\". The payer may have mistyped it.")
                .createdBy("system").build());
    }

    private Long anotherTenant() {
        String ref = RrnGenerator.generate("TN");
        return jdbc.queryForObject(
                "insert into tenants (name, slug, tenant_ref, created_by) values (?, ?, ?, 'test') returning id",
                Long.class, "Elsewhere Ltd " + ref, "elsewhere-" + ref.toLowerCase(), ref);
    }

    private static StatementListRequest unusedOnly() {
        StatementListRequest request = new StatementListRequest();
        request.setState("UNMAPPED");
        return request;
    }

    // ── reading ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the queue is the list filtered to unused, and every row says which channel and why")
    void theQueueIsAFilterOnTheList() {
        CoopStatement waiting = unused(till, "QQQQ", "37500", OffsetDateTime.now().minusDays(2));
        CoopStatement aside = unused(till, "ZZZZ", "1000", OffsetDateTime.now().minusDays(1));
        service.setAside(HashIdUtil.encodeId(aside.getId()), new SetAsideRequest("A supplier's refund."));

        var page = service.list(unusedOnly());
        assertTrue(page.getContent().stream().anyMatch(r -> r.refNo().equals(waiting.getRefNo())));
        assertTrue(page.getContent().stream().noneMatch(r -> r.refNo().equals(aside.getRefNo())),
                "set aside stops appearing as work");

        StatementResponse row = page.getContent().stream()
                .filter(r -> r.refNo().equals(waiting.getRefNo())).findFirst().orElseThrow();
        assertEquals("Unused", row.stateLabel());
        assertEquals("KCB Till", row.paymentTypeName(), "the channel it came through, by name");
        assertEquals("QQQQ", row.quoted(), "the payer's own words, verbatim");
        assertTrue(row.reason().contains("QQQQ"), row.reason());
        assertEquals("TENANT", row.ownerKind());

        StatementListRequest byRef = new StatementListRequest();
        // The tail of the reference is the random part; the middle is today's date, shared by every row.
        byRef.setSearch(waiting.getRefNo().substring(waiting.getRefNo().length() - 6).toLowerCase());
        assertEquals(1, service.list(byRef).getTotalElements(), "found by a partial bank reference");

        StatementListRequest byChannel = new StatementListRequest();
        byChannel.setPaymentTypeId(HashIdUtil.encodeId(till.getPaymentTypeId()));
        assertTrue(service.list(byChannel).getTotalElements() >= 2);
    }

    @Test
    @DisplayName("the waiting tally says how many, how much, and how long the oldest has waited")
    void theWaitingTally() {
        OffsetDateTime oldest = OffsetDateTime.now().minusDays(9).truncatedTo(ChronoUnit.SECONDS);
        unused(till, "AAAA", "1000", oldest);
        unused(till, "BBBB", "2500", OffsetDateTime.now().minusDays(1));

        // Nothing else in this shared database is this development's; narrow to a seller of it.
        signInAsSeller(tenantId);
        Waiting waiting = service.waiting();

        assertEquals(2, waiting.count());
        assertEquals(0, waiting.total().compareTo(new BigDecimal("3500")));
        assertEquals(oldest.toInstant(), waiting.oldestPaidAt().truncatedTo(ChronoUnit.SECONDS).toInstant());
    }

    // ── deciding ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("applying a credit writes one payment from the statement, and a second attempt names the booking")
    void attachCreditsOnceAndNamesTheBooking() {
        CoopStatement credit = unused(till, "Q1KI", "950000", OffsetDateTime.now().minusDays(3));

        StatementResponse applied = service.attach(HashIdUtil.encodeId(credit.getId()),
                new AttachRequest(booking.id()));

        assertEquals("Used", applied.stateLabel());
        assertEquals(booking.reference(), applied.bookingReference());
        assertNotNull(applied.paymentReference());
        assertEquals("Q-1-01", applied.unitLabel());

        Payment payment = payments.findById(HashIdUtil.decodeId(applied.paymentId())).orElseThrow();
        assertEquals(credit.getId(), payment.getStatementId(), "the payment names the statement it came from");
        assertEquals(AppConstant.PAY_GATEWAY, payment.getSource(), "placed by hand, but the bank's money");
        assertEquals("KCB Till", payment.getPaymentTypeName());
        assertEquals(0, payment.getAmount().compareTo(new BigDecimal("950000")), "the bank's figure, not typed");
        assertEquals(0, queries.balanceOf(booking.id()).paid().compareTo(new BigDecimal("950000")));

        CoopStatement placed = statements.findById(credit.getId()).orElseThrow();
        assertEquals(AppConstant.STATEMENT_MAPPED, placed.getState());
        assertEquals("queue-admin", placed.getMappedBy());
        assertNull(placed.getUnmappedReason());

        HodiException again = assertThrows(HodiException.class, () -> service.attach(
                HashIdUtil.encodeId(credit.getId()), new AttachRequest(booking.id())));
        assertTrue(again.getMessage().contains(booking.reference()), again.getMessage());
        assertTrue(again.getMessage().contains(applied.paymentReference()), "and the receipt it became");
        assertEquals(1, payments.findForBooking(HashIdUtil.decodeId(booking.id())).size());
    }

    @Test
    @DisplayName("set aside stops it being applied and says why; restore brings it back; a used credit cannot be set aside")
    void setAsideAndRestore() {
        CoopStatement credit = unused(till, "XXXX", "5000", OffsetDateTime.now());
        String id = HashIdUtil.encodeId(credit.getId());

        StatementResponse aside = service.setAside(id, new SetAsideRequest("Refund of our own transfer."));
        assertEquals("Set aside", aside.stateLabel());
        assertEquals("Refund of our own transfer.", aside.reason());

        HodiException refused = assertThrows(HodiException.class,
                () -> service.attach(id, new AttachRequest(booking.id())));
        assertTrue(refused.getMessage().contains("set aside"), refused.getMessage());
        assertTrue(refused.getMessage().contains("Refund of our own transfer."), "with the reason it was");

        StatementResponse back = service.restore(id);
        assertEquals("Unused", back.stateLabel());
        assertTrue(back.reason().contains("Restored"), back.reason());

        service.attach(id, new AttachRequest(booking.id()));
        HodiException used = assertThrows(HodiException.class,
                () -> service.setAside(id, new SetAsideRequest("Changed my mind.")));
        assertTrue(used.getMessage().contains("Void"), "a used credit is voided, not set aside: " + used.getMessage());
        assertTrue(used.getMessage().contains(booking.reference()));
    }

    @Test
    @DisplayName("money in an account that does not collect for the booking's development is refused")
    void attachRefusesAnAccountOutsideItsReach() {
        Development other = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId)
                .name("Other Court").developmentType("APARTMENT").build());
        developments.flush();
        PaymentAccount pinned = till(tenantId, other.getId());
        CoopStatement credit = unused(pinned, "Q1K1", "950000", OffsetDateTime.now());

        HodiException e = assertThrows(HodiException.class, () -> service.attach(
                HashIdUtil.encodeId(credit.getId()), new AttachRequest(booking.id())));
        assertTrue(e.getMessage().contains("does not collect for"), e.getMessage());
        assertTrue(e.getMessage().contains("Queue Heights"));
        assertEquals(AppConstant.STATEMENT_UNMAPPED, statements.findById(credit.getId()).orElseThrow().getState());
    }

    @Test
    @DisplayName("a credit cannot be applied to a booking that is no longer live")
    void attachRefusesADeadBooking() {
        CoopStatement credit = unused(till, "Q1K1", "950000", OffsetDateTime.now());
        bookings.cancel(HashIdUtil.encodeId(development.getId()), booking.id(),
                new CloseBookingRequest("Buyer withdrew."));

        HodiException e = assertThrows(HodiException.class, () -> service.attach(
                HashIdUtil.encodeId(credit.getId()), new AttachRequest(booking.id())));
        assertTrue(e.getMessage().contains("cancelled"), e.getMessage());
    }

    // ── scope ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a seller sees the money that landed in their own accounts, and nobody else's")
    void aSellerSeesOnlyTheirOwnStatements() {
        CoopStatement mine = unused(till, "MINE", "1000", OffsetDateTime.now());
        Long elsewhere = anotherTenant();
        CoopStatement theirs = unused(till(elsewhere, null), "THRS", "2000", OffsetDateTime.now());

        signInAsSeller(tenantId);
        var page = service.list(unusedOnly());
        assertTrue(page.getContent().stream().anyMatch(r -> r.refNo().equals(mine.getRefNo())));
        assertTrue(page.getContent().stream().noneMatch(r -> r.refNo().equals(theirs.getRefNo())));
        assertThrows(ResourceNotFoundException.class,
                () -> service.find(HashIdUtil.encodeId(theirs.getId())));
        assertNull(service.find(HashIdUtil.encodeId(mine.getId())).rawPayload(),
                "the bank's raw message is the platform's to read");
    }
}
