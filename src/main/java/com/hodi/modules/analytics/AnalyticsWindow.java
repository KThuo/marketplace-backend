package com.hodi.modules.analytics;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hodi.common.exception.HodiException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The stretch of months every panel on the analytics page reads.
 *
 * <p><b>Global to the page, unlike the dashboard's periods.</b> The dashboard's cards each own their own —
 * <i>overall</i>, <i>a month</i> and <i>a year</i> are different questions, and stepping one should not drag
 * the others. Analytics is the opposite case: every panel answers the same question about the same stretch of
 * time, and the point of the page is reading them against each other. A composition for August beside a
 * trend for the year and a development table for the quarter is three panels nobody can add up.
 *
 * <p>Inclusive at both ends, because that is how a person says it: "May to August" is four months.
 *
 * @param fromMonth 1–12, as a person would say it
 */
public record AnalyticsWindow(int fromYear, int fromMonth, int toYear, int toMonth) {

    /** Enough of a window to be a trend, and not so much that the x-axis is unreadable. */
    static final int MAX_MONTHS = 60;

    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);

    public AnalyticsWindow {
        check("fromYear", fromYear, "fromMonth", fromMonth);
        check("toYear", toYear, "toMonth", toMonth);
    }

    /**
     * The window a request asked for, or the last twelve months ending this one.
     *
     * <p>A window given backwards is turned around rather than refused: somebody who picked the later month
     * first meant the range between them, and an error message would be pedantry.
     */
    public static AnalyticsWindow of(Integer fromYear, Integer fromMonth, Integer toYear, Integer toMonth) {
        check("fromYear", fromYear, "fromMonth", fromMonth);
        check("toYear", toYear, "toMonth", toMonth);

        YearMonth now = YearMonth.now();
        YearMonth to = toYear == null || toMonth == null ? now : YearMonth.of(toYear, toMonth);
        YearMonth from = fromYear == null || fromMonth == null ? to.minusMonths(11) : YearMonth.of(fromYear, fromMonth);

        if (from.isAfter(to)) {
            YearMonth swap = from;
            from = to;
            to = swap;
        }
        if (ChronoUnit.MONTHS.between(from, to) + 1 > MAX_MONTHS) {
            throw new HodiException("That is more than five years of months. Narrow the window.",
                    HttpStatus.BAD_REQUEST);
        }
        return new AnalyticsWindow(from.getYear(), from.getMonthValue(), to.getYear(), to.getMonthValue());
    }

    private static void check(String yearField, Integer year, String monthField, Integer month) {
        if (month != null && (month < 1 || month > 12)) {
            throw new HodiException("A month is 1 to 12 (" + monthField + ").", HttpStatus.BAD_REQUEST);
        }
        if (year != null && (year < 2000 || year > 2100)) {
            throw new HodiException("That is not a year this system has data for (" + yearField + ").",
                    HttpStatus.BAD_REQUEST);
        }
    }

    public YearMonth from() {
        return YearMonth.of(fromYear, fromMonth);
    }

    public YearMonth to() {
        return YearMonth.of(toYear, toMonth);
    }

    /**
     * The window immediately before this one, of the same length.
     *
     * <p>What every delta on the page is measured against. Equal length is the whole of it: a six-month window
     * compared against a twelve-month one would report a collapse in sales that is really just half as many
     * months.
     */
    public AnalyticsWindow previous() {
        int months = months();
        YearMonth end = from().minusMonths(1);
        YearMonth start = end.minusMonths(months - 1L);
        return new AnalyticsWindow(start.getYear(), start.getMonthValue(), end.getYear(), end.getMonthValue());
    }

    /** Months in the window, both ends counted. */
    @JsonProperty("months")
    public int months() {
        return (int) ChronoUnit.MONTHS.between(from(), to()) + 1;
    }

    /** Every month in the window, in order — so a trend has a point for a quiet month rather than a gap. */
    public List<YearMonth> eachMonth() {
        List<YearMonth> out = new ArrayList<>(months());
        for (YearMonth m = from(); !m.isAfter(to()); m = m.plusMonths(1)) out.add(m);
        return out;
    }

    /** The first day of the window, for the tables dated by an event. */
    public LocalDate startDate() {
        return from().atDay(1);
    }

    /** The day <b>after</b> the window, so a range test never has to know a month's length. */
    public LocalDate endDateExclusive() {
        return to().plusMonths(1).atDay(1);
    }

    /** "May 2026 – August 2026", or "August 2026" when the window is one month. */
    @JsonProperty("label")
    public String label() {
        return from().equals(to()) ? LABEL.format(to()) : LABEL.format(from()) + " – " + LABEL.format(to());
    }

    /** "Aug 2026" — an axis label. */
    public static String shortLabel(YearMonth month) {
        return SHORT.format(month);
    }
}
