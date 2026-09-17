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

    /**
     * What a buyer should see called the author of these figures.
     *
     * <p>Separate from {@link #name()}, which is a code: it is stored on every check and named in a
     * configuration row, so it cannot change without a migration for a caption. What it must not do is
     * reach a screen — a household reading "MOCK" beside their own salary concludes the platform is a
     * demonstration, and they are not wrong to.
     */
    String label();

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
            String currency,
            /** The mortgage this is being costed against, or null for the platform's own indicative rules. */
            ProductTerms product) {}

    /**
     * One bank's mortgage, as the arithmetic needs it.
     *
     * <p>A copy of the product's own columns rather than the entity, so this contract stays something an
     * external assessor could implement without importing the platform's persistence.
     *
     * <p>Every field here binds the answer: the rate it is computed at, the band the term must sit in, the
     * most this product will lend against a price, the deposit it insists on, the share of income it will
     * allow, the floor it will look at, and the fees payable on the day. Before these were carried, every
     * household in the country got the same configured default rate whichever mortgage they had chosen —
     * which made choosing one a decoration.
     */
    record ProductTerms(
            String reference,
            String name,
            String institutionName,
            BigDecimal annualRate,
            String rateType,
            Short minTermMonths,
            Short maxTermMonths,
            BigDecimal maxLtvPercent,
            BigDecimal minDepositPercent,
            BigDecimal maxDtiPercent,
            BigDecimal minMonthlyIncome,
            BigDecimal processingFeePercent,
            BigDecimal insurancePercent) {}

    /**
     * One line of the working.
     *
     * <p>The map on {@link Decision#payload} is the assessor's own response and is kept verbatim for the
     * row; this is the same reasoning arranged for a person to read. They are not the same thing and the
     * difference matters: a screen rendering the payload prints "maxLoanAmount / 4875000.00", which is a
     * debug dump wearing the word "working".
     *
     * @param formula the arithmetic in the household's own figures, so the line can be checked by hand
     * @param unit    MONEY, PERCENT, MONTHS or NONE — how the screen should render {@code value}
     * @param ok      null for a calculation; true or false for a rule the application either meets or does not
     */
    record Step(
            String label,
            String formula,
            BigDecimal value,
            String unit,
            String note,
            Boolean ok) {

        static Step money(String label, String formula, BigDecimal value, String note) {
            return new Step(label, formula, value, "MONEY", note, null);
        }

        static Step percent(String label, String formula, BigDecimal value, String note) {
            return new Step(label, formula, value, "PERCENT", note, null);
        }

        static Step months(String label, BigDecimal value, String note) {
            return new Step(label, null, value, "MONTHS", note, null);
        }

        static Step check(String label, boolean ok, String note) {
            return new Step(label, null, null, "NONE", note, ok);
        }
    }

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
            Map<String, Object> payload,
            /** The term actually used, which a product's band may have moved. */
            short termMonths,
            /** The working, in order, for the screen that explains the answer. */
            java.util.List<Step> steps) {}
}
