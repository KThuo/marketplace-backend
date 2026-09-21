package com.hodi.modules.analytics;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
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

    /**
     * What the schedules say will fall due within a horizon and is not yet covered by what has been paid.
     *
     * <p>Beyond the overdue: a booking that has paid ahead owes nothing in the horizon until its payments run
     * out, and a booking behind counts only the further instalments here — the arrears are in {@code overdue}.
     */
    public record Expected(int days, LocalDate through, BigDecimal amount, int bookings) {}

    public record ReceivablesView(BigDecimal receivable, BigDecimal overdue, int liveBookings,
                                  int overdueBookings, List<AgeBucket> ageing, List<OwingBooking> worst,
                                  List<Expected> expected) {}

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

    // ── collections: how well what is due gets paid ───────────────────────────

    /** One month: what the schedules said was due, and what actually arrived. */
    public record DuePoint(int year, int month, String label, BigDecimal due, BigDecimal collected) {}

    /**
     * How promptly payments met their instalments.
     *
     * <p>Payments are not allocated to instalments by the system, so each one is read against the instalment
     * its running total first reaches — the same reading a person makes of a schedule beside a receipt list. A
     * payment with no instalment to meet (ahead of the whole schedule, or a booking with no plan) is counted
     * in neither column.
     *
     * @param graceDays how many days after the due date still counts as on time
     * @param medianDaysLate among the late ones only; null when none were late
     */
    public record Lateness(int scheduled, int onTime, int late, int graceDays, Integer medianDaysLate,
                           BigDecimal lateAmount) {
        @JsonProperty("onTimeShare")
        public BigDecimal onTimeShare() {
            if (scheduled == 0) return null;
            return BigDecimal.valueOf(onTime).multiply(HUNDRED).divide(BigDecimal.valueOf(scheduled), 1, RoundingMode.HALF_UP);
        }
    }

    /** One channel's take in one month, for the stacked bar. */
    public record ChannelMonth(int year, int month, String label, String channel, BigDecimal amount, int count) {}

    /** Prompts sent in a month, and what became of them. */
    public record PromptPoint(int year, int month, String label, int sent, int paid, int failed, int unanswered) {}

    public record CollectionsView(AnalyticsWindow window, BigDecimal due, BigDecimal collected, Lateness lateness,
                                  List<DuePoint> months, List<ChannelMonth> channels, List<PromptPoint> prompts,
                                  int promptsSent, int promptsPaid, int promptsFailed, int promptsUnanswered) {
        /** Collected as a share of what was due in the window. Null when nothing was due. */
        @JsonProperty("efficiency")
        public BigDecimal efficiency() {
            if (due == null || due.signum() == 0) return null;
            return collected.multiply(HUNDRED).divide(due, 1, RoundingMode.HALF_UP);
        }
        /** Of the prompts that got an answer, how many were paid. Null when none were answered. */
        @JsonProperty("promptSuccessRate")
        public BigDecimal promptSuccessRate() {
            int answered = promptsPaid + promptsFailed;
            if (answered == 0) return null;
            return BigDecimal.valueOf(promptsPaid).multiply(HUNDRED).divide(BigDecimal.valueOf(answered), 1, RoundingMode.HALF_UP);
        }
    }

    // ── the funnel: how many enquiries become sales, and how long ────────────

    /** @param conversion the share of the stage before that reached this one, in percent; null for the first */
    public record Stage(String key, String label, int count, BigDecimal conversion) {}

    /** The typical time between two stages, for the people who made it from one to the other. */
    public record Interval(String key, String label, Integer medianDays, int sample) {}

    public record FunnelView(AnalyticsWindow window, List<Stage> stages, List<Interval> intervals,
                             List<Slice> offersByOutcome, int offersConverted, List<Slice> viewingsByOutcome) {}

    // ── inventory: how fast units sell, and how much is left ─────────────────

    /** One month: units marked sold, and bookings made. */
    public record SoldPoint(int year, int month, String label, int sold, int booked) {}

    /** One kind of home in one development, as the stock stands today. */
    public record StockRow(String developmentId, String developmentName, String unitType, int total, int available,
                           int held, int sold, BigDecimal listPrice, BigDecimal pricePerSqm) {}

    /**
     * @param soldPerMonth the pace over the window
     * @param monthsOfStock what is available, at that pace; null when nothing sold in the window
     */
    public record InventoryView(AnalyticsWindow window, int unitsTotal, int unitsAvailable, int unitsHeld, int unitsSold,
                                int soldInWindow, int bookedInWindow, BigDecimal soldPerMonth, BigDecimal monthsOfStock,
                                List<SoldPoint> months, List<StockRow> rows, int holdsAgreed, int holdsLapsed) {}

    // ── the bank: how its money behaves ──────────────────────────────────────

    /** Statements that arrived in a month, by what became of them. */
    public record StatementPoint(int year, int month, String label, int arrived, BigDecimal amount, int automatic,
                                 int byHand, int setAside, int unplaced) {}

    /** Transfers proposed in a month, and how they ended. */
    public record DisbursementPoint(int year, int month, String label, int count, BigDecimal amount, int succeeded,
                                    int failed, int pending) {}

    /** Money in against money out, month by month. */
    public record FlowPoint(int year, int month, String label, BigDecimal in, BigDecimal out) {}

    /** @param medianMinutesToPlace from a statement's arrival to its placing on a booking; null when none were placed */
    public record BankView(AnalyticsWindow window, int statements, BigDecimal statementsAmount, int automatic, int byHand,
                           int setAside, int unplaced, Integer medianMinutesToPlace, List<StatementPoint> months,
                           List<DisbursementPoint> disbursements, int disbursementsSucceeded, BigDecimal disbursed,
                           List<FlowPoint> flow) {
        /** Of the statements placed, how many the matcher placed without a person. Null when none were placed. */
        @JsonProperty("automaticShare")
        public BigDecimal automaticShare() {
            int placed = automatic + byHand;
            if (placed == 0) return null;
            return BigDecimal.valueOf(automatic).multiply(HUNDRED).divide(BigDecimal.valueOf(placed), 1, RoundingMode.HALF_UP);
        }
    }

    // ── the dashboard: today ──────────────────────────────────────────────────

    /**
     * One thing waiting for a person, and the way to it.
     *
     * <p>Assembled server-side and present only when the caller may act on it, so a line about bank credits
     * never reaches somebody who cannot open the statements. {@code amount} and {@code oldest} are null where
     * the thing has no money or no age worth saying.
     *
     * @param tone {@code warning} for money or time at stake, {@code neutral} for ordinary work
     * @param action the route that clears it
     */
    public record Attention(String key, String title, String detail, int count, BigDecimal amount,
                            OffsetDateTime oldest, String tone, String action) {}

    /** The month's money against the month before: the strip a dashboard opens on. */
    public record MonthFigures(String label, String previousLabel, Figure collected, Figure contracted,
                               Figure spent, Figure drawn, int payments, int bookings, int unitsSold) {}

    /** How many people moved through each stage this month. Counts, not money. */
    public record Funnel(int enquiries, int viewings, int offers, int bookings) {}

    /**
     * The dashboard as one read: who is asking, what needs them, how the month is going, and the shape of the
     * year behind it. Everything here is summed when asked for, through the caller's own scope.
     */
    public record TodayView(String audience, String greeting, MonthFigures month, Positions now,
                            List<Attention> attention, List<TrendPoint> trend, List<Receipt> recent,
                            List<Slice> inventory, Funnel funnel) {}

    static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
