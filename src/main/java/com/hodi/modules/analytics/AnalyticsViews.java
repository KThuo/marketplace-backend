package com.hodi.modules.analytics;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * What the analytics and dashboard endpoints return.
 *
 * <p>Records rather than entities: nothing here is stored. Every figure is a sum over bookings, payments,
 * the cost ledger, the drawdowns and the units at the moment the page asks, so the page cannot disagree with
 * the lists behind it and there is no refresh button.
 */
public final class AnalyticsViews {

    private AnalyticsViews() {}

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * A figure and the same figure for the window before.
     *
     * <p>Whether up is good is not recorded here — contracted up is progress, overdue up is not — and the tile
     * knows which it is showing. This says only how much it moved.
     */
    public record Figure(BigDecimal value, BigDecimal previous) {

        public static Figure of(BigDecimal value, BigDecimal previous) {
            return new Figure(zero(value), zero(previous));
        }

        /**
         * The move, as a percentage of last time. Null when the previous window was empty — going from nothing
         * to something is not an increase of any particular percentage, and "+∞%" on a tile is a bug report.
         */
        @JsonProperty("change")
        public BigDecimal change() {
            if (previous.signum() == 0) return null;
            return value.subtract(previous).multiply(HUNDRED).divide(previous.abs(), 1, RoundingMode.HALF_UP);
        }

        /** The absolute move, which is the honest figure when the percentage is undefined. */
        @JsonProperty("delta")
        public BigDecimal delta() {
            return value.subtract(previous);
        }
    }

    // ── what the queries return ───────────────────────────────────────────────

    /** Money over a window. */
    public record MoneyTotals(BigDecimal contracted, int bookings, BigDecimal collected, int payments,
                              BigDecimal spent, BigDecimal committed, BigDecimal drawn, int unitsSold) {
        /** Collected plus drawn, less spent: whether the window funded itself. */
        public BigDecimal net() {
            return collected.add(drawn).subtract(spent);
        }
    }

    /** Where things stand today. No window: a position is not a flow. */
    public record Positions(BigDecimal receivable, BigDecimal overdue, int liveBookings, int overdueBookings,
                            int unitsTotal, int unitsAvailable, int unitsReserved, int unitsSold,
                            int developments, int developmentsLate, int developmentsOverBudget,
                            BigDecimal budget, BigDecimal spentToDate, BigDecimal facility, BigDecimal drawnToDate) {

        /** Sold and reserved against everything, in percent. Null when there is nothing to sell. */
        @JsonProperty("salesRate")
        public BigDecimal salesRate() {
            if (unitsTotal == 0) return null;
            return BigDecimal.valueOf(unitsSold + unitsReserved).multiply(HUNDRED)
                    .divide(BigDecimal.valueOf(unitsTotal), 1, RoundingMode.HALF_UP);
        }
    }

    /** One month of the trend. */
    public record TrendPoint(int year, int month, String label, BigDecimal contracted, BigDecimal collected,
                             BigDecimal spent, BigDecimal drawn) {}

    /** One part of a whole: a payment type, a cost category, a unit state. */
    public record Slice(String label, BigDecimal amount, int count) {}

    /** Overdue money by how long the oldest unpaid instalment has been due. */
    public record AgeBucket(String label, int fromDays, BigDecimal amount, int bookings) {}

    /** A booking that owes, for the chase list. */
    public record OwingBooking(String id, String reference, String buyerName, String developmentName,
                               String unitLabel, String state, BigDecimal priceAgreed, BigDecimal balance,
                               BigDecimal overdue, LocalDate nextDueOn, Integer daysOverdue) {}

    /** One development, side by side with the others. */
    public record DevelopmentComparison(
            String id, String reference, String name, String ownerName, String constructionStatus,
            short percentComplete, int unitsTotal, int unitsAvailable, int unitsSold,
            BigDecimal contracted, BigDecimal collected, BigDecimal collectedInWindow, BigDecimal receivable,
            BigDecimal overdue, BigDecimal budget, BigDecimal committed, BigDecimal spent, BigDecimal drawn,
            BigDecimal facility, int phasesLate, LocalDate targetOn, LocalDate forecastOn) {

        @JsonProperty("overBudget")
        public boolean overBudget() {
            return budget != null && budget.signum() > 0 && spent.compareTo(budget) > 0;
        }

        @JsonProperty("slippageDays")
        public Long slippageDays() {
            if (targetOn == null || forecastOn == null) return null;
            return forecastOn.toEpochDay() - targetOn.toEpochDay();
        }
    }

    /** A development whose collections moved most against the window before. */
    public record Mover(String id, String name, BigDecimal collected, BigDecimal previous) {
        @JsonProperty("delta")
        public BigDecimal delta() {
            return collected.subtract(previous);
        }
    }

    /** Leads over the window. These sit on listings, which have a seller and no development. */
    public record PipelineStats(int enquiries, int enquiriesAwaitingSeller, int visits, List<Slice> visitsByState,
                                int offers, BigDecimal offersAmount, List<Slice> offersByState) {}

    // ── the analytics endpoints ───────────────────────────────────────────────

    /** The KPI strip: the window against the window before, plus the position today. */
    public record SummaryView(AnalyticsWindow window, AnalyticsWindow previous,
                              Figure contracted, Figure collected, Figure spent, Figure drawn, Figure net,
                              int bookings, int payments, int unitsSold, Positions now,
                              /** The window's months, for the sparklines. */
                              List<TrendPoint> months) {}

    public record TrendView(AnalyticsWindow window, List<TrendPoint> points) {}

    public record CompositionView(AnalyticsWindow window, List<Slice> collectionsByType,
                                  List<Slice> spendByCategory, List<Slice> unitsByState) {}

    public record ReceivablesView(BigDecimal receivable, BigDecimal overdue, int liveBookings,
                                  int overdueBookings, List<AgeBucket> ageing, List<OwingBooking> worst) {}

    public record DevelopmentsView(AnalyticsWindow window, List<DevelopmentComparison> rows, boolean capped,
                                   int units, int unitsSold, BigDecimal contracted, BigDecimal collected,
                                   BigDecimal receivable, BigDecimal budget, BigDecimal spent, BigDecimal drawn,
                                   List<Mover> movers) {}

    public record PipelineView(AnalyticsWindow window, PipelineStats stats) {}

    // ── the dashboard's cards ─────────────────────────────────────────────────

    /** <b>Overall.</b> Everything, or one year of it. */
    public record OverallView(String label, MoneyTotals totals, Positions now) {}

    /** One receipt, on the month's collections table. */
    public record Receipt(String id, String reference, LocalDate paidOn, String payerName, String buyerName,
                             String developmentName, String unitLabel, String paymentType, BigDecimal amount,
                             String currency) {}

    public record CollectionsPage(List<Receipt> content, int page, int size, long totalElements) {}

    /** <b>The month.</b> Its money, its sales rate, its receipts. */
    public record MonthlyView(int year, int month, String label, MoneyTotals totals, Positions now,
                              CollectionsPage collections) {}

    public record MonthlyCollection(int month, String label, BigDecimal collected, int payments, BigDecimal spent) {}

    /** <b>The calendar.</b> Twelve months of receipts, including the empty ones. */
    public record CalendarView(int year, List<MonthlyCollection> months) {}

    static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
