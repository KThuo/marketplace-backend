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
                .grossMonthlyIncome(nz(request.grossMonthlyIncome()))
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
                .productReference(computed.productReference())
                .productName(computed.productName())
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
        if (request.grossMonthlyIncome() == null || request.grossMonthlyIncome().signum() < 0) {
            throw new HodiException("Tell us what you earn each month.", HttpStatus.BAD_REQUEST);
        }

        Property property = resolveProperty(request.propertyReference());
        short term = resolveTerm(request.termMonths());
        MortgageProduct product = resolveProduct(request.productReference());
        BigDecimal rate = product != null ? product.getInterestRate() : mock.defaultRate();
        String currency = property != null ? property.getCurrency() : "KES";

        AffordabilityProvider provider = resolveProvider();
        AffordabilityProvider.Decision decision = provider.assess(new AffordabilityProvider.Request(
                request.grossMonthlyIncome(),
                nz(request.otherMonthlyIncome()),
                nz(request.monthlyObligations()),
                nz(request.depositAmount()),
                term,
                blankToNull(request.employmentType()),
                request.dependants(),
                property == null ? null : property.getPrice(),
                rate,
                currency,
                termsOf(product)));
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
                request.grossMonthlyIncome(),
                nz(request.otherMonthlyIncome()),
                nz(request.monthlyObligations()),
                nz(request.depositAmount()),
                term,
                blankToNull(request.employmentType()),
                request.dependants(),
                decision.maxLoanAmount(),
                decision.maxPropertyPrice(),
                carryable,
                decision.dtiPercent(),
                decision.dtiCeilingPercent(),
                decision.assumedRate(),
                provider.name(),
                provider.label(),
                product == null ? null : product.getReference(),
                product == null ? null : product.getName(),
                product == null ? null : product.getInstitutionName(),
                decision.steps(),
                property == null ? null : property.getReference(),
                property == null ? null : property.getTitle(),
                property == null ? null : property.getPrice(),
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
        BigDecimal gross = nz(request.grossMonthlyIncome()).add(nz(request.otherMonthlyIncome()));
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
                computed.currency(), computed.grossMonthlyIncome(), computed.otherMonthlyIncome(),
                computed.monthlyObligations(), computed.depositAmount(), computed.termMonths(),
                computed.employmentType(), computed.dependants(), computed.maxLoanAmount(),
                computed.maxPropertyPrice(), computed.monthlyRepayment(), computed.dtiPercent(),
                computed.dtiCeilingPercent(), computed.assumedRate(), computed.provider(),
                computed.providerLabel(), computed.productReference(), computed.productName(),
                computed.institutionName(), computed.steps(),
                computed.propertyReference(), computed.propertyTitle(), computed.propertyPrice(),
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
                c.getGrossMonthlyIncome(), c.getOtherMonthlyIncome(), c.getMonthlyObligations(),
                c.getDepositAmount(), c.getTermMonths(), c.getEmploymentType(),
                c.getDependants() == null ? null : c.getDependants().intValue(),
                c.getMaxLoanAmount(), c.getMaxPropertyPrice(), c.getMonthlyRepayment(),
                c.getDtiPercent(), c.getDtiCeilingPercent(), c.getAssumedRate(), c.getProvider(),
                labelFor(c.getProvider()), c.getProductReference(), c.getProductName(), null,
                // A stored check keeps the assessor's payload, not the rendered derivation: the steps are
                // built from figures that were true on the day and are not re-derived from today's product.
                java.util.List.of(),
                c.getPropertyReference(), null, c.getPropertyPrice(),
                c.getProviderPayload(), options, FinanceMatchService.DISCLAIMER, c.getCreatedAt());
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
        BigDecimal net = nz(c.getGrossMonthlyIncome()).add(nz(c.getOtherMonthlyIncome()))
                .subtract(nz(c.getMonthlyObligations()));
        return net.signum() > 0 ? net : BigDecimal.ZERO;
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
