package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.beneficiaries.Beneficiary;
import com.hodi.modules.beneficiaries.BeneficiaryRepository;
import com.hodi.modules.beneficiaries.BeneficiaryTypeRepository;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingDtos.InstalmentLine;
import com.hodi.modules.bookings.BookingService;
import com.hodi.modules.developments.DevelopmentFinanceDtos.RecordExpenditureRequest;
import com.hodi.modules.developments.DevelopmentFinanceDtos.StatementSummary;
import com.hodi.modules.disbursements.Disbursement;
import com.hodi.modules.disbursements.DisbursementRepository;
import com.hodi.modules.payments.PaymentAccount;
import com.hodi.modules.payments.PaymentAccountRepository;
import com.hodi.modules.payments.PaymentTypeRepository;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.modules.reports.ReportService;
import com.hodi.modules.reports.ReportService.ReportQuery;
import com.hodi.modules.reports.ReportService.ReportResult;
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
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A development's two statements: what came in, what went out, totalled and exportable, and read only by
 * whoever may read the development.
 *
 * <p>Money in is a buyer's cash payment against a booking. Money out is a cost recorded by hand, a payment
 * through Hodi the bank confirmed, and one still awaiting approval — which the summary shows in flight and
 * never adds to what left. The cost a paid payment writes for itself is the same money as the payment and
 * is not listed twice.
 */
@SpringBootTest
@Transactional
class DevelopmentStatementsIT {

    @Autowired DevelopmentFinanceService finance;
    @Autowired ReportService reports;
    @Autowired PaidCostRecorder recorder;
    @Autowired BookingService bookings;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired PropertyRepository units;
    @Autowired DevelopmentCostCategoryRepository categories;
    @Autowired DisbursementRepository disbursements;
    @Autowired BeneficiaryRepository beneficiaries;
    @Autowired BeneficiaryTypeRepository beneficiaryTypes;
    @Autowired PaymentAccountRepository accounts;
    @Autowired PaymentTypeRepository types;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private Development development;
    private Disbursement paid;

