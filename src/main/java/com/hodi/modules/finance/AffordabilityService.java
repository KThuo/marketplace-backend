package com.hodi.modules.finance;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.RefGenerator;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.finance.FinanceDtos.AffordabilityListRequest;
import com.hodi.modules.finance.FinanceDtos.AffordabilityRequest;
import com.hodi.modules.finance.FinanceDtos.AffordabilityResponse;
import com.hodi.modules.finance.FinanceDtos.AffordabilitySummary;
import com.hodi.modules.finance.FinanceDtos.FinanceOption;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a household can carry (M3, BRD FR031–FR034).
 *
 * <h2>Two doors, one calculation</h2>
 *
 * <p>A stranger can use the calculator without an account — {@link #estimate} computes and returns, and keeps
 * nothing. A signed-in person gets {@link #record} instead, which is the same calculation with the row saved.
 * Splitting them at the surface rather than branching inside means the public path has no user id to reach
 * for and cannot accidentally write one.
 *
 * <h2>Who may read a check</h2>
 *
 * <p>The person who ran it, in full, through their own endpoints. Platform staff holding
 * {@code AFFORDABILITY_VIEW}, as {@link AffordabilitySummary} — outcomes and derived figures, never income.
 * The bank learns a buyer's finances when the buyer applies to them, which is M4, and not by browsing a list.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AffordabilityService {

    private static final String REFERENCE_PREFIX = "AF";

    private final AffordabilityCheckRepository repository;
    private final PropertyRepository properties;
    private final MortgageProductRepository products;
    private final FinanceMatchService matcher;
    private final MockAffordabilityProvider mock;
    private final List<AffordabilityProvider> providers;
    private final ConfigurationService configs;
    private final com.hodi.modules.valuations.LendingValueService lendingValues;

    // ── the calculation ───────────────────────────────────────────────────────

    /** Computed and returned. Nothing stored — this is the door a stranger comes through. */
    @Transactional(readOnly = true)
    public AffordabilityResponse estimate(AffordabilityRequest request) {
        return assess(request);
    }

    /** The same calculation, kept. */
    @Transactional
    public AffordabilityResponse record(AffordabilityRequest request) {
        Long userId = AuthContext.requireUserId();
        AffordabilityResponse computed = assess(request);

        Property property = resolveProperty(request.propertyReference());
        AffordabilityCheck check = AffordabilityCheck.builder()
                .userId(userId)
                .reference(nextReference())
                .propertyId(property == null ? null : property.getId())
                .propertyReference(property == null ? null : property.getReference())
                .propertyPrice(property == null ? null : property.getPrice())
                .currency(computed.currency())
                .monthlyTakeHome(nz(request.monthlyTakeHome()))
                .otherMonthlyIncome(nz(request.otherMonthlyIncome()))
                .monthlyObligations(nz(request.monthlyObligations()))
                .depositAmount(nz(request.depositAmount()))
                .termMonths(computed.termMonths())
                .employmentType(blankToNull(request.employmentType()))
                .dependants(request.dependants() == null ? null : request.dependants().shortValue())
                .decision(computed.decision())
                .decisionReason(computed.decisionReason())
                .maxLoanAmount(computed.maxLoanAmount())
                .maxPropertyPrice(computed.maxPropertyPrice())
                .monthlyRepayment(computed.monthlyRepayment())
                .dtiPercent(computed.dtiPercent())
                .dtiCeilingPercent(computed.dtiCeilingPercent())
                .assumedRate(computed.assumedRate())
                .provider(computed.provider())
                .providerPayload(computed.working())
                .loanRequired(computed.loanRequired())
                .productReference(computed.productReference())
                .productName(computed.productName())
                .providerSteps(computed.steps())
                .createdBy(AuthContext.username())
                .updatedBy(AuthContext.username())
                .build();

        AffordabilityCheck saved = repository.save(check);
        // Deliberately no audit row. The check *is* the record, it is the person's own, and copying their
        // income into the audit trail — which every platform auditor can read — would spread the most
        // sensitive figure on the platform into the one table designed to be widely readable.
        return withReference(computed, saved.getReference(), saved.getCreatedAt());
    }

    /**
     * The calculation itself.
     *
     * @param userId null for an anonymous estimate; only used to decide nothing here, which is the point —
     *               the same inputs give the same answer signed in or not
     */
    private AffordabilityResponse assess(AffordabilityRequest request) {
        if (request.monthlyTakeHome() == null || request.monthlyTakeHome().signum() < 0) {
            throw new HodiException("Tell us what you earn each month.", HttpStatus.BAD_REQUEST);
        }

        Property property = resolveProperty(request.propertyReference());
        short term = resolveTerm(request.termMonths());
        MortgageProduct product = resolveProduct(request.productReference());
        BigDecimal rate = product != null ? product.getInterestRate() : mock.defaultRate();
        String currency = property != null ? property.getCurrency() : "KES";

        // A bank lends against the lesser of the price and a completed valuation's figure, not the price.
        com.hodi.modules.valuations.LendingValueService.LendingValue lending = property == null
                ? new com.hodi.modules.valuations.LendingValueService.LendingValue(null,
                        com.hodi.modules.valuations.LendingValueService.BASIS_PRICE, null)
                : lendingValues.lendingValueFor(property.getId(), property.getPrice());

        AffordabilityProvider provider = resolveProvider();
        AffordabilityProvider.Decision decision = provider.assess(new AffordabilityProvider.Request(
                request.monthlyTakeHome(),
                nz(request.otherMonthlyIncome()),
                nz(request.monthlyObligations()),
                nz(request.depositAmount()),
                term,
                blankToNull(request.employmentType()),
                request.dependants(),
                lending.value(),
                rate,
                currency,
                termsOf(product)));
        // Said in the working, first, because every figure below it turns on which number was used.
        List<AffordabilityProvider.Step> steps = decision.steps();
        if (property != null && lending.fromValuation()) {
            steps = new java.util.ArrayList<>(steps);
            steps.add(0, AffordabilityProvider.Step.money("Lending value",
                    "min(asking " + property.getPrice().toPlainString() + ", valuation "
                            + lending.value().toPlainString() + ")",
                    lending.value(),
                    "The bank lends against " + lending.said() + ", which is below the asking price."));
        }
        // The product may have moved the term into its own band, and the answer is about the term used.
        term = decision.termMonths();

        // What the money would actually buy, at the banks in play. Against a listing that is the seller's
        // partnered banks; with no listing named it is everything on offer, because the question has
        // stopped being about one seller.
        BigDecimal carryable = decision.monthlyRepayment();
        List<FinanceOption> options = property != null
                ? matcher.forListing(property.getReference(), term, netIncomeFor(request)).options()
                : marketOptions(decision.maxPropertyPrice(), term, netIncomeFor(request),
                        nz(request.depositAmount()));

        return new AffordabilityResponse(
                null,
                decision.outcome(),
                decision.reason(),
                currency,
                request.monthlyTakeHome(),
                nz(request.otherMonthlyIncome()),
                nz(request.monthlyObligations()),
                nz(request.depositAmount()),
                term,
                blankToNull(request.employmentType()),
                request.dependants(),
                decision.maxLoanAmount(),
                decision.maxPropertyPrice(),
                decision.loanRequired(),
                carryable,
                decision.dtiPercent(),
                decision.dtiCeilingPercent(),
                decision.assumedRate(),
                provider.name(),
                provider.label(),
                product == null ? null : product.getReference(),
                product == null ? null : product.getName(),
                product == null ? null : product.getInstitutionName(),
                steps,
                termOptions(product, decision, rate, term, nz(request.depositAmount()),
                        netIncomeFor(request)),
                property == null ? null : property.getReference(),
                property == null ? null : property.getTitle(),
                property == null ? null : property.getPrice(),
                property == null ? null : lending.value(),
                property == null ? null : lending.basis(),
                lending.valuationReference(),
                decision.payload(),
                options,
                FinanceMatchService.DISCLAIMER,
                null);
    }

    /**
     * Every published product, costed against what this household could afford.
     *
     * <p>No partnership filter: with no listing named there is no seller whose arrangements could narrow it,
     * and the honest answer to "what could I borrow" is the whole market rather than an arbitrary slice.
     */
    private List<FinanceOption> marketOptions(BigDecimal price, short term, BigDecimal netIncome,
                                              BigDecimal buyersDeposit) {
        if (price == null || price.signum() <= 0) return List.of();
        List<MortgageProduct> onOffer = products.findOnOffer(
                org.springframework.data.domain.PageRequest.of(0, 10));
        List<FinanceOption> options = new ArrayList<>(onOffer.size());
        for (MortgageProduct product : onOffer) {
            FinanceOption option = matcher.cost(product, price, term, netIncome, buyersDeposit);
            if (option != null) options.add(option);
        }
        return options;
    }

    /**
     * Which assessor answers.
     *
     * <p>An unknown or absent name falls back to the mock <em>and says so</em>: a misconfigured provider that
     * silently answered would be a platform quoting numbers from a source nobody chose.
     */
    private AffordabilityProvider resolveProvider() {
        String wanted = configs.getString(ConfigKey.AFFORDABILITY_PROVIDER);
        String name = wanted == null ? "" : wanted.trim().toUpperCase();
        for (AffordabilityProvider candidate : providers) {
            if (candidate.name().equals(name)) return candidate;
        }
        if (!AppConstant.PROVIDER_MOCK.equals(name)) {
            log.warn("affordability.provider is '{}', which no provider answers to — using {}",
                    wanted, mock.name());
        }
        return mock;
    }

    // ── the person's own ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<AffordabilitySummary> mine(AffordabilityListRequest request) {
        var page = repository.findMine(AuthContext.requireUserId(),
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(page, this::toSummary);
    }

    @Transactional(readOnly = true)
    public AffordabilityResponse mineByReference(String reference) {
        AffordabilityCheck check = repository
                .findMineByReference(reference == null ? "" : reference.trim(), AuthContext.requireUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Affordability check", reference));
        return toFullResponse(check);
    }

    @Transactional(readOnly = true)
    public long myCount() {
        return repository.countByUserId(AuthContext.requireUserId());
    }

    // ── the platform's list ───────────────────────────────────────────────────

    /** Summaries only. See {@link AffordabilitySummary} — there is no income on it. */
    @Transactional(readOnly = true)
    public PagedResponse<AffordabilitySummary> list(AffordabilityListRequest request) {
        Specification<AffordabilityCheck> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("decision", blankToNull(request.getDecision())),
                SearchSpecs.eq("provider", blankToNull(request.getProvider())));
        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(page, this::toSummary);
    }

    /**
     * One check, in full, for the platform — the working without the person.
     *
     * <p>The list shows outcomes only, and that rule stands. This is the page behind one row of it: how the
     * figure was arrived at, line by line, in the figures the household typed — because "in the market for
     * 9.2 million" is a number nobody can act on without seeing what produced it, and a platform quoting
     * numbers it cannot explain is the thing this module exists not to be. What it does not carry is who
     * ran it: the response has no user id, no name and no contact, and the repository query does not join
     * to one. Platform staff read a calculation, not a household.
     */
    @Transactional(readOnly = true)
    public AffordabilityResponse find(String reference) {
        AffordabilityCheck check = repository.findLiveByReference(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Affordability check", reference));
        return toFullResponse(check);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private Property resolveProperty(String reference) {
        if (reference == null || reference.isBlank()) return null;
        // Live only. A check quoting a withdrawn listing's price would be arithmetic about something the
        // buyer cannot buy.
        return properties.findLiveByReference(reference.trim()).orElse(null);
    }

    private short resolveTerm(Short requested) {
        if (requested != null && requested > 0) return (short) Math.min(requested, 480);
        return (short) mock.defaultTermMonths();
    }

    /** Income left after existing commitments — what a repayment actually comes out of. */
    private static BigDecimal netIncomeFor(AffordabilityRequest request) {
        BigDecimal gross = nz(request.monthlyTakeHome()).add(nz(request.otherMonthlyIncome()));
        BigDecimal net = gross.subtract(nz(request.monthlyObligations()));
        return net.signum() > 0 ? net : BigDecimal.ZERO;
    }

    /**
     * The mortgage named on the request, if one was.
     *
     * <p>Only something on offer: a draft or withdrawn product is not a rate anybody can act on, and
     * costing a household against one would be quoting them a price the bank has not published. A reference
     * that matches nothing is refused rather than ignored — silently falling back to the default rate would
     * hand somebody an answer computed against a mortgage they did not choose.
     */
    private MortgageProduct resolveProduct(String reference) {
        String wanted = blankToNull(reference);
        if (wanted == null) return null;
        return products.findOnOfferByReference(wanted)
                .orElseThrow(() -> new HodiException(
                        "That mortgage is not on offer. Choose one from the list.",
                        HttpStatus.BAD_REQUEST));
    }

    /**
     * What every term on offer would mean, so the length of the loan stops being a number somebody has to
     * take on trust.
     *
     * <p>Two columns because there are two questions, and answering only one of them is what makes a
     * repayment look wrong: <em>what would this loan cost me over five years rather than twenty</em>, and
     * <em>what could I borrow if I took five</em>. The row for the chosen term agrees with the headline by
     * construction, which is the check a reader can make without doing any arithmetic themselves.
     *
     * <p>Bounded by the product where there is one. Offering a household a twenty-five year column on a
     * mortgage that stops at twenty is offering them a number the bank would not honour.
     */
    private List<FinanceDtos.TermOption> termOptions(MortgageProduct product,
                                                     AffordabilityProvider.Decision decision,
                                                     BigDecimal rate, short chosenTerm,
                                                     BigDecimal deposit, BigDecimal netIncome) {
        BigDecimal loan = decision.maxLoanAmount();
        /*
         * The ceiling, not the repayment the answer reports.
         *
         * They are the same figure only when income is what limited the loan. Where the deposit capped it
         * the reported repayment is the smaller one on the capped loan, and reading the table backwards
         * from that would quietly shrink every row — including the one that has to agree with the headline.
         */
        BigDecimal ceilingPayment = Amortisation.percentOf(netIncome, decision.dtiCeilingPercent());
        if (loan == null || loan.signum() <= 0) return List.of();

        short min = product == null || product.getMinTermMonths() == null ? 12 : product.getMinTermMonths();
        short max = product == null || product.getMaxTermMonths() == null
                ? 300 : product.getMaxTermMonths();

        /*
         * The deposit caps every row, exactly as it caps the headline.
         *
         * A product lending at most 80% of a price cannot lend more than the deposit supports however long
         * the term runs, so a table that showed the uncapped annuity would offer a longer term as a way
         * round a rule it is not a way round — and would disagree with the figure printed above it, which
         * is the precise complaint this table exists to answer.
         */
        BigDecimal ltvCap = null;
        if (product != null && product.getMaxLtvPercent() != null
                && product.getMaxLtvPercent().signum() > 0
                && product.getMaxLtvPercent().compareTo(new BigDecimal("100")) < 0) {
            BigDecimal ltv = product.getMaxLtvPercent();
            ltvCap = nz(deposit).multiply(ltv)
                    .divide(new BigDecimal("100").subtract(ltv), 2, java.math.RoundingMode.HALF_UP);
        }

        List<FinanceDtos.TermOption> rows = new ArrayList<>();
        for (short months : new short[] {60, 120, 180, 240, 300}) {
            if (months < min || months > max) continue;
            rows.add(row(loan, ceilingPayment, rate, months, chosenTerm, ltvCap));
        }
        // The chosen term may be one the standard row set does not carry — a product whose band is 12 to 36
        // months, say. The row the answer was computed on always appears.
        if (rows.stream().noneMatch(FinanceDtos.TermOption::chosen)) {
            rows.add(row(loan, ceilingPayment, rate, chosenTerm, chosenTerm, ltvCap));
            rows.sort(java.util.Comparator.comparingInt(FinanceDtos.TermOption::months));
        }
        return List.copyOf(rows);
    }

    /** One row: the loan they can take costed over this term, and what their ceiling buys over it. */
    private static FinanceDtos.TermOption row(BigDecimal loan, BigDecimal ceilingPayment, BigDecimal rate,
                                              short months, short chosenTerm, BigDecimal ltvCap) {
        BigDecimal borrowable = Amortisation.loanFor(ceilingPayment, rate, months);
        if (ltvCap != null && ltvCap.compareTo(borrowable) < 0) borrowable = ltvCap;
        return new FinanceDtos.TermOption(months,
                Amortisation.monthlyRepayment(loan, rate, months),
                borrowable,
                months == chosenTerm);
    }

    /** The product's own columns, in the shape the assessor's contract asks for. */
    private static AffordabilityProvider.ProductTerms termsOf(MortgageProduct p) {
        if (p == null) return null;
        return new AffordabilityProvider.ProductTerms(
                p.getReference(), p.getName(), p.getInstitutionName(),
                p.getInterestRate(), p.getRateType(),
                p.getMinTermMonths(), p.getMaxTermMonths(),
                p.getMaxLtvPercent(), p.getMinDepositPercent(),
                p.getMaxDtiPercent(), p.getMinMonthlyIncome(),
                p.getProcessingFeePercent(), p.getInsurancePercent());
    }

    private AffordabilityResponse withReference(AffordabilityResponse computed, String reference,
                                                java.time.OffsetDateTime createdAt) {
        return new AffordabilityResponse(reference, computed.decision(), computed.decisionReason(),
                computed.currency(), computed.monthlyTakeHome(), computed.otherMonthlyIncome(),
                computed.monthlyObligations(), computed.depositAmount(), computed.termMonths(),
                computed.employmentType(), computed.dependants(), computed.maxLoanAmount(),
                computed.maxPropertyPrice(), computed.loanRequired(), computed.monthlyRepayment(),
                computed.dtiPercent(),
                computed.dtiCeilingPercent(), computed.assumedRate(), computed.provider(),
                computed.providerLabel(), computed.productReference(), computed.productName(),
                computed.institutionName(), computed.steps(), computed.terms(),
                computed.propertyReference(), computed.propertyTitle(), computed.propertyPrice(),
                computed.lendingValue(), computed.lendingBasis(), computed.valuationReference(),
                computed.working(), computed.options(), computed.disclaimer(), createdAt);
    }

    /** A stored check, back to the person who ran it — including the options as they stand today. */
    private AffordabilityResponse toFullResponse(AffordabilityCheck c) {
        List<FinanceOption> options = c.getPropertyReference() != null
                ? safeListingOptions(c)
                : marketOptions(c.getMaxPropertyPrice(), c.getTermMonths(), netIncomeOf(c),
                        c.getDepositAmount());

        return new AffordabilityResponse(
                c.getReference(), c.getDecision(), c.getDecisionReason(), c.getCurrency(),
                c.getMonthlyTakeHome(), c.getOtherMonthlyIncome(), c.getMonthlyObligations(),
                c.getDepositAmount(), c.getTermMonths(), c.getEmploymentType(),
                c.getDependants() == null ? null : c.getDependants().intValue(),
                c.getMaxLoanAmount(), c.getMaxPropertyPrice(), c.getLoanRequired(),
                c.getMonthlyRepayment(),
                c.getDtiPercent(), c.getDtiCeilingPercent(), c.getAssumedRate(), c.getProvider(),
                labelFor(c.getProvider()), c.getProductReference(), c.getProductName(), null,
                // The working as it was shown on the day, kept on the row rather than re-derived: today's
                // product may carry a different rate, and re-running it would contradict the figures here.
                c.getProviderSteps() == null ? java.util.List.of() : c.getProviderSteps(),
                storedTermOptions(c),
                c.getPropertyReference(), null, c.getPropertyPrice(),
                // The stored working's first line carries the lending value where a valuation set it.
                storedLendingValue(c), storedLendingBasis(c), null,
                c.getProviderPayload(), options, FinanceMatchService.DISCLAIMER, c.getCreatedAt());
    }

    /** The lending value as the working recorded it on the day, or the price. */
    private static BigDecimal storedLendingValue(AffordabilityCheck c) {
        if (c.getProviderSteps() != null) {
            for (AffordabilityProvider.Step step : c.getProviderSteps()) {
                if ("Lending value".equals(step.label())) return step.value();
            }
        }
        return c.getPropertyPrice();
    }

    private static String storedLendingBasis(AffordabilityCheck c) {
        if (c.getProviderSteps() != null) {
            for (AffordabilityProvider.Step step : c.getProviderSteps()) {
                if ("Lending value".equals(step.label())) {
                    return step.note() != null && step.note().contains("market value")
                            ? com.hodi.modules.valuations.LendingValueService.BASIS_MARKET
                            : com.hodi.modules.valuations.LendingValueService.BASIS_FORCED_SALE;
                }
            }
        }
        return c.getPropertyPrice() == null ? null : com.hodi.modules.valuations.LendingValueService.BASIS_PRICE;
    }

    /**
     * The listing's options, or none.
     *
     * <p>A check outlives the listing it was run against. Asking for a panel on a withdrawn listing throws,
     * and a stored check should still open — so the absence is caught here rather than allowed to turn a
     * historical record into a 404.
     */
    private List<FinanceOption> safeListingOptions(AffordabilityCheck c) {
        try {
            return matcher.forListing(c.getPropertyReference(), c.getTermMonths(), netIncomeOf(c)).options();
        } catch (ResourceNotFoundException e) {
            return List.of();
        }
    }

    private static BigDecimal netIncomeOf(AffordabilityCheck c) {
        BigDecimal net = nz(c.getMonthlyTakeHome()).add(nz(c.getOtherMonthlyIncome()))
                .subtract(nz(c.getMonthlyObligations()));
        return net.signum() > 0 ? net : BigDecimal.ZERO;
    }

    /**
     * The term table for a stored check, rebuilt from the figures on the row.
     *
     * <p>Arithmetic on numbers this row already holds — the loan, the ceiling payment, the rate as it was —
     * so it says the same thing today as it did then. Nothing is read from the product, which is what makes
     * it safe to compute rather than store.
     */
    private List<FinanceDtos.TermOption> storedTermOptions(AffordabilityCheck c) {
        if (c.getMaxLoanAmount() == null || c.getMaxLoanAmount().signum() <= 0) return List.of();
        List<FinanceDtos.TermOption> rows = new ArrayList<>();
        for (short months : new short[] {60, 120, 180, 240, 300}) {
            rows.add(new FinanceDtos.TermOption(months,
                    Amortisation.monthlyRepayment(c.getMaxLoanAmount(), c.getAssumedRate(), months),
                    Amortisation.loanFor(c.getMonthlyRepayment(), c.getAssumedRate(), months),
                    c.getTermMonths() != null && c.getTermMonths() == months));
        }
        return List.copyOf(rows);
    }

    private AffordabilitySummary toSummary(AffordabilityCheck c) {
        return new AffordabilitySummary(
                c.getReference(), c.getDecision(), c.getCurrency(), c.getMaxLoanAmount(),
                c.getMaxPropertyPrice(), c.getMonthlyRepayment(), c.getDtiPercent(),
                c.getDtiCeilingPercent(), c.getAssumedRate(), c.getTermMonths(), c.getProvider(),
                labelFor(c.getProvider()), c.getProductReference(), c.getProductName(),
                c.getPropertyReference(), c.getPropertyPrice(), c.getCreatedAt());
    }

    /**
     * The display name for a stored provider code.
     *
     * <p>Rows keep the code — it is what the configuration names and what a migration would have to change
     * — and every screen shows this instead. The list of checks used to print "MOCK" in a column beside
     * people's salaries.
     */
    private String labelFor(String providerCode) {
        for (AffordabilityProvider candidate : providers) {
            if (candidate.name().equalsIgnoreCase(providerCode)) return candidate.label();
        }
        return providerCode;
    }

    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = RefGenerator.getInstance().generate(REFERENCE_PREFIX);
            if (!repository.existsByReference(reference)) return reference;
        }
        throw new HodiException("Could not allocate a reference. Try again.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
