package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.infra.vault.VaultStorage;
import com.hodi.modules.analytics.ChartService;
import com.hodi.modules.developments.DevelopmentFinanceDtos.*;
import com.hodi.modules.kyc.DocumentService;
import com.hodi.modules.kyc.VaultDocumentRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A development's money, end to end: the ledger, the recount, the summary, the facility, the evidence, and
 * who may see any of it.
 *
 * <p>Two of these are the ones that matter. That a voided line drops out of the phase's spent figure — the
 * whole argument for a ledger over a typed number is that the number cannot drift from its lines. And that a
 * lender's officer, who has no tenant at all, sees their own project's money and nobody else's — the tenant
 * predicate every other table uses would show them nothing, and the fix for that must not show them
 * everything.
 */
@SpringBootTest
@Transactional
class DevelopmentFinanceIT {

    @Autowired DevelopmentFinanceService finance;
    @Autowired CostCategoryService categoryService;
    @Autowired ChartService charts;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentPhaseRepository phases;
    @Autowired DevelopmentCostCategoryRepository categories;
    @Autowired VaultDocumentRepository vaultDocuments;
    @Autowired VaultStorage vaultStorage;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private Long otherTenantId;
    private Long institutionId;
    private Development development;
    private DevelopmentPhase groundworks;
    private DevelopmentPhase superstructure;
    private Long categoryId;
    private final List<String> writtenKeys = new ArrayList<>();

    @BeforeEach
    void build() {
        List<Long> tenants = jdbc.queryForList(
                "select id from tenants where status <> 5 order by id limit 2", Long.class);
        tenantId = tenants.getFirst();
        otherTenantId = tenants.get(1);
        institutionId = jdbc.queryForObject("select id from lending_institutions order by id limit 1", Long.class);

        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Ledger Court").developmentType("APARTMENT").currency("KES")
                .budgetAmount(new BigDecimal("50000000")).facilityAmount(new BigDecimal("30000000"))
                .facilityReference("FAC-1").startedOn(LocalDate.now().minusMonths(6))
                .projectedCompletionOn(LocalDate.now().plusMonths(6)).build());
        groundworks = phases.save(DevelopmentPhase.builder().reference(RrnGenerator.generate("PH")).developmentId(development.getId())
                .sequenceNo((short) 1).name("Groundworks").budgetAmount(new BigDecimal("10000000"))
                .plannedSpend(new BigDecimal("10000000"))
                .plannedCompletionOn(LocalDate.now().minusMonths(1)).build());
        superstructure = phases.save(DevelopmentPhase.builder().reference(RrnGenerator.generate("PH")).developmentId(development.getId())
                .sequenceNo((short) 2).name("Superstructure").budgetAmount(new BigDecimal("25000000"))
                .plannedSpend(new BigDecimal("25000000"))
                .plannedCompletionOn(LocalDate.now().plusMonths(4)).build());
        categoryId = categories.findByCode("CONSTRUCTION").orElseThrow().getId();

