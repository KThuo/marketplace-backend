package com.hodi.modules.developments;

import com.hodi.common.AppConstant;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;

/**
 * The arithmetic behind a development's derived figures, with no database in it.
 *
 * <p>Separated from {@link DevelopmentInventoryService} deliberately. That class is the only writer of eleven
 * cached columns and most of it is reading rows and saving them; the part that can be *wrong* is in here — a
 * weighted average that ignores a null weight, a roll-up that calls a finished project unstarted. Pure
 * functions over plain records, so the whole rule set is provable in a unit test with no context to stand up.
 */
final class InventoryMaths {

    private InventoryMaths() {}

    /** One phase, reduced to the four numbers a derivation can use. */
    record PhaseFigures(short percentComplete, Short weightPct, BigDecimal budget, Integer plannedUnits) {}

    /** A percentage and the rule that produced it. The basis is rendered, so it is part of the answer. */
    record Derived(short percent, String basis) {}

    /**
     * A development's percentage, from its live phases.
     *
     * <p>The rules are tried in order of how deliberate they are, not how clever:
     *
     * <ol>
     *   <li><b>WEIGHT</b> — every phase carries a weight and they sum to 100. Somebody said what each stage is
     *       worth, and a stated intention beats an inferred one.</li>
     *   <li><b>BUDGET</b> — every phase carries a positive budget. Money committed is the honest weight for a
     *       project's progress, which is why it sits above unit counts for the bank.</li>
     *   <li><b>UNITS</b> — every phase carries a positive planned unit count.</li>
     *   <li><b>EQUAL</b> — none of the above is complete, so a plain mean, and the basis says so rather than
     *       implying a weighting nobody supplied.</li>
     *   <li><b>STATED</b> — no live phases at all. The figure on the development stands, because there is
     *       nothing to derive it from and refusing to answer would leave a one-line project stuck at zero.</li>
     * </ol>
     *
     * <p>"Every phase" is the condition throughout: one phase missing a weight makes a weighted average a lie
     * dressed as precision, because the missing one silently counts for nothing.
     *
     * @param stated what the development currently holds, returned untouched when there is nothing to derive
     */
    static Derived derivePercent(List<PhaseFigures> phases, short stated) {
        if (phases == null || phases.isEmpty()) {
            return new Derived(stated, AppConstant.PERCENT_BASIS_STATED);
        }

        if (phases.stream().allMatch(p -> p.weightPct() != null && p.weightPct() > 0)) {
            int sum = phases.stream().mapToInt(PhaseFigures::weightPct).sum();
            if (sum == 100) {
                return new Derived(weighted(phases, p -> BigDecimal.valueOf(p.weightPct())),
                        AppConstant.PERCENT_BASIS_WEIGHT);
            }
            // Weights that do not sum to 100 describe no whole. Fall through rather than normalise them:
            // scaling somebody's mistake into a plausible number is how it survives to the next screen.
        }

        if (phases.stream().allMatch(p -> positive(p.budget()))) {
            return new Derived(weighted(phases, PhaseFigures::budget), AppConstant.PERCENT_BASIS_BUDGET);
        }

        if (phases.stream().allMatch(p -> p.plannedUnits() != null && p.plannedUnits() > 0)) {
            return new Derived(weighted(phases, p -> BigDecimal.valueOf(p.plannedUnits())),
                    AppConstant.PERCENT_BASIS_UNITS);
        }

        BigDecimal mean = phases.stream()
                .map(p -> BigDecimal.valueOf(p.percentComplete()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(phases.size()), 0, RoundingMode.HALF_UP);
        return new Derived(clamp(mean), AppConstant.PERCENT_BASIS_EQUAL);
    }

    /**
     * The least-advanced build status in a set — a typology is as far along as its furthest-behind unit.
     *
     * <p>Ordered by the build sequence, never alphabetically: COMPLETE sorts before PLANNED as a string, which
     * would report a finished typology as unstarted. An empty set is PLANNED, because nothing built is where
     * everything starts.
     */
    static String leastAdvanced(Collection<String> statuses) {
        if (statuses == null || statuses.isEmpty()) return AppConstant.BUILD_PLANNED;
        return statuses.stream()
                .filter(s -> s != null && rank(s) >= 0)
                .min((a, b) -> Integer.compare(rank(a), rank(b)))
                .orElse(AppConstant.BUILD_PLANNED);
    }

    /**
     * A development's own build status, from the percentage and its units.
     *
     * <p>Handover is checked before completion: a project whose every unit has been handed over is finished in
     * the sense that matters, and it would otherwise be reported as merely COMPLETE forever.
     */
    static String rollUpStatus(short percent, String leastAdvancedUnit) {
        if (AppConstant.BUILD_HANDED_OVER.equals(leastAdvancedUnit)) return AppConstant.BUILD_HANDED_OVER;
        if (percent >= 100) return AppConstant.BUILD_COMPLETE;
        if (percent <= 0 && AppConstant.BUILD_PLANNED.equals(leastAdvancedUnit)) {
            return AppConstant.BUILD_PLANNED;
        }
        return AppConstant.BUILD_UNDER_CONSTRUCTION;
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private static short weighted(List<PhaseFigures> phases,
                                  java.util.function.Function<PhaseFigures, BigDecimal> weightOf) {
        BigDecimal totalWeight = BigDecimal.ZERO;
        BigDecimal weightedSum = BigDecimal.ZERO;
        for (PhaseFigures p : phases) {
            BigDecimal w = weightOf.apply(p);
            totalWeight = totalWeight.add(w);
            weightedSum = weightedSum.add(w.multiply(BigDecimal.valueOf(p.percentComplete())));
        }
        if (totalWeight.signum() == 0) return 0;
        return clamp(weightedSum.divide(totalWeight, 0, RoundingMode.HALF_UP));
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    /** The column is 0..100 and the database enforces it, so a rounding excursion is caught here. */
    private static short clamp(BigDecimal value) {
        long v = value.setScale(0, RoundingMode.HALF_UP).longValue();
        return (short) Math.max(0, Math.min(100, v));
    }

    private static int rank(String status) {
        return switch (status) {
            case AppConstant.BUILD_PLANNED -> 0;
            case AppConstant.BUILD_UNDER_CONSTRUCTION -> 1;
            case AppConstant.BUILD_COMPLETE -> 2;
            case AppConstant.BUILD_HANDED_OVER -> 3;
            default -> -1;
        };
    }
}
