package com.hodi.modules.analytics;

import com.hodi.modules.analytics.ChartCatalogue.Chart;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalogue's own contract, checked without a database.
 *
 * <p>These are the invariants that make the charting SQL safe to splice, and every one of them fails silently
 * if broken: a chart with no scope column runs unscoped and shows one organisation another's totals, and a
 * view name with a quote in it is an injection point in a query that cannot use bind parameters. None of that
 * throws — it just returns the wrong numbers to the wrong person.
 */
class ChartCatalogueTest {

    @Test
    @DisplayName("every chart declares at least one column to scope on")
    void everyChartCanBeScoped() {
        for (Chart chart : ChartCatalogue.all()) {
            assertFalse(chart.scopeColumns().isEmpty(),
                    chart.key() + " declares no scope column, so it would run unscoped — which is one "
                            + "organisation reading another's totals");
        }
    }

    @Test
    @DisplayName("keys are unique, or one chart shadows another")
    void keysAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Chart chart : ChartCatalogue.all()) {
            assertTrue(seen.add(chart.key()), "duplicate key: " + chart.key());
        }
    }

    @Test
    @DisplayName("nothing spliced into SQL can carry anything but a plain identifier")
    void identifiersAreSafeToSplice() {
        /*
         * A view name and a column name go into the query as literals, because neither can be a bind
         * parameter. They come from this file rather than from a request, which is the actual defence — this
         * asserts the file itself cannot introduce one, so a future entry with a quote or a semicolon in it
         * fails here rather than in production.
         */
        for (Chart chart : ChartCatalogue.all()) {
            assertTrue(chart.view().matches("[a-z_][a-z0-9_]*"),
                    chart.key() + " has a view name that is not a plain identifier: " + chart.view());
            for (String column : chart.scopeColumns()) {
                assertTrue(column.matches("[a-z_][a-z0-9_]*"),
                        chart.key() + " has a scope column that is not a plain identifier: " + column);
            }
        }
    }

    @Test
    @DisplayName("every chart is behind a permission and says what its numbers are")
    void everyChartIsDescribed() {
        for (Chart chart : ChartCatalogue.all()) {
            assertTrue(chart.permission() != null, chart.key() + " is behind no permission");
            assertFalse(chart.title().isBlank(), chart.key() + " has no title");
            assertFalse(chart.description().isBlank(), chart.key() + " has no description");
            // The summary sentence reads "1,240 listings" or "KES 4,300,000"; without a unit it reads "1,240".
            assertFalse(chart.valueLabel().isBlank(),
                    chart.key() + " has no value label, so its summary sentence has no units");
        }
    }
}
