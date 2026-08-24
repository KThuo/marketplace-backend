package com.hodi.modules.finance;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Whoever answers "what can this household carry" (plan §3.11).
 *
 * <p>An interface with a mock behind it, and the mock is not a placeholder to be deleted — it is the
 * platform's own indicative answer, and it stays useful after the credit microservice arrives as the thing
 * that responds when OCP is unreachable. What the missing contract blocks is *authoritative* numbers, not the
 * funnel around them.
 *
 * <p>Implementations declare a {@link #name()} and the configuration row picks one. Nothing that calls this
 * knows which is in play, and nothing here needs changing on the day the real one lands.
 */
public interface AffordabilityProvider {

    /** Matched against {@code affordability.provider}, and recorded on every check this answers. */
    String name();

    Decision assess(Request request);

    /**
     * What a household brought, in its own currency.
     *
     * @param propertyPrice the listing being considered, or null for an open-ended "what can I afford"
     */
    record Request(
            BigDecimal grossMonthlyIncome,
            BigDecimal otherMonthlyIncome,
            BigDecimal monthlyObligations,
            BigDecimal depositAmount,
            int termMonths,
            String employmentType,
            Integer dependants,
            BigDecimal propertyPrice,
            /** The rate to assume. The caller resolves it — a product's own rate, or the configured default. */
            BigDecimal annualRate,
            String currency) {}

    /**
     * The answer, and enough of the working to explain it.
     *
     * @param payload the assessor's own response, kept verbatim for the row. When somebody asks why a figure
     *                was what it was, the answer has to be the thing that produced it rather than this
     *                application's reading of it — which is the part that could be wrong.
     */
    record Decision(
            String outcome,
            String reason,
            BigDecimal maxLoanAmount,
            BigDecimal maxPropertyPrice,
            BigDecimal monthlyRepayment,
            BigDecimal dtiPercent,
            BigDecimal dtiCeilingPercent,
            BigDecimal assumedRate,
            String providerReference,
            Map<String, Object> payload) {}
}
