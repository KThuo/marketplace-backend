package com.hodi.modules.finance;

import com.hodi.common.dto.PagedDataRequest;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Request and response shapes for M3.
 *
 * <p>Three response records where a lazier design would have one, and each split earns its place:
 *
 * <ul>
 *   <li>{@link ProductResponse} vs {@link PublicProductResponse} — the bank's own view carries the
 *       lifecycle, the internal notes and the draft; the public one carries what is on offer. Two records
 *       rather than one with fields blanked, exactly as for a listing.</li>
 *   <li>{@link AffordabilityResponse} vs {@link AffordabilitySummary} — the first is somebody's household
 *       finances and goes back only to them; the second is what the platform's own list may see, and it has
 *       no income on it at all.</li>
 * </ul>
 */
public final class FinanceDtos {

    private FinanceDtos() {}

    // ── mortgage products, bank side ────────────────────────────────────────

    public record ProductResponse(
            String id,
            String reference,
            String institutionId,
            String institutionName,
            String name,
            String description,
            String productType,
            String currency,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            Short minTermMonths,
            Short maxTermMonths,
            BigDecimal interestRate,
            String rateType,
            BigDecimal maxLtvPercent,
            BigDecimal minDepositPercent,
            BigDecimal processingFeePercent,
            BigDecimal insurancePercent,
            String otherFeesNote,
            BigDecimal minMonthlyIncome,
            BigDecimal maxDtiPercent,
            String eligibilityNotes,
            String requiredDocuments,
            boolean published,
            OffsetDateTime publishedAt,
            Integer status,
            String statusFlag,
            OffsetDateTime createdAt,
            String createdBy) {}

    public record SaveProductRequest(
            @NotBlank(message = "Give the product a name")
            @Size(max = 160, message = "That name is too long")
            String name,

            String description,
            String productType,

            @NotNull(message = "An interest rate is required")
            @DecimalMin(value = "0.001", message = "An interest rate must be more than zero")
            BigDecimal interestRate,

            String rateType,

            BigDecimal minAmount,
            BigDecimal maxAmount,
            Short minTermMonths,
            Short maxTermMonths,

            BigDecimal maxLtvPercent,
            BigDecimal minDepositPercent,
            BigDecimal processingFeePercent,
            BigDecimal insurancePercent,
            String otherFeesNote,

            BigDecimal minMonthlyIncome,
            BigDecimal maxDtiPercent,
            String eligibilityNotes,
            String requiredDocuments) {}

    @Getter
    @Setter
    public static class ProductListRequest extends PagedDataRequest {
        private String productType;
        /** {@code true}, {@code false}, or absent for both. */
        private Boolean published;
    }

    // ── mortgage products, public side ────────────────────────────────────────

    /**
     * What a buyer sees.
     *
     * <p>No lifecycle, no internal notes, no draft — being on offer is the permission to be seen, the same
     * rule the marketplace applies to listings. The eligibility text stays: a buyer deciding whether to
     * approach the bank needs to know what that bank will ask for.
     */
    public record PublicProductResponse(
            String reference,
            String institutionName,
            String name,
            String description,
            String productType,
            String currency,
            BigDecimal minAmount,
            BigDecimal maxAmount,
            Short minTermMonths,
            Short maxTermMonths,
            BigDecimal interestRate,
            String rateType,
            BigDecimal maxLtvPercent,
            BigDecimal minDepositPercent,
            BigDecimal processingFeePercent,
            BigDecimal insurancePercent,
            String eligibilityNotes,
            String requiredDocuments) {}

    /**
     * One product costed against one listing.
     *
     * <p>Computed on read and never stored: these are indicative figures derived from products that change,
     * and a frozen copy would be a promise the platform did not make.
     *
     * @param affordable null when the caller has not said what they earn — absence of an answer, which is
     *                   different from a "no"
     */
    public record FinanceOption(
            PublicProductResponse product,
            BigDecimal depositRequired,
            BigDecimal loanAmount,
            BigDecimal monthlyRepayment,
            Short termMonths,
            BigDecimal processingFee,
            BigDecimal totalPayable,
            Boolean affordable) {}

    public record FinancePanel(
            String propertyReference,
            BigDecimal price,
            String currency,
            /** What the options are costed against: the price, or a completed valuation's figure when lower. */
            BigDecimal lendingValue,
            /** PRICE, FORCED_SALE or MARKET — which figure set {@code lendingValue}. */
            String lendingBasis,
            String valuationReference,
            /*
             * No count of banks. It counted those partnered with this seller, and the panel said "from N
             * banks this seller works with" — a sentence about a marketplace of banks competing for a
             * seller's portfolio. There is one bank and it runs the platform, so the number could only
             * ever be 1, and a figure that cannot vary is not information.
             */
            List<FinanceOption> options,
            /** Said out loud on every screen carrying these numbers, because that is what they are. */
            String disclaimer) {}

    @Getter
    @Setter
    public static class PublicProductSearchRequest extends PagedDataRequest {
        private String productType;
        /** Costs every result against this price when given. */
        private BigDecimal price;
        private Short termMonths;
    }

