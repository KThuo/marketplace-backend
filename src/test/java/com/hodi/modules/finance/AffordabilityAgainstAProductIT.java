package com.hodi.modules.finance;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.finance.FinanceDtos.AffordabilityRequest;
import com.hodi.modules.finance.FinanceDtos;
import com.hodi.modules.finance.FinanceDtos.SaveProductRequest;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.users.User;
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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A mortgage the bank can create, and an answer computed against the one the buyer chose.
 *
 * <p>Two faults were reported together and they turn out to be one: nothing carried a product. It could not
 * be created, because creation refused anybody without an institution of their own — which is every person
 * who administers this platform — and it could not have been used if it had been, because the calculation
 * took the configured default rate for every household in the country whichever mortgage they picked.
 */
@SpringBootTest
@Transactional
class AffordabilityAgainstAProductIT {

    @Autowired MortgageProductService products;
    @Autowired MortgageProductRepository productRepository;
    @Autowired AffordabilityService affordability;
    @Autowired JdbcTemplate jdbc;

    private Long institutionId;

    @BeforeEach
    void signInAsPlatformStaff() {
        institutionId = jdbc.queryForObject(
                "select id from banks where status <> 5 order by id limit 1", Long.class);
        User user = User.builder().id(1L).username("finance-test").password("x")
                .email("f@example.invalid").firstName("Fin").lastName("Ance")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(AppConstant.ACTOR_PLATFORM).userTypeCode("SUPER_ADMIN")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("MORTGAGE_PRODUCTS_CREATE", "MORTGAGE_PRODUCTS_PUBLISH"), List.of(), true, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /**
     * A product with terms chosen to be checkable by hand: 12% a year over 240 months, lending at most 80%
     * of a price, allowing half of net income, and charging 1% to arrange.
     */
    private String aPublishedProduct() {
        var created = products.create(new SaveProductRequest(
                "Test Mortgage " + RrnGenerator.generate("T"), "For the test.",
                AppConstant.PRODUCT_MORTGAGE,
                new BigDecimal("12.000"), AppConstant.RATE_FIXED,
                null, null, (short) 60, (short) 240,
                new BigDecimal("80.00"), new BigDecimal("20.00"),
                new BigDecimal("1.00"), BigDecimal.ZERO, null,
                new BigDecimal("50000"), new BigDecimal("50.00"), null, null));
        products.setPublished(created.id(), true);
        return productRepository.findById(
                        com.hodi.security.hashid.HashIdUtil.decodeId(created.id()))
                .orElseThrow().getReference();
    }

    /** A live listing at a known price, so a check can be run against something real. */
    private String aLiveListingAt(BigDecimal price) {
        Long tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        String reference = RrnGenerator.generate("PR");
        jdbc.update("insert into properties (reference, tenant_id, title, property_type, listing_type, "
                        + "price, currency, county, listing_state, listing_kind, published_at, status, "
                        + "status_flag, created_at, updated_at) "
                        + "values (?, ?, 'Test house', 'HOUSE', 'SALE', ?, 'KES', 'Nairobi', 'LIVE', "
                        + "'HOUSE', now(), 1, 'ACTIVE', now(), now())",
                reference, tenantId, price);
        return reference;
    }

    // ── creating one at all ──────────────────────────────────────────────────

    @Test
    @DisplayName("platform staff can create a product, and it belongs to the one institution there is")
    void platformStaffCanCreateAProduct() {
        var created = products.create(new SaveProductRequest(
                "Owner Occupier", "For a first home.", AppConstant.PRODUCT_MORTGAGE,
                new BigDecimal("13.500"), AppConstant.RATE_FIXED,
                null, null, (short) 12, (short) 300,
                new BigDecimal("90.00"), new BigDecimal("10.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, null));

        assertNotNull(created.id(), "the catalogue could not be added to by the people who administer it");
        assertEquals(institutionId,
                productRepository.findById(com.hodi.security.hashid.HashIdUtil.decodeId(created.id()))
                        .orElseThrow().getInstitutionId(),
                "Co-op is the only institution, so a product files itself under it");
    }

    // ── the calculation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("the platform can open one check and read its working, but not who ran it")
    void thePlatformReadsTheWorkingWithoutThePerson() {
        String reference = aPublishedProduct();
        var saved = affordability.record(new AffordabilityRequest(
                new BigDecimal("200000"), null, null, new BigDecimal("2000000"),
                (short) 240, null, null, null, reference));

        var opened = affordability.find(saved.reference());

        assertEquals(saved.reference(), opened.reference());
        assertFalse(opened.steps().isEmpty(),
                "a list row says 'in the market for 9.2 million'; the page behind it has to say why");
        assertEquals(0, opened.maxLoanAmount().compareTo(saved.maxLoanAmount()),
                "the working shown to the platform is the one shown to the buyer, not a re-run");
        assertEquals(reference, opened.productReference());
        // The record type has no user field at all, so identity cannot leak by omission on a later edit.
        Set<String> namesThatAreNotAPerson = Set.of("productName", "institutionName");
        for (var component : FinanceDtos.AffordabilityResponse.class.getRecordComponents()) {
            String name = component.getName();
            String lower = name.toLowerCase();
            boolean aPerson = lower.contains("user") || lower.contains("buyer")
                    || (lower.contains("name") && !namesThatAreNotAPerson.contains(name));
            assertFalse(aPerson, "the platform reads a calculation, not a household: " + name);
        }
        assertThrows(com.hodi.common.exception.ResourceNotFoundException.class,
                () -> affordability.find("AFNOPE000001"));
    }

    @Test
    @DisplayName("the chosen mortgage's rate is the rate, not the configured default")
    void theProductsRateIsUsed() {
        String reference = aPublishedProduct();

        var withProduct = affordability.estimate(new AffordabilityRequest(
                new BigDecimal("200000"), null, null, new BigDecimal("2000000"),
                (short) 240, null, null, null, reference));

        assertEquals(0, withProduct.assumedRate().compareTo(new BigDecimal("12.000")),
                "every household used to be quoted the configured default whichever mortgage they chose");
        assertEquals(reference, withProduct.productReference());
        assertEquals("Hodi indicative rules", withProduct.providerLabel(),
                "a buyer reading MOCK beside their salary concludes the platform is a demonstration");
    }

    @Test
    @DisplayName("its ceiling on income is its own, and the working says so")
    void theProductsCeilingIsUsed() {
        String reference = aPublishedProduct();

        var result = affordability.estimate(new AffordabilityRequest(
                new BigDecimal("200000"), null, new BigDecimal("50000"), new BigDecimal("2000000"),
                (short) 240, null, null, null, reference));

        assertEquals(0, result.dtiCeilingPercent().compareTo(new BigDecimal("50.00")),
                "the product allows half of net income");
        // 200,000 − 50,000 = 150,000 net; half of that is the repayment it would allow.
        assertEquals(0, result.monthlyRepayment().compareTo(new BigDecimal("75000.00")),
                "net income times the product's ceiling, and nothing else");
    }

    @Test
    @DisplayName("lending at most 80% of a price caps the loan a deposit can reach")
    void loanToValueCapsTheLoan() {
        String reference = aPublishedProduct();

        // A large income with a small deposit: the annuity would lend far more than 80% of any price this
        // deposit could complete, so the cap is what answers.
        var result = affordability.estimate(new AffordabilityRequest(
                new BigDecimal("900000"), null, null, new BigDecimal("1000000"),
                (short) 240, null, null, null, reference));

        // 1,000,000 × 80 ÷ 20 = 4,000,000.
        assertEquals(0, result.maxLoanAmount().compareTo(new BigDecimal("4000000.00")),
                "a deposit of 1M is 20% of 5M, so the loan cannot exceed 4M however much income there is");
        assertEquals(0, result.maxPropertyPrice().compareTo(new BigDecimal("5000000.00")));
    }

    @Test
    @DisplayName("a term outside the product's band is moved into it, and the answer says which term it used")
    void theTermIsBoundedByTheProduct() {
        String reference = aPublishedProduct();

        var result = affordability.estimate(new AffordabilityRequest(
                new BigDecimal("200000"), null, null, new BigDecimal("2000000"),
                (short) 360, null, null, null, reference));

        assertEquals((short) 240, result.termMonths(),
                "asking for thirty years on a twenty-year product should not quote thirty");
        assertTrue(result.steps().stream().anyMatch(s -> "MONTHS".equals(s.unit())
                        && s.note() != null && s.note().contains("360")),
                "and the move is said out loud rather than discovered at the branch");
    }

    @Test
    @DisplayName("below the product's minimum income it says the figure, not just no")
    void theMinimumIncomeIsNamed() {
        String reference = aPublishedProduct();

        var result = affordability.estimate(new AffordabilityRequest(
                new BigDecimal("30000"), null, null, new BigDecimal("500000"),
                (short) 240, null, null, null, reference));

        assertEquals(AppConstant.AFFORDABILITY_NOT_ELIGIBLE, result.decision());
        assertTrue(result.decisionReason().contains("50000") || result.decisionReason().contains("50,000"),
                "a refusal without the figure is not something anybody can act on: "
                        + result.decisionReason());
    }

    @Test
    @DisplayName("the working is a derivation, and it reconciles to the headline figures")
    void theWorkingReconciles() {
        String reference = aPublishedProduct();

        var result = affordability.estimate(new AffordabilityRequest(
                new BigDecimal("200000"), new BigDecimal("20000"), new BigDecimal("20000"),
                new BigDecimal("3000000"), (short) 240, null, null, null, reference));

        List<AffordabilityProvider.Step> steps = result.steps();
        assertTrue(steps.size() >= 5, "a debug dump of a map is not a derivation");

        BigDecimal netFromSteps = steps.stream()
                .filter(s -> "Less what you already owe each month".equals(s.label()))
                .findFirst().orElseThrow().value();
        assertEquals(0, netFromSteps.compareTo(new BigDecimal("200000")),
                "200,000 + 20,000 − 20,000, shown as the arithmetic a person can check");

        BigDecimal maxPriceFromSteps = steps.stream()
                .filter(s -> "The most you could pay for a home".equals(s.label()))
                .findFirst().orElseThrow().value();
        assertEquals(0, maxPriceFromSteps.compareTo(result.maxPropertyPrice()),
                "the steps and the headline must be the same calculation, not two of them");
    }

    @Test
    @DisplayName("every term the product allows is costed both ways, and the chosen one matches the headline")
    void theTermTableReconciles() {
        String reference = aPublishedProduct();

        var result = affordability.estimate(new AffordabilityRequest(
                new BigDecimal("200000"), null, null, new BigDecimal("2000000"),
                (short) 240, null, null, null, reference));

        // The product runs 60 to 240 months, so 300 must not be offered.
        assertTrue(result.terms().stream().noneMatch(t -> t.months() == 300),
                "offering a term the mortgage does not run to is offering a figure the bank would refuse");

        var chosen = result.terms().stream().filter(FinanceDtos.TermOption::chosen).findFirst().orElseThrow();
        assertEquals((short) 240, chosen.months());
        assertEquals(0, chosen.maxLoan().compareTo(result.maxLoanAmount()),
                "on the chosen term the table and the headline are the same calculation");
        assertEquals(0, chosen.monthlyRepayment().compareTo(result.monthlyRepayment()),
                "and so is the repayment");

        // Shorter is dearer on the same loan, and buys less at the same repayment. Both directions, so a
        // reader can see that a shorter term costing more is arithmetic rather than a mistake.
        var fiveYears = result.terms().stream().filter(t -> t.months() == 60).findFirst().orElseThrow();
        assertTrue(fiveYears.monthlyRepayment().compareTo(chosen.monthlyRepayment()) > 0,
                "the same loan over five years costs more each month than over twenty");
        assertTrue(fiveYears.maxLoan().compareTo(chosen.maxLoan()) < 0,
                "and the same repayment borrows less over five years than over twenty");
    }

    @Test
    @DisplayName("against a listing, the loan is that listing's price less the deposit")
    void aListingIsCostedOnItsOwnPrice() {
        String reference = aPublishedProduct();
        String listing = aLiveListingAt(new BigDecimal("6000000"));

        var result = affordability.estimate(new AffordabilityRequest(
                new BigDecimal("400000"), null, null, new BigDecimal("2000000"),
                (short) 240, null, null, listing, reference));

        assertEquals(0, result.loanRequired().compareTo(new BigDecimal("4000000")),
                "the question was about this home: 6,000,000 less the 2,000,000 they have");
        assertTrue(result.maxLoanAmount().compareTo(result.loanRequired()) > 0,
                "they could borrow more than this home needs — which is a different figure, kept apart");
        assertEquals(0, result.monthlyRepayment().compareTo(
                        com.hodi.modules.finance.Amortisation.monthlyRepayment(
                                new BigDecimal("4000000"), new BigDecimal("12.000"), 240)),
                "and the repayment is on that loan, not on the maximum");
    }

    @Test
    @DisplayName("with no mortgage chosen the answer is unchanged, and labelled as the platform's own")
    void noProductStillAnswers() {
        var result = affordability.estimate(new AffordabilityRequest(
                new BigDecimal("200000"), null, null, new BigDecimal("2000000"),
                (short) 240, null, null, null, null));

        assertEquals(AppConstant.AFFORDABILITY_ELIGIBLE, result.decision(),
                "somebody who has not chosen a bank yet still deserves a number");
        assertEquals(null, result.productReference());
        assertTrue(result.maxPropertyPrice().signum() > 0);
    }

    @Test
    @DisplayName("a mortgage that is not on offer is refused rather than quietly ignored")
    void anUnpublishedProductIsRefused() {
        var draft = products.create(new SaveProductRequest(
                "Not published", null, AppConstant.PRODUCT_MORTGAGE,
                new BigDecimal("9.000"), AppConstant.RATE_FIXED,
                null, null, (short) 12, (short) 240,
                new BigDecimal("90.00"), new BigDecimal("10.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, null));
        String reference = productRepository.findById(
                com.hodi.security.hashid.HashIdUtil.decodeId(draft.id())).orElseThrow().getReference();

        assertThrows(HodiException.class, () -> affordability.estimate(new AffordabilityRequest(
                        new BigDecimal("200000"), null, null, null,
                        (short) 240, null, null, null, reference)),
                "falling back to the default rate would answer about a mortgage nobody chose");
    }
}
