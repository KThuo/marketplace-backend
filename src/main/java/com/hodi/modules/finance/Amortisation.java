package com.hodi.modules.finance;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * The two sums this module rests on, in one place.
 *
 * <h2>Why a class and not two private methods</h2>
 *
 * <p>Three callers need them and would otherwise each have a copy: the assessor turning income into a loan,
 * the product matcher turning a listing's price into a repayment, and the check that records what it decided.
 * Three copies of an annuity formula is three places for a rounding rule to drift, and the number they
 * disagree about is the one a buyer writes down.
 *
 * <h2>Rounding</h2>
 *
 * <p>Money to two places, HALF_UP — the convention a bank statement uses. Intermediate compounding runs at
 * {@link MathContext#DECIMAL64}, because rounding {@code (1 + i)^-n} to two places before dividing throws
 * away most of the precision the exponent just created.
 *
 * <p>A zero rate is handled explicitly rather than left to fall out of the formula: at {@code i = 0} the
 * annuity divides by zero, and an interest-free product is a real thing the bank may offer.
 */
public final class Amortisation {

    private Amortisation() {}

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal TWELVE = new BigDecimal("12");
    private static final MathContext MC = MathContext.DECIMAL64;

    /**
     * The monthly repayment on a loan.
     *
     * <p>{@code P = L · i / (1 − (1 + i)^−n)}, the standard annuity.
     *
     * @param principal    the amount borrowed
     * @param annualRate   the nominal annual rate as a percentage — 13.5, not 0.135
     * @param termMonths   the number of payments
     */
    public static BigDecimal monthlyRepayment(BigDecimal principal, BigDecimal annualRate, int termMonths) {
        if (principal == null || principal.signum() <= 0 || termMonths <= 0) return BigDecimal.ZERO;

        BigDecimal monthlyRate = monthlyRate(annualRate);
        if (monthlyRate.signum() == 0) {
            return principal.divide(BigDecimal.valueOf(termMonths), 2, RoundingMode.HALF_UP);
        }

        BigDecimal growth = onePlus(monthlyRate).pow(termMonths, MC);
        BigDecimal denominator = BigDecimal.ONE.subtract(BigDecimal.ONE.divide(growth, MC));
        return principal.multiply(monthlyRate, MC).divide(denominator, 2, RoundingMode.HALF_UP);
    }

    /**
     * The loan a given monthly repayment will carry — the annuity read backwards.
     *
     * <p>{@code L = P · (1 − (1 + i)^−n) / i}. This is the direction affordability runs in: a household knows
     * what it can pay, and wants to know what that buys.
     */
    public static BigDecimal loanFor(BigDecimal monthlyPayment, BigDecimal annualRate, int termMonths) {
        if (monthlyPayment == null || monthlyPayment.signum() <= 0 || termMonths <= 0) return BigDecimal.ZERO;

        BigDecimal monthlyRate = monthlyRate(annualRate);
        if (monthlyRate.signum() == 0) {
            return monthlyPayment.multiply(BigDecimal.valueOf(termMonths)).setScale(2, RoundingMode.HALF_UP);
        }

        BigDecimal growth = onePlus(monthlyRate).pow(termMonths, MC);
        BigDecimal factor = BigDecimal.ONE.subtract(BigDecimal.ONE.divide(growth, MC));
        return monthlyPayment.multiply(factor, MC).divide(monthlyRate, 2, RoundingMode.HALF_UP);
    }

    /** A percentage of an amount, to the shilling. */
    public static BigDecimal percentOf(BigDecimal amount, BigDecimal percent) {
        if (amount == null || percent == null) return BigDecimal.ZERO;
        return amount.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    /** What share of {@code income} the {@code amount} takes, as a percentage to two places. */
    public static BigDecimal shareOf(BigDecimal amount, BigDecimal income) {
        if (amount == null || income == null || income.signum() <= 0) return BigDecimal.ZERO;
        return amount.multiply(HUNDRED).divide(income, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal monthlyRate(BigDecimal annualRate) {
        if (annualRate == null || annualRate.signum() <= 0) return BigDecimal.ZERO;
        return annualRate.divide(HUNDRED, MC).divide(TWELVE, MC);
    }

    private static BigDecimal onePlus(BigDecimal monthlyRate) {
        return BigDecimal.ONE.add(monthlyRate);
    }
}
