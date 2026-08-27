package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.modules.developments.InventoryMaths.Derived;
import com.hodi.modules.developments.InventoryMaths.PhaseFigures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The rules behind a development's percentage and build status.
 *
 * <p>These are the only figures on a development that nobody types, and eleven cached columns are written from
 * them. A weighted average that quietly ignores a null weight is not a wrong number that looks wrong — it is a
 * plausible number that is wrong, reported to a bank about a project it financed. So every basis is pinned
 * here, including the ones that must *not* be chosen.
 *
 * <p>No Spring context and no database: the arithmetic lives in its own class precisely so it can be proved
 * without either, the same reasoning CacheRegionsTest gives for reflecting over constants instead of standing
 * an application up.
 */
class InventoryMathsTest {

    private static PhaseFigures phase(int percent, Integer weight, String budget, Integer units) {
        return new PhaseFigures((short) percent,
                weight == null ? null : weight.shortValue(),
                budget == null ? null : new BigDecimal(budget),
                units);
    }

    @Nested
    @DisplayName("which rule is chosen")
    class BasisChoice {

        @Test
        @DisplayName("no phases at all: the stated figure stands, and says it was stated")
        void noPhases() {
            Derived d = InventoryMaths.derivePercent(List.of(), (short) 42);
            assertEquals(42, d.percent(), "a one-line project must not be dragged to zero");
            assertEquals(AppConstant.PERCENT_BASIS_STATED, d.basis());
        }

        @Test
        @DisplayName("weights present and summing to 100: WEIGHT wins over budget and units")
        void weightsWin() {
            // Every phase also carries a budget and a unit count, so this proves precedence, not just the maths.
            Derived d = InventoryMaths.derivePercent(List.of(
                    phase(100, 25, "1000000", 10),
                    phase(0, 75, "9000000", 90)), (short) 0);
            assertEquals(AppConstant.PERCENT_BASIS_WEIGHT, d.basis());
            assertEquals(25, d.percent(), "100% of a quarter of the project");
        }

        @Test
        @DisplayName("weights that do not sum to 100 are refused, not normalised")
        void weightsNotSummingTo100() {
            // 30 + 30 = 60. Scaling that to look like a whole would turn somebody's mistake into a number
            // nobody questions, so it falls through to the next rule instead.
            Derived d = InventoryMaths.derivePercent(List.of(
                    phase(100, 30, "1000000", null),
                    phase(0, 30, "1000000", null)), (short) 0);
            assertEquals(AppConstant.PERCENT_BASIS_BUDGET, d.basis(), "should fall through to budget");
            assertEquals(50, d.percent(), "equal budgets, so 100 and 0 average to 50");
        }

        @Test
        @DisplayName("one phase missing a weight disqualifies the whole weighted rule")
        void oneMissingWeight() {
            Derived d = InventoryMaths.derivePercent(List.of(
                    phase(100, 50, null, 10),
                    phase(0, null, null, 10)), (short) 0);
            assertEquals(AppConstant.PERCENT_BASIS_UNITS, d.basis(),
                    "no budgets, so units is the next complete rule");
        }

        @Test
        @DisplayName("budgets present: BUDGET wins over units")
        void budgetsWin() {
            Derived d = InventoryMaths.derivePercent(List.of(
                    phase(100, null, "3000000", 90),
                    phase(0, null, "1000000", 10)), (short) 0);
            assertEquals(AppConstant.PERCENT_BASIS_BUDGET, d.basis());
            assertEquals(75, d.percent(), "three quarters of the money is finished");
        }

        @Test
        @DisplayName("a zero budget is not a budget")
        void zeroBudgetFallsThrough() {
            Derived d = InventoryMaths.derivePercent(List.of(
                    phase(100, null, "0", 1),
                    phase(0, null, "1000000", 1)), (short) 0);
            assertEquals(AppConstant.PERCENT_BASIS_UNITS, d.basis());
        }

        @Test
        @DisplayName("nothing complete anywhere: a plain mean, and the basis admits it")
        void equalFallback() {
            Derived d = InventoryMaths.derivePercent(List.of(
                    phase(90, null, null, null),
                    phase(60, null, null, null),
                    phase(0, null, null, null)), (short) 0);
            assertEquals(AppConstant.PERCENT_BASIS_EQUAL, d.basis());
            assertEquals(50, d.percent());
        }
    }

    @Nested
    @DisplayName("the arithmetic itself")
    class Arithmetic {

