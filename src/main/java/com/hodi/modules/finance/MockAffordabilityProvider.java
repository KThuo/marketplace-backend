package com.hodi.modules.finance;

import com.hodi.common.AppConstant;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The platform's own affordability rules, written down.
 *
 * <h2>Not a stub</h2>
 *
 * <p>It returns real arithmetic the bank would recognise, not a fixed number and not noise. The point of the
 * interface is that the OCP microservice replaces this as the *authority*; the point of this class is that
 * until then a buyer still gets an answer, and it is an answer somebody can check by hand.
 *
 * <h2>The rules, in order</h2>
 *
 * <ol>
 *   <li><strong>Net income</strong> = take-home + other income − existing monthly obligations. Obligations come
 *       off the top rather than out of the ceiling: a household already paying a car loan has that much less
 *       to give, whatever percentage anyone is willing to lend against.</li>
 *   <li><strong>Affordable repayment</strong> = net income × the configured DTI ceiling. Kenyan banks
 *       commonly sit between 35% and 50%; the row defaults to 40 and an operator moves it without a deploy.</li>
 *   <li><strong>Maximum loan</strong> = the annuity read backwards at the assumed rate over the chosen term.</li>
 *   <li><strong>Maximum price</strong> = that loan plus whatever deposit they have.</li>
 * </ol>
 *
 * <h2>Three outcomes, not two</h2>
 *
 * <p>Where a specific property is in play, the repayment it would need is compared with what the household
 * can carry. Within the ceiling is ELIGIBLE; past it but inside the marginal band is MARGINAL; beyond that is
 * NOT_ELIGIBLE. A hard line at the ceiling turns one shilling into a refusal, which is not how the bank reads
 * a file and not how a buyer should be told.
 *
 * <p><strong>Nothing here is a credit decision.</strong> No bureau, no scoring, no view of the applicant's
 * actual liabilities beyond the figure they typed. Every screen carrying these numbers says indicative, and
 * that is not a disclaimer — it is what they are.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MockAffordabilityProvider implements AffordabilityProvider {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ConfigurationService configs;

    @Override
    public String name() {
        return AppConstant.PROVIDER_MOCK;
    }

    /**
     * What a buyer sees. Never the code.
     *
     * <p>The code is {@code MOCK}, it is stored on every check and named in a configuration row, and it
     * was reaching the screen — a household reading "MOCK" beside their own salary concludes the platform
     * is a demonstration. The arithmetic here is not a demonstration; it is the platform's own indicative
     * rules, which is what this says.
     */
    @Override
    public String label() {
        return "Hodi indicative rules";
    }

    @Override
    public Decision assess(Request request) {
        ProductTerms product = request.product();
        List<Step> steps = new ArrayList<>();

        BigDecimal ceiling = ceilingFor(product);
        BigDecimal marginalBand = new BigDecimal(configs.getInt(ConfigKey.AFFORDABILITY_MARGINAL_BAND));
        BigDecimal rate = product != null && product.annualRate() != null
                ? product.annualRate() : request.annualRate();
        // Computed here, reported further down: the term is an assumption about the loan, and a reader
        // meets it at the line where it starts to matter rather than before they know what they earn.
        List<Step> termStep = new ArrayList<>();
        short term = termFor(product, request.termMonths(), termStep);

        BigDecimal takeHome = nz(request.monthlyTakeHome());
        BigDecimal other = nz(request.otherMonthlyIncome());
        BigDecimal obligations = nz(request.monthlyObligations());
        BigDecimal deposit = nz(request.depositAmount());

        BigDecimal grossTotal = takeHome.add(other);
        BigDecimal netIncome = grossTotal.subtract(obligations).max(BigDecimal.ZERO);

        steps.add(Step.money("What you take home each month",
                money(takeHome) + " + " + money(other), grossTotal,
                other.signum() > 0
                        ? "Your pay after tax and deductions, and the other income you told us about."
                        : "Your pay after tax and deductions — what actually reaches your account."));
        steps.add(Step.money("Less what you already owe each month", "− " + money(obligations), netIncome,
                "Existing repayments come off the top rather than out of the ceiling: a household already "
                        + "paying a car loan has that much less to give."));

        /*
         * The product's own floor, before anything else is worked out.
         *
         * <p>Measured against take-home, because take-home is what this application asks for and therefore
         * the only income it actually knows. It used to ask for gross and then spend it as though tax had
         * not been taken — which overstated every figure below by whatever PAYE, the housing levy and SHIF
         * had already removed. Asking for the figure that reaches the account is the honest fix; modelling
         * Kenyan payroll from a salary is a second product, and a wrong model would be worse than not
         * having one.
         *
         * <p>So a product's minimum has to be stated in the same terms. The field's hint on the product
         * form says take-home, and a bank quoting a gross floor here would be comparing two different
         * numbers — which is the trap this comment exists to flag.
         */
        if (product != null && product.minMonthlyIncome() != null
                && grossTotal.compareTo(product.minMonthlyIncome()) < 0) {
            steps.add(Step.check("Minimum income for " + product.name(), false,
                    product.name() + " starts at " + money(product.minMonthlyIncome())
                            + " a month. You told us " + money(grossTotal) + "."));
            Map<String, Object> payload = payload(request, product, grossTotal, netIncome, ceiling, rate,
                    term, BigDecimal.ZERO, BigDecimal.ZERO, deposit,
                    AppConstant.AFFORDABILITY_NOT_ELIGIBLE);
            return new Decision(AppConstant.AFFORDABILITY_NOT_ELIGIBLE,
                    product.name() + " is for households earning at least "
                            + money(product.minMonthlyIncome()) + " a month.",
                    BigDecimal.ZERO, deposit, BigDecimal.ZERO, BigDecimal.ZERO, ceiling, rate,
                    null, payload, term, null, List.copyOf(steps));
        }
        if (product != null && product.minMonthlyIncome() != null) {
            steps.add(Step.check("Minimum income for " + product.name(), true,
                    "This product starts at " + money(product.minMonthlyIncome()) + " a month."));
        }

        BigDecimal affordableRepayment = Amortisation.percentOf(netIncome, ceiling);
        steps.add(Step.money("What you could put to a mortgage", money(netIncome) + " × "
                + plain(ceiling) + "%", affordableRepayment,
                product != null && product.maxDtiPercent() != null
                        ? product.name() + " lends up to " + plain(ceiling) + "% of net income."
                        : "Banks here commonly allow between 35% and 50% of net income."));

        steps.addAll(termStep);
        BigDecimal annuityLoan = Amortisation.loanFor(affordableRepayment, rate, term);
        steps.add(Step.money("What that repayment borrows", money(affordableRepayment) + " a month at "
                + rate(rate) + "% over " + term + " months", annuityLoan,
                "The repayment read backwards: the loan whose monthly instalment is exactly that figure."));

        /*
         * The deposit rule, as a cap on the loan rather than a warning after it.
         *
         * A product lending at most 90% of the price is the same statement as "your deposit is at least
         * 10%", and with a deposit of D the largest price that satisfies it is D ÷ 10% — so the loan cannot
         * exceed D × 90 ÷ 10 however much income there is. Applying it here rather than printing a warning
         * afterwards is the difference between a figure the bank would honour and one it would not.
         */
        BigDecimal maxLoan = annuityLoan;
        BigDecimal ltv = product == null ? null : product.maxLtvPercent();
        if (ltv != null && ltv.signum() > 0 && ltv.compareTo(HUNDRED) < 0) {
            BigDecimal capByDeposit = deposit.multiply(ltv)
                    .divide(HUNDRED.subtract(ltv), 2, RoundingMode.HALF_UP);
            if (capByDeposit.compareTo(annuityLoan) < 0) {
                BigDecimal depositNeeded = annuityLoan.multiply(HUNDRED.subtract(ltv))
                        .divide(ltv, 2, RoundingMode.HALF_UP);
                maxLoan = capByDeposit;
                steps.add(Step.money("Capped by your deposit", money(deposit) + " × " + plain(ltv)
                        + "% ÷ " + plain(HUNDRED.subtract(ltv)) + "%", capByDeposit,
                        product.name() + " lends at most " + plain(ltv) + "% of the price, so a deposit of "
                                + money(deposit) + " only reaches this far. To borrow the "
                                + money(annuityLoan) + " your income supports you would need "
                                + money(depositNeeded) + " down."));
            } else {
                steps.add(Step.check("Deposit meets the " + plain(HUNDRED.subtract(ltv)) + "% minimum", true,
                        "Your deposit covers the share " + product.name() + " asks you to put in."));
            }
        }

        BigDecimal maxPrice = maxLoan.add(deposit);
        steps.add(Step.money("The most you could pay for a home", money(maxLoan) + " + "
                + money(deposit) + " deposit", maxPrice, null));

        /*
         * What the loan they can actually take would cost — which is the ceiling only when the ceiling is
         * what limited it.
         *
         * Where the deposit capped the loan, the repayment on that smaller loan is lower than the ceiling,
         * and reporting the ceiling as "your repayment" overstated it: a household shown a loan of eight
         * million and a repayment worked out on ten would find the two did not go together, and would be
         * right. The ceiling is still on screen a few lines above, as what they *could* pay.
         */
        BigDecimal repaymentOnMaxLoan = Amortisation.monthlyRepayment(maxLoan, rate, term);
        if (repaymentOnMaxLoan.compareTo(affordableRepayment) < 0) {
            steps.add(Step.money("What that loan would actually cost you",
                    money(maxLoan) + " at " + rate(rate) + "% over " + term + " months",
                    repaymentOnMaxLoan,
                    "Less than the " + money(affordableRepayment) + " you could put to it, because the "
                            + "deposit — not your income — is what limited the loan."));
        }

        addFeeSteps(product, maxLoan, steps);

        // No particular property: the answer is the headline, and there is nothing to be ineligible for.
        if (request.propertyPrice() == null || request.propertyPrice().signum() <= 0) {
            boolean can = netIncome.signum() > 0 && maxLoan.signum() > 0;
            String outcome = can
                    ? AppConstant.AFFORDABILITY_ELIGIBLE : AppConstant.AFFORDABILITY_NOT_ELIGIBLE;
            String reason = reasonForHeadline(can, netIncome, maxLoan, ceiling, product, deposit);
            Map<String, Object> payload = payload(request, product, grossTotal, netIncome, ceiling, rate,
                    term, maxLoan, maxPrice, deposit, outcome);
            // The repayment reported is the one on the loan offered, not the ceiling it was measured
            // against — see the step above for why the two can differ.
            return new Decision(outcome, reason, maxLoan, maxPrice, repaymentOnMaxLoan,
                    Amortisation.shareOf(repaymentOnMaxLoan, netIncome), ceiling, rate, null, payload,
                    term, null, List.copyOf(steps));
        }

        // A property is named: the question becomes whether this one fits.
        BigDecimal price = request.propertyPrice();
        BigDecimal required = price.subtract(deposit).max(BigDecimal.ZERO);
        BigDecimal repaymentNeeded = Amortisation.monthlyRepayment(required, rate, term);
        BigDecimal dti = Amortisation.shareOf(repaymentNeeded, netIncome);

        steps.add(Step.money("This home", null, price, "The listing you are looking at."));
        steps.add(Step.money("What you would have to borrow", money(price) + " − " + money(deposit),
                required, null));
        steps.add(Step.money("What that would cost each month", money(required) + " at " + rate(rate)
                + "% over " + term + " months", repaymentNeeded, null));
        steps.add(Step.percent("Share of your net income", money(repaymentNeeded) + " ÷ "
                + money(netIncome), dti, "Against a ceiling of " + plain(ceiling) + "%."));

        boolean depositOk = true;
        if (product != null && product.minDepositPercent() != null
                && product.minDepositPercent().signum() > 0) {
            BigDecimal depositNeeded = Amortisation.percentOf(price, product.minDepositPercent());
            depositOk = deposit.compareTo(depositNeeded) >= 0;
            steps.add(Step.check("Deposit of at least " + plain(product.minDepositPercent()) + "%",
                    depositOk,
                    depositOk
                            ? "This home needs " + money(depositNeeded) + " down and you have "
                                    + money(deposit) + "."
                            : "This home needs " + money(depositNeeded) + " down — "
                                    + money(depositNeeded.subtract(deposit)) + " more than you have."));
        }
        addFeeSteps(product, required, steps);

        String outcome;
        String reason;
        if (required.signum() == 0) {
            outcome = AppConstant.AFFORDABILITY_ELIGIBLE;
            reason = "Your deposit covers the whole price — no borrowing needed.";
        } else if (netIncome.signum() <= 0) {
            outcome = AppConstant.AFFORDABILITY_NOT_ELIGIBLE;
            reason = "Your monthly commitments take everything you have told us you earn.";
        } else if (!depositOk) {
            outcome = AppConstant.AFFORDABILITY_MARGINAL;
            reason = product.name() + " needs a deposit of at least "
                    + plain(product.minDepositPercent()) + "% on this home, which is more than you have "
                    + "told us about. The repayment itself would take " + plain(dti) + "% of your income.";
        } else if (dti.compareTo(ceiling) <= 0) {
            outcome = AppConstant.AFFORDABILITY_ELIGIBLE;
            reason = "The repayment would take " + plain(dti) + "% of your net income, inside the "
                    + plain(ceiling) + "% " + lender(product) + " allows.";
        } else if (dti.compareTo(ceiling.add(marginalBand)) <= 0) {
            outcome = AppConstant.AFFORDABILITY_MARGINAL;
            reason = "The repayment would take " + plain(dti) + "% of your net income, just past the "
                    + plain(ceiling) + "% " + lender(product) + " allows. A longer term or a larger deposit "
                    + "would bring it within reach.";
        } else {
            outcome = AppConstant.AFFORDABILITY_NOT_ELIGIBLE;
            reason = "The repayment would take " + plain(dti) + "% of your net income, well past the "
                    + plain(ceiling) + "% " + lender(product) + " allows.";
        }

        Map<String, Object> payload = payload(request, product, grossTotal, netIncome, ceiling, rate, term,
                maxLoan, maxPrice, deposit, outcome);
        payload.put("propertyPrice", price);
        payload.put("loanRequired", required);
        payload.put("repaymentRequired", repaymentNeeded);
        payload.put("repaymentAsShareOfNetIncome", dti);
        // `required` — what this home needs — travels beside the maximum, because the question asked was
        // about this home and the two are different numbers.
        return new Decision(outcome, reason, maxLoan, maxPrice, repaymentNeeded, dti, ceiling, rate,
                null, payload, term, required, List.copyOf(steps));
    }

    /** The ceiling this product allows, or the platform's configured default where it names none. */
    private BigDecimal ceilingFor(ProductTerms product) {
        if (product != null && product.maxDtiPercent() != null
                && product.maxDtiPercent().signum() > 0) {
            return product.maxDtiPercent().min(HUNDRED);
        }
        return ceilingPercent();
    }

    /**
     * The term, moved into the product's band if it is outside it — and said out loud when it moves.
     *
     * <p>A silent clamp is how somebody asks for twenty-five years, is quoted twenty, and finds out at the
     * branch. The step is added whether or not it bound, because "we used the term you asked for" is also
     * worth knowing on a page that exists to be checked.
     */
    private short termFor(ProductTerms product, int requested, List<Step> steps) {
        short term = (short) requested;
        if (product == null) {
            steps.add(Step.months("Over a term of", BigDecimal.valueOf(term), null));
            return term;
        }
        short min = product.minTermMonths() == null ? term : product.minTermMonths();
        short max = product.maxTermMonths() == null ? term : product.maxTermMonths();
        short bounded = (short) Math.min(Math.max(term, min), max);
        steps.add(Step.months("Over a term of", BigDecimal.valueOf(bounded),
                bounded == term
                        ? product.name() + " runs from " + min + " to " + max + " months."
                        : "You asked for " + term + " months; " + product.name() + " runs from " + min
                                + " to " + max + ", so this is worked out over " + bounded + "."));
        return bounded;
    }

    /**
     * What is payable on the day, named rather than folded into the loan.
     *
     * <p>Adding fees to the principal would quietly change every figure above them; a buyer needs to know
     * what cash the completion asks for, which is a different question from what they can borrow.
     */
    private void addFeeSteps(ProductTerms product, BigDecimal loan, List<Step> steps) {
        if (product == null || loan.signum() <= 0) return;
        BigDecimal processing = pct(product.processingFeePercent());
        BigDecimal insurance = pct(product.insurancePercent());
        if (processing.signum() > 0) {
            steps.add(Step.money("Arrangement fee", money(loan) + " × " + plain(processing) + "%",
                    Amortisation.percentOf(loan, processing), "Payable on completion, not borrowed."));
        }
        if (insurance.signum() > 0) {
            steps.add(Step.money("Insurance", money(loan) + " × " + plain(insurance) + "%",
                    Amortisation.percentOf(loan, insurance), "Payable on completion, not borrowed."));
        }
    }

    private String reasonForHeadline(boolean can, BigDecimal netIncome, BigDecimal maxLoan,
                                     BigDecimal ceiling, ProductTerms product, BigDecimal deposit) {
        if (netIncome.signum() <= 0) {
            return "Your monthly commitments take everything you have told us you earn.";
        }
        if (!can && product != null && deposit.signum() == 0) {
            return product.name() + " lends at most " + plain(product.maxLtvPercent())
                    + "% of a home's price, so it needs a deposit to lend against. Tell us what you have "
                    + "saved and this becomes a figure.";
        }
        return "Based on what you can set aside each month at the ceiling of " + plain(ceiling)
                + "% of net income" + (product == null ? "." : ", under " + product.name() + ".");
    }

    private static String lender(ProductTerms product) {
        return product == null ? "the bank typically" : product.institutionName();
    }

    /** The response kept verbatim on the row. Unchanged in shape; richer now there is a product. */
    private Map<String, Object> payload(Request request, ProductTerms product, BigDecimal grossTotal,
                                        BigDecimal netIncome, BigDecimal ceiling, BigDecimal rate,
                                        short term, BigDecimal maxLoan, BigDecimal maxPrice,
                                        BigDecimal deposit, String outcome) {
        Map<String, Object> working = new LinkedHashMap<>();
        working.put("provider", name());
        if (product != null) {
            working.put("productReference", product.reference());
            working.put("productName", product.name());
            working.put("institutionName", product.institutionName());
        }
        working.put("grossTotalMonthlyIncome", grossTotal);
        working.put("monthlyObligations", nz(request.monthlyObligations()));
        working.put("netMonthlyIncome", netIncome);
        working.put("dtiCeilingPercent", ceiling);
        working.put("affordableMonthlyRepayment", Amortisation.percentOf(netIncome, ceiling));
        working.put("assumedAnnualRatePercent", rate);
        working.put("termMonths", term);
        working.put("maxLoanAmount", maxLoan);
        working.put("depositAmount", deposit);
        working.put("maxPropertyPrice", maxPrice);
        working.put("outcome", outcome);
        return working;
    }

    private static BigDecimal pct(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String money(BigDecimal value) {
        return nz(value).setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * The rate to assume when nothing more specific is in play.
     *
     * <p>Stored as a STRING config rather than an INTEGER because rates are not whole numbers and 13 is not
     * 13.5 — a whole-percent key would be a rounding error built into the configuration itself.
     */
    public BigDecimal defaultRate() {
        String raw = configs.getString(ConfigKey.AFFORDABILITY_DEFAULT_RATE);
        try {
            return new BigDecimal(raw == null || raw.isBlank() ? "13.5" : raw.trim());
        } catch (NumberFormatException e) {
            log.warn("affordability.default.rate.percent is not a number ('{}') — using 13.5", raw);
            return new BigDecimal("13.5");
        }
    }

    public int defaultTermMonths() {
        return configs.getInt(ConfigKey.AFFORDABILITY_DEFAULT_TERM);
    }

    private BigDecimal ceilingPercent() {
        BigDecimal ceiling = new BigDecimal(configs.getInt(ConfigKey.AFFORDABILITY_DTI_CEILING));
        // A ceiling of zero or one over a hundred is a misconfiguration that would silently refuse or
        // approve everybody. Clamped, and the row is left as the operator set it so the mistake stays visible.
        return ceiling.max(BigDecimal.ONE).min(HUNDRED);
    }

    private static String plain(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    /**
     * A rate, as it is quoted.
     *
     * <p>Not {@link #plain}, which rounds to one place: 13.25% printed as 13.3% is a different rate from
     * the one on the product, and this is a panel whose entire job is being checkable against the bank's
     * own sheet.
     */
    private static String rate(BigDecimal value) {
        return nz(value).stripTrailingZeros().toPlainString();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