        signInAsSeller(tenantId, "DEVELOPMENTS_FINANCE_VIEW", "DEVELOPMENTS_FINANCE_RECORD");
    }

    /*
     * Hash ids are salted per caller, so every hash here is minted at the moment of use — by whoever is signed
     * in at that moment — rather than once in set-up. A hash encoded for the seller is not one the lender
     * can present.
     */
    private String categoryHash() {
        return HashIdUtil.encodeId(categoryId);
    }

    @AfterEach
    void cleanUp() {
        writtenKeys.forEach(vaultStorage::delete);
        SecurityContextHolder.clearContext();
    }

    private void signIn(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void signInAsSeller(Long tenant, String... permissions) {
        User user = User.builder().id(1L).username("finance-seller").password("x")
                .email("f@example.invalid").firstName("Fin").lastName("Seller")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenant).tenantName("Test Seller").status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of(permissions), List.of(tenant), false, true));
    }

    private void signInAsLender(Long institution, String... permissions) {
        User user = User.builder().id(2L).username("finance-lender").password("x")
                .email("l@example.invalid").firstName("Len").lastName("Der")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(2L).userId(2L)
                .profileType(AppConstant.ACTOR_LENDER).userTypeCode("LENDER_ADMIN")
                .institutionId(institution).status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of(permissions), List.of(), false, true));
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertMoney(expected, actual, null);
    }

    /** Scale-blind: 50000000 from the entity and 50000000.00 from the view are the same money. */
    private static void assertMoney(String expected, BigDecimal actual, String message) {
        assertNotNull(actual, message == null ? "expected " + expected : message);
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                (message == null ? "" : message + ": ") + "expected " + expected + " but was " + actual);
    }

    private String devHash() {
        return HashIdUtil.encodeId(development.getId());
    }

    private ExpenditureResponse spend(DevelopmentPhase phase, String amount) {
        return finance.recordExpenditure(devHash(), new RecordExpenditureRequest(categoryHash(),
                phase == null ? null : HashIdUtil.encodeId(phase.getId()), "SPENT", new BigDecimal(amount),
                LocalDate.now().minusDays(3), "Bamburi Cement", "INV-" + amount, null));
    }

    // ── the ledger and the recount ────────────────────────────────────────────

    @Test
    @DisplayName("a phase's spent figure is the sum of its lines, and a voided line drops out")
    void phaseMoneyIsRecountedFromTheLedger() {
        spend(groundworks, "4000000");
        ExpenditureResponse second = spend(groundworks, "1500000");
        finance.recordExpenditure(devHash(), new RecordExpenditureRequest(categoryHash(),
                HashIdUtil.encodeId(groundworks.getId()), "COMMITTED", new BigDecimal("2000000"),
                null, "Steel supplier", "PO-9", "Rebar order"));

        DevelopmentPhase after = phases.findById(groundworks.getId()).orElseThrow();
        assertMoney("5500000", after.getSpentAmount());
        assertMoney("2000000", after.getCommittedAmount());

        ExpenditureResponse voided = finance.voidExpenditure(devHash(), second.id(),
                new VoidRequest("Duplicate of INV-4000000"));
        assertEquals(AppConstant.STATUS_INACTIVE, voided.status());
        assertEquals("Duplicate of INV-4000000", voided.voidReason());

        after = phases.findById(groundworks.getId()).orElseThrow();
        assertMoney("4000000", after.getSpentAmount(), "the voided line must fall out of the phase's spent figure on its own");

        // Voiding twice is a conflict, not a silent no-op — the second reason would be lost.
        assertThrows(HodiException.class, () -> finance.voidExpenditure(devHash(), second.id(),
                new VoidRequest("again")));
        // The voided line is still on the ledger, marked.
        assertTrue(finance.expenditures(devHash(), new ExpenditureListRequest()).getContent().stream()
                .anyMatch(e -> e.id().equals(second.id()) && "Voided".equals(e.statusLabel())));
    }

    @Test
    @DisplayName("the summary adds the ledger, the facility and the programme up the same way the view does")
    void summaryReadsFromTheView() {
        spend(groundworks, "4000000");
        spend(superstructure, "6000000");
        spend(null, "500000");
        finance.recordDrawdown(devHash(), new RecordDrawdownRequest(new BigDecimal("12000000"),
                LocalDate.now().minusDays(10), "DRW-1", null));

        FinanceSummary s = finance.summary(devHash());
        assertMoney("50000000", s.budget());
        assertMoney("10500000", s.spent());
        assertMoney("39500000", s.remaining());
        // Groundworks was due a month ago: its planned spend is what should have gone by now.
        assertMoney("10000000", s.plannedToDate());
        assertMoney("-500000", s.spendVariance(), "spent more than was planned by now");
        assertMoney("12000000", s.drawn());
        assertMoney("18000000", s.undrawn());
        assertEquals(2, s.phases());
        assertEquals(1, s.phasesLate(), "groundworks has no actual completion and its date has passed");
        assertNotNull(s.daysRemaining());
        assertTrue(s.daysRemaining() > 0);
        assertEquals(2, s.phaseRows().size());
        PhaseMoneyRow ground = s.phaseRows().getFirst();
        assertTrue(ground.late());
        assertMoney("6000000", ground.remaining());
    }

    @Test
    @DisplayName("a cost cannot be filed on another development's phase, a suspended category, or the future")
    void recordingRules() {
        Development other = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Elsewhere").developmentType("APARTMENT").build());
        DevelopmentPhase foreign = phases.save(DevelopmentPhase.builder().reference(RrnGenerator.generate("PH")).developmentId(other.getId())
                .sequenceNo((short) 1).name("Theirs").build());

        assertThrows(HodiException.class, () -> finance.recordExpenditure(devHash(),
                new RecordExpenditureRequest(categoryHash(), HashIdUtil.encodeId(foreign.getId()), "SPENT",
                        BigDecimal.TEN, null, null, null, null)));
        assertThrows(HodiException.class, () -> finance.recordExpenditure(devHash(),
                new RecordExpenditureRequest(categoryHash(), null, "SPENT", BigDecimal.TEN,
                        LocalDate.now().plusDays(1), null, null, null)));
        assertThrows(HodiException.class, () -> finance.recordExpenditure(devHash(),
                new RecordExpenditureRequest(categoryHash(), null, "MAYBE", BigDecimal.TEN, null, null, null, null)));

        // Suspend the category: the form stops offering it and a new line under it is refused.
        signInAsSeller(tenantId, "COST_CATEGORIES_MANAGE", "DEVELOPMENTS_FINANCE_RECORD",
                "DEVELOPMENTS_FINANCE_VIEW");
        categoryService.setStatus(categoryHash(), false);
        assertFalse(categoryService.available().stream().anyMatch(c -> c.id().equals(categoryHash())));
        assertThrows(HodiException.class, () -> spend(groundworks, "100"));
        categoryService.setStatus(categoryHash(), true);
        assertNotNull(spend(groundworks, "100"));
    }

    // ── the evidence ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("evidence goes into the vault and comes back to anyone who may see the development's money")
    void evidenceIsReadThroughTheDevelopment() {
        ExpenditureResponse line = spend(groundworks, "250000");
        ExpenditureResponse withEvidence = finance.attachEvidence(devHash(), line.id(),
                new MockMultipartFile("file", "invoice.pdf", "application/pdf", "%PDF-1.4 test".getBytes()));
        assertNotNull(withEvidence.documentReference());
        vaultDocuments.findByReference(withEvidence.documentReference())
                .ifPresent(d -> writtenKeys.add(d.getStorageKey()));

        DocumentService.Fetched fetched = finance.evidence(devHash(), withEvidence.documentReference());
        assertEquals("invoice.pdf", fetched.fileName());
        assertEquals("%PDF-1.4 test", new String(fetched.bytes()));

        // Through another development it is not found: the document belongs to this one's lines.
        Development other = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Not this one").developmentType("APARTMENT").build());
        assertThrows(ResourceNotFoundException.class, () -> finance.evidence(
                HashIdUtil.encodeId(other.getId()), withEvidence.documentReference()));
    }

    // ── who may see it ────────────────────────────────────────────────────────

    @Test
    @DisplayName("another organisation cannot read, record on, or chart this development's money")
    void otherOrganisationsAreNotFound() {
        spend(groundworks, "4000000");
        signInAsSeller(otherTenantId, "DEVELOPMENTS_FINANCE_VIEW", "DEVELOPMENTS_FINANCE_RECORD");

        assertThrows(ResourceNotFoundException.class, () -> finance.summary(devHash()));
        assertThrows(ResourceNotFoundException.class, () -> finance.drawdowns(devHash()));
        assertThrows(ResourceNotFoundException.class, () -> spend(groundworks, "1"));
        assertThrows(ResourceNotFoundException.class, () -> charts.draw("dev-spend", devHash()));
    }

    @Test
    @DisplayName("a lender sees the money on its own financed project, and its charts, and not a seller's")
    void lenderScope() {
        Development financed = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).institutionId(institutionId).sellingTenantId(otherTenantId)
                .name("Bank Towers").developmentType("APARTMENT").currency("KES")
                .facilityAmount(new BigDecimal("80000000")).budgetAmount(new BigDecimal("90000000")).build());
        signInAsLender(institutionId, "DEVELOPMENTS_VIEW", "DEVELOPMENTS_FINANCE_VIEW",
                "DEVELOPMENTS_FINANCE_RECORD");
        String financedHash = HashIdUtil.encodeId(financed.getId());
        finance.recordDrawdown(financedHash, new RecordDrawdownRequest(new BigDecimal("20000000"),
                LocalDate.now().minusDays(1), "TR-77", "First tranche"));
        finance.recordExpenditure(financedHash, new RecordExpenditureRequest(categoryHash(), null, "SPENT",
                new BigDecimal("7000000"), null, "Main contractor", "CERT-1", null));

        FinanceSummary s = finance.summary(financedHash);
        assertMoney("20000000", s.drawn());
        assertMoney("60000000", s.undrawn());
        assertMoney("7000000", s.spent());

        // The lender's funding chart for its project has a Drawn bar with the tranche on it. Bucketless
        // rows are drawn as categories: the labels are the series names, and there is one series of values.
        ChartService.ChartData funding = charts.draw("dev-funding", financedHash);
        int drawn = funding.labels().indexOf("Drawn");
        assertTrue(drawn >= 0, "a Drawn bar: " + funding.labels());
        assertMoney("20000000", funding.series().getFirst().values().get(drawn));

        // Summed across everything the lender may see, the seller's project is not in it.
        BigDecimal lenderSpend = charts.draw("dev-spend", null).series().stream()
                .filter(series -> "Spent".equals(series.name())).findFirst()
                .map(ChartService.Series::total).orElse(BigDecimal.ZERO);
        assertTrue(lenderSpend.compareTo(new BigDecimal("7000000")) >= 0);

        // And the seller's own development is not the lender's to read.
        assertThrows(ResourceNotFoundException.class, () -> finance.summary(devHash()));
        assertThrows(ResourceNotFoundException.class, () -> charts.draw("dev-funding", devHash()));
    }

    @Test
    @DisplayName("a drawdown needs a facility to draw against; a subject-only chart needs a subject")
    void drawdownAndChartRules() {
        Development noFacility = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Self-funded").developmentType("APARTMENT").build());
        assertThrows(HodiException.class, () -> finance.recordDrawdown(HashIdUtil.encodeId(noFacility.getId()),
                new RecordDrawdownRequest(BigDecimal.TEN, null, null, null)));

        signInAsSeller(tenantId, "DEVELOPMENTS_VIEW", "DEVELOPMENTS_FINANCE_VIEW");
        assertThrows(ResourceNotFoundException.class, () -> charts.draw("dev-completion", null),
                "completion over time means nothing summed across projects");
        assertNotNull(charts.draw("dev-completion", devHash()));
        // Not offered in the list either.
        assertFalse(charts.available().stream().anyMatch(c -> "dev-completion".equals(c.get("key"))));
    }
}
