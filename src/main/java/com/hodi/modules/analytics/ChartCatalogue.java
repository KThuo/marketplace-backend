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
 * <p>A request carries a chart {@code key}, and optionally the development to narrow it to, and nothing else.
 * The key selects a row here or it is refused; there is no path by which a caller names a view.
 *
 * <h2>Every chart declares its scope columns</h2>
 *
 * <p>{@link Chart#scopeColumns} is what {@code OwnerScopeSql} is spliced onto. A chart that cannot name one
 * cannot be scoped, and an aggregate that cannot be scoped is where one organisation reads another's figures.
 * Two columns where a thing may be owned by a lender rather than a seller — a bank's development is not a
 * tenant's, and a chart that only understood tenants would show a lender an empty page.
 *
 * <h2>Some charts are about one project</h2>
 *
 * <p>{@link Chart#subjectColumn} names the column a development is filtered on, where the view has one. A
 * chart with {@link Chart#subjectOnly} set makes no sense summed across projects — a percentage complete,
 * for one — and is drawn only with a subject.
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
     * @param key           what a request names. The only thing a caller supplies, beside a subject
     * @param title         the heading, and part of the chart's accessible name
     * @param description   what the chart is actually measuring, for the screen and for whoever maintains it
     * @param view          the view it reads. A literal, never request input
     * @param kind          how to draw it
     * @param scopeColumns  the owner columns the scope predicate is spliced onto. Never empty
     * @param permission    who may see it at all
     * @param valueLabel    what the numbers are — "listings", "KES". Used in the summary sentence
     * @param money         whether values are currency, which changes formatting and the summary's wording
     * @param subjectColumn the column a development is filtered on, or null when the chart has no subject
     * @param subjectOnly   whether the chart is meaningless without a subject
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
            boolean money,
            String subjectColumn,
            boolean subjectOnly) {

        public boolean hasSubject() {
            return subjectColumn != null;
        }
    }

    private static final List<String> TENANT = List.of("tenant_id");
    private static final List<String> OWNER = List.of("tenant_id", "institution_id");

    /*
     * Deliberately small. A catalogue of thirty charts nobody has looked at is a maintenance burden pretending
     * to be a feature; these are the questions somebody actually opens a dashboard to answer.
     */
    private static final List<Chart> CHARTS = List.of(
            new Chart("listings-by-month", "Listings published",
                    "How many listings went live each month, and what became of them.",
                    "v_chart_listings_by_month", Kind.STACKED_BAR,
                    TENANT, AppPermissionEnum.REPORTS_VIEW, "listings", false, null, false),

            new Chart("listings-by-type", "What is on the marketplace",
                    "Live listings by kind of property.",
                    "v_chart_listings_by_type", Kind.DONUT,
                    TENANT, AppPermissionEnum.REPORTS_VIEW, "listings", false, null, false),

            new Chart("leads-by-month", "Enquiries and viewings",
                    "Enquiries, viewings and offers received each month.",
                    "v_chart_leads_by_month", Kind.LINE,
                    TENANT, AppPermissionEnum.REPORTS_VIEW, "leads", false, null, false),

            new Chart("unit-inventory", "Unit inventory",
                    "How much of each development is still available.",
                    "v_chart_unit_inventory", Kind.STACKED_BAR,
                    OWNER, AppPermissionEnum.DEVELOPMENTS_VIEW, "units", false, "development_id", false),

            new Chart("bookings-by-state", "Where bookings stand",
                    "Reserved, agreed, completed — and the ones that lapsed or were cancelled.",
                    "v_chart_bookings_by_state", Kind.DONUT,
                    OWNER, AppPermissionEnum.BOOKINGS_VIEW, "bookings", false, "development_id", false),

            new Chart("payments-by-month", "Money received",
                    "Payments recorded against bookings each month. Voided payments are not counted.",
                    "v_chart_payments_by_month", Kind.LINE,
                    OWNER, AppPermissionEnum.PAYMENTS_VIEW, "KES", true, "development_id", false),

            // ── a development's money ─────────────────────────────────────────

            new Chart("dev-spend", "Spend against plan",
                    "Cumulative planned, committed and spent, month by month. Planned steps up as each phase "
                            + "falls due.",
                    "v_chart_dev_spend", Kind.LINE,
                    OWNER, AppPermissionEnum.DEVELOPMENTS_FINANCE_VIEW, "KES", true, "development_id", false),

            new Chart("dev-funding", "Where the money stands",
                    "What was allowed, what has gone, what the lender put in, and what buyers have paid.",
                    "v_chart_dev_funding", Kind.BAR,
                    OWNER, AppPermissionEnum.DEVELOPMENTS_FINANCE_VIEW, "KES", true, "development_id", false),

            new Chart("dev-completion", "Completion over time",
                    "The percentage complete reported on progress posts, month by month.",
                    "v_chart_dev_completion", Kind.LINE,
                    OWNER, AppPermissionEnum.DEVELOPMENTS_VIEW, "%", false, "development_id", true));

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
