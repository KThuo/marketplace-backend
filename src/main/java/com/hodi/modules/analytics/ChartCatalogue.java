package com.hodi.modules.analytics;

import com.hodi.enums.AppPermissionEnum;

import java.util.List;
import java.util.Optional;

/**
 * Every chart the platform can draw, declared.
 *
 * <h2>Why a catalogue rather than parameters</h2>
 *
 * <p>{@code ReportCatalogue} exists because no identifier in a reporting query may come from a request: a view
 * name, a column name or a GROUP BY target cannot be a bind parameter, so anything spliced into that SQL has
 * to be a literal from a file somebody can read. This is the same rule for charts, and it is a separate
 * catalogue rather than an extension because a report is a table somebody exports and a chart is a shape
 * somebody reads — they need different fields, and merging them would mean half of each record being null.
 *
 * <p>A request carries a chart {@code key} and nothing else. The key selects a row here or it is refused;
 * there is no path by which a caller names a view.
 *
 * <h2>Every chart declares its scope column</h2>
 *
 * <p>{@link Chart#scopeColumns} is what {@code TenantScope.sqlPredicate} is spliced onto. A chart that cannot
 * name one cannot be scoped, and an aggregate that cannot be scoped is where one organisation reads another's
 * figures. Two columns where a thing may be owned by a lender rather than a seller — a bank's development is
 * not a tenant's, and a chart that only understood tenants would show a lender an empty page.
 */
public final class ChartCatalogue {

    private ChartCatalogue() {}

    /** How a chart is meant to be drawn. The client picks a renderer from this, not from the data's shape. */
    public enum Kind {
        /** A trend over time. Lines carry a dash pattern and a symbol as well as a colour. */
        LINE,
        /** Comparison across categories, stacked where the series are parts of one whole. */
        BAR,
        STACKED_BAR,
        /** Composition of a single total. Labels sit outside, because a legend alone fails on colour. */
        DONUT
    }

    /**
     * @param key           what a request names. The only thing a caller supplies
     * @param title         the heading, and part of the chart's accessible name
     * @param description   what the chart is actually measuring, for the screen and for whoever maintains it
     * @param view          the view it reads. A literal, never request input
     * @param kind          how to draw it
     * @param scopeColumns  the columns TenantScope is spliced onto. Never empty
     * @param permission    who may see it at all
     * @param valueLabel    what the numbers are — "listings", "KES". Used in the summary sentence
     * @param money         whether values are currency, which changes formatting and the summary's wording
     */
    public record Chart(
            String key,
            String title,
            String description,
            String view,
            Kind kind,
            List<String> scopeColumns,
            AppPermissionEnum permission,
            String valueLabel,
            boolean money) {}

    /*
     * The two that ship over data which already exists, and the four that follow the developments work.
     *
     * Deliberately small. A catalogue of thirty charts nobody has looked at is a maintenance burden pretending
     * to be a feature; these are the questions somebody actually opens a dashboard to answer.
     */
    private static final List<Chart> CHARTS = List.of(
            new Chart("listings-by-month", "Listings published",
                    "How many listings went live each month, and what became of them.",
                    "v_chart_listings_by_month", Kind.STACKED_BAR,
                    List.of("tenant_id"), AppPermissionEnum.REPORTS_VIEW, "listings", false),

            new Chart("listings-by-type", "What is on the marketplace",
                    "Live listings by kind of property.",
                    "v_chart_listings_by_type", Kind.DONUT,
                    List.of("tenant_id"), AppPermissionEnum.REPORTS_VIEW, "listings", false),

            new Chart("leads-by-month", "Enquiries and viewings",
                    "Enquiries, viewings and offers received each month.",
                    "v_chart_leads_by_month", Kind.LINE,
                    List.of("tenant_id"), AppPermissionEnum.REPORTS_VIEW, "leads", false),

            new Chart("unit-inventory", "Unit inventory",
                    "How much of each development is still available.",
                    "v_chart_unit_inventory", Kind.STACKED_BAR,
                    List.of("tenant_id", "institution_id"), AppPermissionEnum.DEVELOPMENTS_VIEW,
                    "units", false),

            new Chart("bookings-by-state", "Where bookings stand",
                    "Reserved, agreed, completed — and the ones that lapsed or were cancelled.",
                    "v_chart_bookings_by_state", Kind.DONUT,
                    List.of("tenant_id", "institution_id"), AppPermissionEnum.BOOKINGS_VIEW,
                    "bookings", false),

            new Chart("payments-by-month", "Money received",
                    "Payments recorded against bookings each month, net of reversals.",
                    "v_chart_payments_by_month", Kind.LINE,
                    List.of("tenant_id", "institution_id"), AppPermissionEnum.BOOKINGS_VIEW,
                    "KES", true));

    public static List<Chart> all() {
        return CHARTS;
    }

    /**
     * The chart a key names, if it names one.
     *
     * <p>An unknown key is empty rather than an exception: the caller turns it into a 404, and a catalogue
     * lookup is not the place to decide what a missing chart means.
     */
    public static Optional<Chart> byKey(String key) {
        if (key == null || key.isBlank()) return Optional.empty();
        String wanted = key.trim();
        return CHARTS.stream().filter(c -> c.key().equals(wanted)).findFirst();
    }
}
