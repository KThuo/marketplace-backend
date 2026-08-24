package com.hodi.modules.finance;

import com.hodi.common.AppConstant;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The platform's own affordability rules, written down.
 *
 * <h2>Not a stub</h2>
 *
 * <p>It returns real arithmetic a lender would recognise, not a fixed number and not noise. The point of the
 * interface is that the OCP microservice replaces this as the *authority*; the point of this class is that
 * until then a buyer still gets an answer, and it is an answer somebody can check by hand.
 *
 * <h2>The rules, in order</h2>
 *
 * <ol>
 *   <li><strong>Net income</strong> = gross + other income − existing monthly obligations. Obligations come
 *       off the top rather than out of the ceiling: a household already paying a car loan has that much less
 *       to give, whatever percentage anyone is willing to lend against.</li>
 *   <li><strong>Affordable repayment</strong> = net income × the configured DTI ceiling. Kenyan lenders
 *       commonly sit between 35% and 50%; the row defaults to 40 and an operator moves it without a deploy.</li>
 *   <li><strong>Maximum loan</strong> = the annuity read backwards at the assumed rate over the chosen term.</li>
 *   <li><strong>Maximum price</strong> = that loan plus whatever deposit they have.</li>
 * </ol>
 *
 * <h2>Three outcomes, not two</h2>
 *
 * <p>Where a specific property is in play, the repayment it would need is compared with what the household
 * can carry. Within the ceiling is ELIGIBLE; past it but inside the marginal band is MARGINAL; beyond that is
 * NOT_ELIGIBLE. A hard line at the ceiling turns one shilling into a refusal, which is not how a lender reads
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

    @Override
    public Decision assess(Request request) {
        BigDecimal ceiling = ceilingPercent();
        BigDecimal marginalBand = new BigDecimal(configs.getInt(ConfigKey.AFFORDABILITY_MARGINAL_BAND));
        BigDecimal rate = request.annualRate();

        BigDecimal grossTotal = nz(request.grossMonthlyIncome()).add(nz(request.otherMonthlyIncome()));
        BigDecimal netIncome = grossTotal.subtract(nz(request.monthlyObligations())).max(BigDecimal.ZERO);

        BigDecimal affordableRepayment = Amortisation.percentOf(netIncome, ceiling);
        BigDecimal maxLoan = Amortisation.loanFor(affordableRepayment, rate, request.termMonths());
        BigDecimal maxPrice = maxLoan.add(nz(request.depositAmount()));

        Map<String, Object> working = new LinkedHashMap<>();
        working.put("provider", name());
        working.put("grossTotalMonthlyIncome", grossTotal);
        working.put("monthlyObligations", nz(request.monthlyObligations()));
        working.put("netMonthlyIncome", netIncome);
        working.put("dtiCeilingPercent", ceiling);
        working.put("affordableMonthlyRepayment", affordableRepayment);
        working.put("assumedAnnualRatePercent", rate);
        working.put("termMonths", request.termMonths());
        working.put("maxLoanAmount", maxLoan);
        working.put("depositAmount", nz(request.depositAmount()));
        working.put("maxPropertyPrice", maxPrice);

        // No particular property: the answer is the headline, and there is nothing to be ineligible for.
        if (request.propertyPrice() == null || request.propertyPrice().signum() <= 0) {
            String outcome = netIncome.signum() > 0
                    ? AppConstant.AFFORDABILITY_ELIGIBLE
                    : AppConstant.AFFORDABILITY_NOT_ELIGIBLE;
            String reason = netIncome.signum() > 0
                    ? "Based on what you can set aside each month at the ceiling of "
                            + ceiling.stripTrailingZeros().toPlainString() + "% of net income."
                    : "Your monthly commitments take everything you have told us you earn.";
            working.put("outcome", outcome);
            return new Decision(outcome, reason, maxLoan, maxPrice, affordableRepayment,
                    Amortisation.shareOf(affordableRepayment, netIncome), ceiling, rate, null, working);
        }

        // A property is named: the question becomes whether this one fits.
        BigDecimal required = request.propertyPrice().subtract(nz(request.depositAmount()))
                .max(BigDecimal.ZERO);
        BigDecimal repaymentNeeded = Amortisation.monthlyRepayment(required, rate, request.termMonths());
        BigDecimal dti = Amortisation.shareOf(repaymentNeeded, netIncome);

        working.put("propertyPrice", request.propertyPrice());
        working.put("loanRequired", required);
        working.put("repaymentRequired", repaymentNeeded);
        working.put("repaymentAsShareOfNetIncome", dti);

        String outcome;
        String reason;
        if (required.signum() == 0) {
            outcome = AppConstant.AFFORDABILITY_ELIGIBLE;
            reason = "Your deposit covers the whole price — no borrowing needed.";
        } else if (netIncome.signum() <= 0) {
            outcome = AppConstant.AFFORDABILITY_NOT_ELIGIBLE;
            reason = "Your monthly commitments take everything you have told us you earn.";
        } else if (dti.compareTo(ceiling) <= 0) {
            outcome = AppConstant.AFFORDABILITY_ELIGIBLE;
            reason = "The repayment would take " + plain(dti) + "% of your net income, inside the "
                    + plain(ceiling) + "% a lender typically allows.";
        } else if (dti.compareTo(ceiling.add(marginalBand)) <= 0) {
            outcome = AppConstant.AFFORDABILITY_MARGINAL;
            reason = "The repayment would take " + plain(dti) + "% of your net income, just past the "
                    + plain(ceiling) + "% a lender typically allows. A longer term or a larger deposit "
                    + "would bring it within reach.";
        } else {
            outcome = AppConstant.AFFORDABILITY_NOT_ELIGIBLE;
            reason = "The repayment would take " + plain(dti) + "% of your net income, well past the "
                    + plain(ceiling) + "% a lender typically allows.";
        }
        working.put("outcome", outcome);

        return new Decision(outcome, reason, maxLoan, maxPrice, repaymentNeeded, dti, ceiling, rate,
                null, working);
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

    private static BigDecimal nz(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