    // ── affordability ─────────────────────────────────────────────────────────

    /**
     * @param propertyReference optional — a check run from a listing is about that listing, and a check run
     *                          from the calculator is about the household
     */
    public record AffordabilityRequest(
            /**
             * Pay after tax and deductions — what actually reaches the account.
             *
             * <p>Take-home rather than gross, and the rename is the point. The form used to ask for gross
             * and the arithmetic then spent all of it, so every figure was overstated by whatever PAYE, the
             * housing levy and SHIF had already taken — on a Kenyan salary that is not a rounding error.
             *
             * <p>The alternative was to model payroll from a gross figure. That is a second product with a
             * yearly maintenance burden, and a model that is slightly wrong is worse here than no model at
             * all: it would put a precise-looking number on a deduction nobody checked.
             */
            @NotNull(message = "Tell us what you take home each month")
            @DecimalMin(value = "0", message = "That cannot be negative")
            BigDecimal monthlyTakeHome,

            BigDecimal otherMonthlyIncome,
            BigDecimal monthlyObligations,
            BigDecimal depositAmount,
            Short termMonths,
            String employmentType,
            Integer dependants,
            String propertyReference,

            /**
             * The mortgage to cost this against, by its public reference.
             *
             * <p>Optional, and deliberately. Somebody who has not shopped for a bank yet still deserves a
             * number, and refusing to answer until they choose would make the calculator useless at the
             * moment it is most useful. With no product the platform's own indicative rules stand, which is
             * what every check did before products were carried at all.
             */
            String productReference) {}

    /**
     * One term, costed two ways.
     *
     * <p>Both questions somebody asks about length, answered on the same row, because they are the same
     * arithmetic run in opposite directions and seeing only one of them is what makes a repayment look
     * wrong. {@code monthlyRepayment} is what the loan they can take would cost over <em>this</em> term —
     * shorter is dearer. {@code maxLoan} is what their ceiling payment would borrow over this term —
     * shorter buys less. On the term actually chosen the two agree with the headline, which is what makes
     * the table checkable rather than decorative.
     *
     * @param months how long, in months, so a screen can render years without the server assuming twelve
     * @param chosen the row the answer above was computed on
     */
    public record TermOption(
            short months,
            BigDecimal monthlyRepayment,
            BigDecimal maxLoan,
            boolean chosen) {}

    /** Everything back, and only ever to the person who entered it. */
    public record AffordabilityResponse(
            String reference,
            String decision,
            String decisionReason,
            String currency,
            BigDecimal monthlyTakeHome,
            BigDecimal otherMonthlyIncome,
            BigDecimal monthlyObligations,
            BigDecimal depositAmount,
            Short termMonths,
            String employmentType,
            Integer dependants,
            BigDecimal maxLoanAmount,
            BigDecimal maxPropertyPrice,
            /** What this listing would need borrowed — its price less the deposit. Null without a listing. */
            BigDecimal loanRequired,
            BigDecimal monthlyRepayment,
            BigDecimal dtiPercent,
            BigDecimal dtiCeilingPercent,
            BigDecimal assumedRate,
            String provider,
            /** What to call the author of these figures on screen. Never the code. */
            String providerLabel,
            /** The mortgage this was computed against, or null for the platform's own rules. */
            String productReference,
            String productName,
            String institutionName,
            /** The working, in order, as a person would check it by hand. */
            List<AffordabilityProvider.Step> steps,
            /** What each term the product allows would cost, and what it would borrow. */
            List<TermOption> terms,
            String propertyReference,
            String propertyTitle,
            BigDecimal propertyPrice,
            /** What the loan was sized against: the price, or a completed valuation's figure when lower. */
            BigDecimal lendingValue,
            /** PRICE, FORCED_SALE or MARKET. */
            String lendingBasis,
            String valuationReference,
            /** The assessor's own working, so the figures can be checked rather than trusted. */
            Map<String, Object> working,
            /** What the money would actually buy today, at the banks in play. */
            List<FinanceOption> options,
            String disclaimer,
            /** Null for an unsaved estimate — a stranger's calculation is not kept. */
            OffsetDateTime createdAt) {}

    /**
     * What the platform's list may see.
     *
     * <p>No income, no obligations, no deposit. A separate record rather than the same one with fields
     * blanked, for the reason the listing split exists: a response that sometimes carries somebody's salary
     * is one that will eventually carry it when it should not.
     */
    public record AffordabilitySummary(
            String reference,
            String decision,
            String currency,
            BigDecimal maxLoanAmount,
            BigDecimal maxPropertyPrice,
            BigDecimal monthlyRepayment,
            BigDecimal dtiPercent,
            BigDecimal dtiCeilingPercent,
            BigDecimal assumedRate,
            Short termMonths,
            String provider,
            String providerLabel,
            String productReference,
            String productName,
            String propertyReference,
            BigDecimal propertyPrice,
            OffsetDateTime createdAt) {}

    @Getter
    @Setter
    public static class AffordabilityListRequest extends PagedDataRequest {
        private String decision;
        private String provider;
    }
}
