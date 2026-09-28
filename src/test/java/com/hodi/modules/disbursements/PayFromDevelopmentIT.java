package com.hodi.modules.disbursements;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.approvals.ApprovalService.DecisionRequest;
import com.hodi.modules.approvals.ApprovalWorkflow;
import com.hodi.modules.beneficiaries.Beneficiary;
import com.hodi.modules.beneficiaries.BeneficiaryRepository;
import com.hodi.modules.beneficiaries.BeneficiaryTypeRepository;
import com.hodi.modules.beneficiaries.PayoutAccountCheck;
import com.hodi.modules.developments.*;
import com.hodi.modules.developments.DevelopmentFinanceDtos.ExpenditureResponse;
import com.hodi.modules.developments.DevelopmentFinanceDtos.RecordExpenditureRequest;
import com.hodi.modules.developments.DevelopmentMoneySettingsService.SaveMoneySettingsRequest;
import com.hodi.modules.disbursements.DisbursementDtos.*;
import com.hodi.modules.payments.PaymentAccount;
import com.hodi.modules.payments.PaymentAccountRepository;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A development paying one of its beneficiaries out of the owner's own account.
 *
 * <p>The engine's own guarantees — sent once, never re-sent, the bank's answer read per leg — are
 * {@code DisbursementIT}'s business, against a Co-op in this JVM. What is tested here is what the development
 * adds: who may propose and from which account, that the bank's name still matches the beneficiary's, whose
 * checker decides, who may read the payment afterwards, and that a payment that succeeded writes its cost
 * exactly once. The bank is a fake that answers by account number.
 */
@SpringBootTest
@Transactional
class PayFromDevelopmentIT {

    @TestConfiguration
    static class FakeBank {
        @Bean @Primary
        PayoutAccountCheck payoutAccountCheck() {
            return (bankCode, accountNo) -> accountNo.endsWith("99")
                    ? new PayoutAccountCheck.Answer(accountNo, bankCode, null, "No such account.")
                    : accountNo.endsWith("77")
                    ? new PayoutAccountCheck.Answer(accountNo, "0011", "SOMEBODY ELSE", null)
                    : new PayoutAccountCheck.Answer(accountNo, "0011", "CONFIRMED HOLDER", null);
        }
    }

    @Autowired DisbursementService service;
    @Autowired DisbursementRepository rows;
    @Autowired PaidCostRecorder recorder;
    @Autowired DevelopmentFinanceService finance;
    @Autowired DevelopmentMoneySettingsService settings;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentCostCategoryRepository categories;
    @Autowired DevelopmentExpenditureRepository expenditures;
    @Autowired BeneficiaryRepository beneficiaries;
    @Autowired BeneficiaryTypeRepository beneficiaryTypes;
    @Autowired PaymentAccountRepository accounts;
    @Autowired PaymentTypeRepository types;
    @Autowired ApprovalService approvals;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private Long makerId;
    private Long checkerId;
    private Development development;
    private PaymentAccount ownersAccount;
    private Beneficiary supplier;

    @BeforeEach
    void build() {
        tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        makerId = jdbc.queryForObject("select id from users order by id limit 1", Long.class);
        checkerId = jdbc.queryForObject("select id from users order by id offset 1 limit 1", Long.class);
        jdbc.update("update payment_types set status = 1 where provider_type = 'COOP_PESALINK'");

        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Pay Heights").developmentType("APARTMENT").currency("KES")
                .listingState(AppConstant.LISTING_DRAFT).build());
        PaymentType pesalink = types.findByProviderType("COOP_PESALINK").orElseThrow();
        PaymentAccount account = new PaymentAccount();
        account.stampChannel(pesalink);
        account.setTenantId(tenantId);
        account.setAccountNo("01" + RrnGenerator.generate("A").substring(0, 10));
        account.setStatus(AppConstant.STATUS_ACTIVE);
        account.setStatusFlag(AppConstant.FLAG_ACTIVE);
        ownersAccount = accounts.saveAndFlush(account);
        supplier = beneficiary("Mwangi Hardware", "00", Beneficiary.VERIFIED, AppConstant.STATUS_ACTIVE);
        asMaker();
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    // ── people ───────────────────────────────────────────────────────────────