    @BeforeEach
    void build() {
        tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        jdbc.update("update payment_types set status = 1 where provider_type = 'COOP_PESALINK'");
        asOwner();

        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Statement Heights " + RrnGenerator.generate("X").substring(0, 6))
                .developmentType("APARTMENT").currency("KES").listingState(AppConstant.LISTING_DRAFT).build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(new BigDecimal("9500000")).build());
        Property unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("S-1-01")
                .saleState(AppConstant.UNIT_AVAILABLE)
                .constructionStatus(AppConstant.BUILD_PLANNED).build());

        // Money in: a buyer's deposit, in cash, against a booking on the unit.
        BookingResponse booking = bookings.create(HashIdUtil.encodeId(development.getId()), new CreateBookingRequest(
                HashIdUtil.encodeId(unit.getId()), "Asha Mwangi", "+254712000111", null, null,
                new BigDecimal("9500000"), new BigDecimal("950000"), AppConstant.PLAN_INSTALMENTS, 14, null,
                List.of(new InstalmentLine("Deposit", LocalDate.now().minusDays(10), new BigDecimal("950000")),
                        new InstalmentLine("Balance", LocalDate.now().plusDays(90), new BigDecimal("8550000")))));
        jdbc.update("""
                insert into payments (reference, booking_id, development_id, property_id, tenant_id, development_name,
                    unit_label, buyer_name, paid_on, amount, currency, source, method, status, created_by)
                values (?, ?, ?, ?, ?, ?, 'S-1-01', 'Asha Mwangi', current_date, 950000, 'KES', 'MANUAL', 'CASH', 1, 'test')
                """, RrnGenerator.generate("PY"), HashIdUtil.decodeId(booking.id()), development.getId(),
                unit.getId(), tenantId, development.getName());

        // Money out by hand: a cost typed in.
        finance.recordExpenditure(HashIdUtil.encodeId(development.getId()), new RecordExpenditureRequest(
                HashIdUtil.encodeId(categories.findAvailable().getFirst().getId()), null, "SPENT",
                new BigDecimal("20000"), null, "Site casuals", "RCPT-1", "Cash on site", null));

        // Money out through Hodi: one the bank confirmed, and one still waiting for a second person.
        var pesalink = types.findByProviderType("COOP_PESALINK").orElseThrow();
        PaymentAccount account = new PaymentAccount();
        account.stampChannel(pesalink);
        account.setTenantId(tenantId);
        account.setAccountNo("01" + java.util.concurrent.ThreadLocalRandom.current().nextLong(10_000_000L, 99_999_999L) + "01");
        account.setStatus(AppConstant.STATUS_ACTIVE);
        account.setStatusFlag(AppConstant.FLAG_ACTIVE);
        account = accounts.saveAndFlush(account);
        Long supplierType = beneficiaryTypes.findAll().stream().filter(t -> t.getCode().equals("SUPPLIER")).findFirst().orElseThrow().getId();
        Beneficiary supplier = beneficiaries.saveAndFlush(Beneficiary.builder()
                .reference(RrnGenerator.generate("BN")).tenantId(tenantId).typeId(supplierType).name("Mwangi Hardware")
                .bankCode("0011").accountNo("01" + java.util.concurrent.ThreadLocalRandom.current().nextLong(10_000_000L, 99_999_999L) + "00")
                .verification(Beneficiary.VERIFIED).confirmedName("CONFIRMED HOLDER").confirmedAt(OffsetDateTime.now())
                .status(AppConstant.STATUS_ACTIVE).statusFlag(AppConstant.FLAG_ACTIVE).createdBy("test").build());
        paid = disbursements.saveAndFlush(transfer(supplier, account, "125000", Disbursement.SUCCEEDED));
        disbursements.saveAndFlush(transfer(supplier, account, "40000", Disbursement.AWAITING_APPROVAL));
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private Disbursement transfer(Beneficiary to, PaymentAccount from, String amount, String state) {
        boolean decided = !Disbursement.AWAITING_APPROVAL.equals(state);
        return Disbursement.builder()
                .reference(RrnGenerator.generate("DB")).payeeKind(Disbursement.PAYEE_BENEFICIARY)
                .payeeName(to.getName()).bankCode(to.getBankCode()).accountNo(to.getAccountNo())
                .validatedName(to.getConfirmedName()).validatedAt(OffsetDateTime.now())
                .amount(new BigDecimal(amount)).currency("KES").purpose("Cement, certificate 3")
                .narration("Cement").sourceAccountId(from.getId())
                .developmentId(development.getId()).costCategoryId(categories.findAvailable().getFirst().getId())
                .beneficiaryId(to.getId()).beneficiaryType("Supplier").ownerTenantId(tenantId)
                .invoiceReference("INV-7").managedBy(Disbursement.MANAGED_BY_OWNER)
                .state(state).callbackTimeoutSeconds(300).madeBy("maker")
                .checkedBy(decided ? "checker" : null).checkedAt(decided ? OffsetDateTime.now() : null)
                .sentAt(decided ? OffsetDateTime.now() : null).settledAt(decided ? OffsetDateTime.now() : null)
                .bankReference(decided ? "FT" + amount : null)
                .createdBy("maker").updatedBy("maker").build();
    }

    private void asOwner() { signIn(tenantId, false); }
    private void asStranger(Long other) { signIn(other, false); }

    private void signIn(Long tenant, boolean platform) {
        Long userId = jdbc.queryForObject("select id from users order by id limit 1", Long.class);
        User user = User.builder().id(userId).username("stmt-test").password("x").email("s@example.invalid")
                .firstName("Sta").lastName("Tement").status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(userId)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER").tenantId(tenant).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("DEVELOPMENTS_FINANCE_VIEW", "DEVELOPMENTS_FINANCE_RECORD", "BOOKINGS_CREATE", "BOOKINGS_VIEW",
                        "REPORTS_VIEW", "REPORTS_EXPORT"),
                List.of(tenant), platform, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private ReportQuery forThisDevelopment() {
        return new ReportQuery(LocalDate.now().minusDays(1), LocalDate.now(), null,
                Map.of("development_name", development.getName()), List.of(), 0, 50);
    }

    // ── the tests ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the summary totals what came in and what left, and shows what is in flight beside it")
    void theSummary() {
        StatementSummary s = finance.statement(HashIdUtil.encodeId(development.getId()),
                LocalDate.now().minusDays(1), LocalDate.now());

        assertEquals(new BigDecimal("950000.00"), s.moneyIn().setScale(2));
        assertEquals(1, s.paymentsIn());
        assertEquals(new BigDecimal("145000.00"), s.moneyOut().setScale(2), "the confirmed payment and the hand-recorded cost");
        assertEquals(2, s.paymentsOut());
        assertEquals(new BigDecimal("40000.00"), s.inFlight().setScale(2), "awaiting approval: beside, never added");
        assertEquals(new BigDecimal("805000.00"), s.net().setScale(2));
        assertEquals("By hand", s.inByPlacement().getFirst().label());
        assertTrue(s.outByBeneficiaryType().stream().anyMatch(x -> "Supplier".equals(x.label())
                && x.amount().compareTo(new BigDecimal("125000")) == 0));
        assertTrue(s.outByBeneficiaryType().stream().anyMatch(x -> "—".equals(x.label())
                && x.amount().compareTo(new BigDecimal("20000")) == 0), "a one-off payee has no kind");
    }

    @Test
    @DisplayName("the two statements list their rows for the owner, and nothing for a stranger")
    void theStatementsAreScoped() {
        ReportResult out = reports.run("DEVELOPMENT_MONEY_OUT", forThisDevelopment());
        assertEquals(3, out.total(), "two payments through Hodi in their states, one manual entry");
        assertTrue(out.rows().stream().anyMatch(r -> "Awaiting approval".equals(r.get("state"))));
        assertTrue(out.rows().stream().anyMatch(r -> "Manual entry".equals(r.get("route")) && "Site casuals".equals(r.get("payee"))));
        assertTrue(out.rows().stream().anyMatch(r -> "Paid".equals(r.get("state")) && "••" .concat(
                String.valueOf(r.get("paid_from")).substring(2)).equals(r.get("paid_from"))), "the account is masked");

        ReportResult in = reports.run("DEVELOPMENT_MONEY_IN", forThisDevelopment());
        assertEquals(1, in.total());
        assertEquals("By hand", in.rows().getFirst().get("how_placed"));
        assertEquals("Asha Mwangi", in.rows().getFirst().get("buyer_name"));

        String csv = reports.csv("DEVELOPMENT_MONEY_OUT", forThisDevelopment());
        assertTrue(csv.contains(paid.getReference()) && csv.contains("Mwangi Hardware"));

        asStranger(jdbc.queryForObject(
                "insert into tenants (name, slug, tenant_ref, created_by) values (?, ?, ?, 'test') returning id",
                Long.class, "Elsewhere " + development.getReference(), "elsewhere-" + development.getReference().toLowerCase(),
                RrnGenerator.generate("TN")));
        assertEquals(0, reports.run("DEVELOPMENT_MONEY_OUT", forThisDevelopment()).total());
        assertEquals(0, reports.run("DEVELOPMENT_MONEY_IN", forThisDevelopment()).total());
    }

    @Test
    @DisplayName("the cost a paid payment writes for itself is the same money, and is not listed twice")
    void aPaymentsOwnCostIsNotCountedAgain() {
        recorder.record(paid).orElseThrow();
        assertEquals(3, reports.run("DEVELOPMENT_MONEY_OUT", forThisDevelopment()).total());
        StatementSummary s = finance.statement(HashIdUtil.encodeId(development.getId()),
                LocalDate.now().minusDays(1), LocalDate.now());
        assertEquals(new BigDecimal("145000.00"), s.moneyOut().setScale(2));
    }
}
