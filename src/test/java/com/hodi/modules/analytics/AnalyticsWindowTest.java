package com.hodi.modules.analytics;

import com.hodi.common.exception.HodiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The window: what every analytics panel agrees on, so its arithmetic has to be right on its own. */
class AnalyticsWindowTest {

    @Test
    @DisplayName("omitted, it is the last twelve months ending this one")
    void defaultsToTwelveMonths() {
        AnalyticsWindow w = AnalyticsWindow.of(null, null, null, null);
        YearMonth now = YearMonth.now();
        assertEquals(now, w.to());
        assertEquals(now.minusMonths(11), w.from());
        assertEquals(12, w.months());
    }

    @Test
    @DisplayName("given backwards, it is turned around rather than refused")
    void swapsAReversedWindow() {
        AnalyticsWindow w = AnalyticsWindow.of(2026, 8, 2026, 3);
        assertEquals(YearMonth.of(2026, 3), w.from());
        assertEquals(YearMonth.of(2026, 8), w.to());
        assertEquals(6, w.months());
    }

    @Test
    @DisplayName("the previous window is the same length and ends the month before")
    void previousIsAdjacentAndEqual() {
        AnalyticsWindow w = new AnalyticsWindow(2026, 3, 2026, 8);
        AnalyticsWindow p = w.previous();
        assertEquals(YearMonth.of(2025, 9), p.from());
        assertEquals(YearMonth.of(2026, 2), p.to());
        assertEquals(w.months(), p.months());
    }

    @Test
    @DisplayName("the range test needs no month lengths: the end is the first day after")
    void edgesAreHalfOpen() {
        AnalyticsWindow w = new AnalyticsWindow(2026, 2, 2026, 2);
        assertEquals(LocalDate.of(2026, 2, 1), w.startDate());
        assertEquals(LocalDate.of(2026, 3, 1), w.endDateExclusive());
        assertEquals("February 2026", w.label());
        assertEquals(1, w.eachMonth().size());
    }

    @Test
    @DisplayName("a month of 13 and five years of months are both refused as bad requests")
    void refusesNonsense() {
        assertThrows(HodiException.class, () -> AnalyticsWindow.of(2026, 13, 2026, 1));
        assertThrows(HodiException.class, () -> AnalyticsWindow.of(2020, 1, 2026, 1));
        assertThrows(HodiException.class, () -> new AnalyticsWindow(1999, 1, 2026, 1));
    }
}