    private void asMaker() { signIn(makerId, AppConstant.ACTOR_SELLER, "SELLER_OWNER", tenantId, false,
            "DISBURSEMENTS_MAKE", "DISBURSEMENTS_VIEW", "DEVELOPMENTS_FINANCE_VIEW", "DEVELOPMENTS_FINANCE_RECORD"); }
    private void asChecker() { signIn(checkerId, AppConstant.ACTOR_SELLER, "SELLER_OWNER", tenantId, false,
            "DISBURSEMENTS_APPROVE", "APPROVALS_VIEW", "DISBURSEMENTS_VIEW"); }
    private void asBank(String... perms) { signIn(makerId, AppConstant.ACTOR_PLATFORM, "SUPER_ADMIN", null, true, perms); }
    private void asStranger(Long otherTenant) { signIn(checkerId, AppConstant.ACTOR_SELLER, "SELLER_OWNER", otherTenant,
            false, "DISBURSEMENTS_APPROVE", "DISBURSEMENTS_VIEW", "APPROVALS_VIEW"); }

    private void signIn(Long userId, String actor, String userType, Long tenant, boolean platform, String... perms) {
        User user = User.builder().id(userId).username("pay-" + userId).password("x")
                .email("p" + userId + "@example.invalid").firstName("Pat").lastName("Payer")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(userId).userId(userId)
                .profileType(actor).userTypeCode(userType).tenantId(tenant).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile, Set.of(perms),
                tenant == null ? List.of() : List.of(tenant), platform, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private Beneficiary beneficiary(String name, String suffix, String verification, int status) {
        Long type = beneficiaryTypes.findAll().stream().filter(t -> t.getCode().equals("SUPPLIER")).findFirst().orElseThrow().getId();
        return beneficiaries.saveAndFlush(Beneficiary.builder()
                .reference(RrnGenerator.generate("BN")).tenantId(tenantId).typeId(type).name(name)
                // Random digits, not a slice of a reference: a reference's first characters repeat within a second.
                .bankCode("0011").accountNo("01" + java.util.concurrent.ThreadLocalRandom.current().nextLong(10_000_000L, 99_999_999L) + suffix)
                .verification(verification).confirmedName(Beneficiary.VERIFIED.equals(verification) ? "CONFIRMED HOLDER" : null)
                .confirmedAt(OffsetDateTime.now()).status(status)
                .statusFlag(status == AppConstant.STATUS_ACTIVE ? AppConstant.FLAG_ACTIVE : AppConstant.FLAG_NEW)
                .createdBy("test").build());
    }

    private String id(Development d) { return HashIdUtil.encodeId(d.getId()); }
    private String categoryId() { return HashIdUtil.encodeId(categories.findAvailable().getFirst().getId()); }

    private PayFromDevelopmentRequest pay(Beneficiary to, PaymentAccount from, String amount) {
        return new PayFromDevelopmentRequest(HashIdUtil.encodeId(to.getId()), HashIdUtil.encodeId(from.getId()),
                categoryId(), null, new BigDecimal(amount), "Cement, certificate 3", "INV-0042", null);
    }

    private void bankManages(boolean bank) {
        var was = SecurityContextHolder.getContext().getAuthentication();
        asBank("DEVELOPMENT_FINANCE_SETTINGS", "DEVELOPMENTS_FINANCE_VIEW");
        settings.save(id(development), new SaveMoneySettingsRequest("BANK", bank ? "BANK" : "OWNER"));
        development = developments.findById(development.getId()).orElseThrow();
        SecurityContextHolder.getContext().setAuthentication(was);
    }

    // ── proposing ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an owner's maker pays a supplier from the development's own account, and the owner's checker gets it")
    void proposedFromTheDevelopment() {
        PaymentOptions options = service.paymentOptions(id(development));
        assertTrue(options.mayPay());
        assertEquals(1, options.beneficiaries().size());
        assertEquals(ownersAccount.getAccountNo(), options.debitAccounts().getFirst().accountNo());

        DisbursementResponse proposed = service.payFromDevelopment(id(development), pay(supplier, ownersAccount, "125000"));

        assertEquals(Disbursement.PAYEE_BENEFICIARY, proposed.payeeKind());
        assertEquals("Mwangi Hardware", proposed.payeeName());
        assertEquals("CONFIRMED HOLDER", proposed.validatedName(), "asked the bank again, and stored its answer");
        assertEquals("Supplier", proposed.beneficiaryType());
        assertEquals("Pay Heights", proposed.developmentName());
        assertEquals("INV-0042", proposed.invoiceReference());
        assertEquals(Disbursement.MANAGED_BY_OWNER, proposed.managedBy());
        assertEquals(ownersAccount.getAccountNo(), proposed.sourceAccountNo());
        assertEquals(Disbursement.AWAITING_APPROVAL, proposed.state());

        ApprovalWorkflow waiting = approvals.pendingFor(AppConstant.APPROVAL_ENTITY_DISBURSEMENT,
                HashIdUtil.decodeId(proposed.id()), AppConstant.APPROVAL_ACTION_SEND).orElseThrow();
        assertEquals(tenantId, waiting.getTenantId(), "scoped to the organisation whose money it is");
    }

    @Test
    @DisplayName("refused: an unpayable beneficiary, an account that is not the development's, and a holder the bank now names differently")
    void whatIsRefused() {
        Beneficiary waiting = beneficiary("Not Yet Ltd", "00", Beneficiary.VERIFIED, AppConstant.STATUS_NEW);
        assertTrue(assertThrows(HodiException.class, () -> service.payFromDevelopment(id(development),
                pay(waiting, ownersAccount, "1000"))).getMessage().contains("second person has not approved"));

        Beneficiary unconfirmed = beneficiary("Ghost Ltd", "99", Beneficiary.UNVERIFIED, AppConstant.STATUS_ACTIVE);
        assertTrue(assertThrows(HodiException.class, () -> service.payFromDevelopment(id(development),
                pay(unconfirmed, ownersAccount, "1000"))).getMessage().contains("bank has not confirmed"));

        // Registered as CONFIRMED HOLDER; the bank now says somebody else holds it.
        Beneficiary moved = beneficiary("Moved Ltd", "77", Beneficiary.VERIFIED, AppConstant.STATUS_ACTIVE);
        HodiException changed = assertThrows(HodiException.class, () -> service.payFromDevelopment(id(development),
                pay(moved, ownersAccount, "1000")));
        assertTrue(changed.getMessage().contains("SOMEBODY ELSE") && changed.getMessage().contains("CONFIRMED HOLDER"));

        PaymentType pesalink = types.findByProviderType("COOP_PESALINK").orElseThrow();
        PaymentAccount platforms = new PaymentAccount();
        platforms.stampChannel(pesalink);
        platforms.setAccountNo("01" + RrnGenerator.generate("A").substring(0, 10));
        platforms.setStatus(AppConstant.STATUS_ACTIVE);
        platforms.setStatusFlag(AppConstant.FLAG_ACTIVE);
        platforms = accounts.saveAndFlush(platforms);
        PaymentAccount notTheirs = platforms;
        assertTrue(assertThrows(HodiException.class, () -> service.payFromDevelopment(id(development),
                pay(supplier, notTheirs, "1000"))).getMessage().contains("may pay from"),
                "the owner manages: the bank's account is not theirs to pay from");
    }

    @Test
    @DisplayName("where the bank manages spending, the owner cannot propose and the bank's checker decides")
    void theBankManages() {
        bankManages(true);
        assertFalse(service.paymentOptions(id(development)).mayPay());
        assertTrue(assertThrows(HodiException.class, () -> service.payFromDevelopment(id(development),
                pay(supplier, ownersAccount, "1000"))).getMessage().startsWith("The bank manages spending"));

        asBank("DISBURSEMENTS_MAKE", "DEVELOPMENTS_FINANCE_VIEW");
        DisbursementResponse proposed = service.payFromDevelopment(id(development), pay(supplier, ownersAccount, "1000"));
        assertEquals(Disbursement.MANAGED_BY_BANK, proposed.managedBy());
        ApprovalWorkflow waiting = approvals.pendingFor(AppConstant.APPROVAL_ENTITY_DISBURSEMENT,
                HashIdUtil.decodeId(proposed.id()), AppConstant.APPROVAL_ACTION_SEND).orElseThrow();
        assertNull(waiting.getTenantId(), "nobody's but the bank's to decide");
    }

    // ── deciding ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the owner's checker approves; a stranger's cannot; the maker cannot approve their own")
    void whoDecides() {
        DisbursementResponse proposed = service.payFromDevelopment(id(development), pay(supplier, ownersAccount, "5000"));
        Long id = HashIdUtil.decodeId(proposed.id());
        assertThrows(HodiException.class, () -> approvals.decideFor(AppConstant.APPROVAL_ENTITY_DISBURSEMENT, id,
                AppConstant.APPROVAL_ACTION_SEND, new DecisionRequest("APPROVED", "me")));

        asStranger(jdbc.queryForObject(
                "insert into tenants (name, slug, tenant_ref, created_by) values (?, ?, ?, 'test') returning id",
                Long.class, "Elsewhere " + id, "elsewhere-" + id, RrnGenerator.generate("TN")));
        assertThrows(HodiException.class, () -> approvals.decideFor(AppConstant.APPROVAL_ENTITY_DISBURSEMENT, id,
                AppConstant.APPROVAL_ACTION_SEND, new DecisionRequest("APPROVED", "not mine")));
        assertThrows(ResourceNotFoundException.class, () -> service.find(HashIdUtil.encodeId(id)), "nor read");
        assertTrue(service.list(new ListRequest()).getContent().isEmpty());

        asChecker();
        approvals.decideFor(AppConstant.APPROVAL_ENTITY_DISBURSEMENT, id, AppConstant.APPROVAL_ACTION_SEND,
                new DecisionRequest("APPROVED", "Invoice matches the certificate."));
        // The send runs after commit, which a rolled-back test never reaches; APPROVED is the state the sweep picks up.
        assertEquals(Disbursement.APPROVED, rows.findById(id).orElseThrow().getState());
        assertFalse(service.list(new ListRequest()).getContent().isEmpty(), "the owner's own, visible to the owner");
    }

    // ── the cost writes itself ───────────────────────────────────────────────

    @Test
    @DisplayName("a payment that succeeded writes its cost once; a failed one writes nothing")
    void theCostRecordsItself() {
        DisbursementResponse proposed = service.payFromDevelopment(id(development), pay(supplier, ownersAccount, "125000"));
        Disbursement row = rows.findById(HashIdUtil.decodeId(proposed.id())).orElseThrow();
        assertTrue(recorder.record(row).isEmpty(), "not until the bank says paid");

        // As the engine would leave it: approved by somebody (the schema insists), sent, then confirmed.
        row.setCheckedBy("pay-" + checkerId);
        row.setCheckedAt(OffsetDateTime.now());
        row.setState(Disbursement.SUCCEEDED);
        row.setSettledAt(OffsetDateTime.now());
        row.setBankReference("FT" + row.getReference());
        rows.saveAndFlush(row);
        DevelopmentExpenditure cost = recorder.record(row).orElseThrow();
        assertEquals(AppConstant.COST_SPENT, cost.getKind());
        assertEquals(new BigDecimal("125000.00"), cost.getAmount());
        assertEquals(supplier.getId(), cost.getBeneficiaryId());
        assertEquals("Mwangi Hardware", cost.getPayee());
        assertEquals("INV-0042", cost.getReferenceNo());
        assertEquals(PaidCostRecorder.ENTRY_DISBURSEMENT, cost.getEntryKind());
        assertEquals(row.getId(), cost.getDisbursementId());
        assertTrue(recorder.record(row).isEmpty(), "read the bank's answer twice, wrote the cost once");

        List<ExpenditureResponse> ledger = finance.expenditures(id(development),
                new DevelopmentFinanceDtos.ExpenditureListRequest()).getContent();
        assertEquals(1, ledger.size());
        assertEquals(row.getReference(), ledger.getFirst().disbursementReference());

        Disbursement failed = rows.findById(HashIdUtil.decodeId(
                service.payFromDevelopment(id(development), pay(supplier, ownersAccount, "1")).id())).orElseThrow();
        failed.setCheckedBy("pay-" + checkerId);
        failed.setCheckedAt(OffsetDateTime.now());
        failed.setState(Disbursement.FAILED);
        rows.saveAndFlush(failed);
        assertTrue(recorder.record(failed).isEmpty(), "nothing was spent");
    }

    @Test
    @DisplayName("a cost typed in by hand may name a beneficiary, and says it was a manual entry")
    void aManualCostNamesABeneficiary() {
        ExpenditureResponse line = finance.recordExpenditure(id(development), new RecordExpenditureRequest(
                categoryId(), null, "SPENT", new BigDecimal("8000"), null, null, "RCPT-9", "Paid in cash on site",
                HashIdUtil.encodeId(supplier.getId())));
        assertEquals("Mwangi Hardware", line.payee(), "the beneficiary's name is the payee");
        assertEquals(PaidCostRecorder.ENTRY_MANUAL, line.entryKind());
        assertNull(line.disbursementReference());
    }
}