        @Test
        @DisplayName("a realistic four-phase build, weighted by budget")
        void realisticBuild() {
            Derived d = InventoryMaths.derivePercent(List.of(
                    phase(100, null, "20000000", null),   // foundation, done
                    phase(100, null, "45000000", null),   // superstructure, done
                    phase(40,  null, "25000000", null),   // finishes, part way
                    phase(0,   null, "10000000", null)),  // handover, not started
                    (short) 0);
            // (20 + 45 + 10)/100 of the money = 75m of 100m
            assertEquals(75, d.percent());
            assertEquals(AppConstant.PERCENT_BASIS_BUDGET, d.basis());
        }

        @Test
        @DisplayName("rounds to whole percent, half up")
        void rounding() {
            // Two equal phases at 33 and 34 average to 33.5
            assertEquals(34, InventoryMaths.derivePercent(List.of(
                    phase(33, null, null, null), phase(34, null, null, null)), (short) 0).percent());
        }

        @Test
        @DisplayName("never leaves 0..100, whatever the weights do")
        void clamped() {
            Derived d = InventoryMaths.derivePercent(List.of(phase(100, 100, null, null)), (short) 0);
            assertEquals(100, d.percent());
            assertEquals(0, InventoryMaths.derivePercent(
                    List.of(phase(0, 100, null, null)), (short) 0).percent());
        }
    }

    @Nested
    @DisplayName("build status roll-up")
    class Status {

        @Test
        @DisplayName("a typology is as far along as its furthest-behind unit")
        void leastAdvancedWins() {
            assertEquals(AppConstant.BUILD_PLANNED, InventoryMaths.leastAdvanced(List.of(
                    AppConstant.BUILD_HANDED_OVER, AppConstant.BUILD_PLANNED, AppConstant.BUILD_COMPLETE)));
        }

        @Test
        @DisplayName("ordered by the build sequence, not alphabetically")
        void notAlphabetical() {
            // COMPLETE < PLANNED as strings, which would call a finished typology unstarted.
            assertEquals(AppConstant.BUILD_COMPLETE, InventoryMaths.leastAdvanced(List.of(
                    AppConstant.BUILD_COMPLETE, AppConstant.BUILD_HANDED_OVER)));
        }

        @Test
        @DisplayName("no units yet is PLANNED, not an error")
        void empty() {
            assertEquals(AppConstant.BUILD_PLANNED, InventoryMaths.leastAdvanced(List.of()));
            assertEquals(AppConstant.BUILD_PLANNED, InventoryMaths.leastAdvanced(null));
        }

        @Test
        @DisplayName("an unrecognised status is ignored rather than ranked first")
        void unknownIgnored() {
            assertEquals(AppConstant.BUILD_COMPLETE,
                    InventoryMaths.leastAdvanced(java.util.Arrays.asList("NONSENSE", AppConstant.BUILD_COMPLETE)));
        }

        @Test
        @DisplayName("everything handed over beats a 100% reading")
        void handoverWins() {
            assertEquals(AppConstant.BUILD_HANDED_OVER,
                    InventoryMaths.rollUpStatus((short) 100, AppConstant.BUILD_HANDED_OVER));
            // and even when the phases have not caught up
            assertEquals(AppConstant.BUILD_HANDED_OVER,
                    InventoryMaths.rollUpStatus((short) 80, AppConstant.BUILD_HANDED_OVER));
        }

        @Test
        @DisplayName("100% is COMPLETE; nothing started is PLANNED; anything between is under way")
        void theOtherThree() {
            assertEquals(AppConstant.BUILD_COMPLETE,
                    InventoryMaths.rollUpStatus((short) 100, AppConstant.BUILD_COMPLETE));
            assertEquals(AppConstant.BUILD_PLANNED,
                    InventoryMaths.rollUpStatus((short) 0, AppConstant.BUILD_PLANNED));
            assertEquals(AppConstant.BUILD_UNDER_CONSTRUCTION,
                    InventoryMaths.rollUpStatus((short) 1, AppConstant.BUILD_PLANNED));
        }

        @Test
        @DisplayName("0% but a unit is already going up: under way, not planned")
        void zeroPercentButBuilding() {
            assertEquals(AppConstant.BUILD_UNDER_CONSTRUCTION,
                    InventoryMaths.rollUpStatus((short) 0, AppConstant.BUILD_UNDER_CONSTRUCTION));
        }
    }
}
