package com.hodi.modules.reports;

import java.util.List;
import java.util.Map;

/**
 * What can be reported on (M15).
 *
 * <p>A declared catalogue rather than a query builder. A report is a view, a date column, a scoping column
 * and a handful of columns to show — and stating those four things per report is shorter, safer and far
 * easier to read than a generic engine that can express any query including the ones nobody should run.
 *
 * <p><strong>Nothing here is built from request input.</strong> The view name, the date column and every
 * column name come from this file; the request chooses a report by code and supplies dates and a page.
 * That is what makes the native SQL below safe to assemble at all.
 */
public final class ReportCatalogue {

    private ReportCatalogue() {}

    /**
     * @param code       what a request asks for
     * @param view       the reporting view, from this file and never from a request
     * @param dateColumn what the window filters on — every report answers "between these dates"
     * @param columns    display name → column, in the order they appear
     * @param numeric    the columns that get a total under the table
     */
    public record Report(String code, String name, String description, String view,
                         String dateColumn, Map<String, String> columns, List<String> numeric,
                         boolean platformOnly, List<Filter> filters,
                         /**
                          * Whether the view's rows may be owned by a lending institution as well as a tenant.
                          *
                          * <p>Such a view carries {@code tenant_id}, {@code institution_id} and
                          * {@code development_id}, and is scoped by {@code OwnerScopeSql} rather than the
                          * tenant predicate — otherwise a bank running the report on its own financed
                          * projects would see nothing at all.
                          */
                         boolean ownerScoped) {

        /** The tenant-scoped shape every report had before developments could be owned by a lender. */
        public Report(String code, String name, String description, String view, String dateColumn,
                      Map<String, String> columns, List<String> numeric, boolean platformOnly,
                      List<Filter> filters) {
            this(code, name, description, view, dateColumn, columns, numeric, platformOnly, filters, false);
        }

        /** The columns a free-text search looks at: everything that is neither a figure nor a timestamp. */
        public List<String> searchable() {
            return columns().values().stream()
                    .filter(c -> !numeric().contains(c))
                    .filter(c -> !c.endsWith("_at") && !c.equals(dateColumn()))
                    .toList();
        }

        /** True when this report declares that column. The gate every request-named column passes. */
        public boolean declares(String column) {
            return columns().containsValue(column);
        }

        public Filter filter(String column) {
            return filters().stream().filter(f -> f.column().equals(column)).findFirst().orElse(null);
        }
    }

    /**
     * A filter a report offers.
     *
     * <p>Declared per report because "the necessary filters" are not the same question twice: a listings
     * report wants state, county and kind; a commission report wants how far a settlement has got. A
     * generic "filter any column" would be both useless — sixty selects — and the injection surface this
     * catalogue exists to avoid.
     *
     * @param column the column to filter, which must be one this report already declares
     * @param kind   how the client should render it, and how the value is bound
     */
    public record Filter(String label, String column, Kind kind) {
        public enum Kind {
            /** A select, its options read from the distinct values present in the caller's own scope. */
            ENUM,
            /** A yes/no select. */
            BOOLEAN,
        }
    }

    /** Insertion-ordered, because it is also the order the picker shows them in. */
    private static final List<Report> REPORTS = List.of(
            new Report("LISTINGS", "Listings",
                    "Every listing, what it is, and what became of it.",
                    "v_report_listings", "drafted_at",
                    ordered("Reference", "reference", "Listing", "title", "Seller", "tenant_name",
                            "Kind", "property_type", "County", "county", "Town", "town",
                            "Price", "price", "State", "listing_state", "Promoted", "promoted",
                            "Drafted", "drafted_at", "Published", "published_at", "Sold", "sold_at",
                            "Days to sell", "days_to_sell"),
                    List.of("price"), false,
                    List.of(new Filter("State", "listing_state", Filter.Kind.ENUM),
                            new Filter("County", "county", Filter.Kind.ENUM),
                            new Filter("Kind", "property_type", Filter.Kind.ENUM),
                            new Filter("Seller", "tenant_name", Filter.Kind.ENUM),
                            new Filter("Promoted", "promoted", Filter.Kind.BOOLEAN))),

            new Report("LEADS", "Leads",
                    "Enquiries, viewing requests and offers, in one list.",
                    "v_report_leads", "created_at",
                    ordered("Kind", "lead_type", "Reference", "reference", "Seller", "tenant_name",
                            "Listing", "property_title", "State", "state",
                            "Handled by", "handled_by", "Raised", "created_at"),
                    List.of(), false,
                    List.of(new Filter("Kind", "lead_type", Filter.Kind.ENUM),
                            new Filter("State", "state", Filter.Kind.ENUM),
                            new Filter("Seller", "tenant_name", Filter.Kind.ENUM))),

            new Report("COMMISSION", "Commission",
                    "What the platform earned on completed sales, and where each figure stands.",
                    "v_report_commission", "sold_at",
                    ordered("Reference", "reference", "Seller", "tenant_name",
                            "Listing", "property_title", "Sale price", "sale_price",
                            "Rate %", "rate_percent", "Commission", "amount",
                            "State", "state", "Sold", "sold_at", "Invoiced", "invoiced_at",
                            "Paid", "paid_at"),
                    List.of("sale_price", "amount"), false,
                    List.of(new Filter("State", "state", Filter.Kind.ENUM),
                            new Filter("Seller", "tenant_name", Filter.Kind.ENUM))),

            new Report("PROMOTIONS", "Placements",
                    "Paid placement bought, and whether it ran.",
                    "v_report_promotions", "created_at",
                    ordered("Reference", "reference", "Seller", "tenant_name",
                            "Listing", "property_title", "Package", "package_name",
                            "Placement", "placement", "Price", "price", "Days", "duration_days",
                            "State", "state", "Started", "starts_at", "Ends", "ends_at"),
                    List.of("price"), false,
                    List.of(new Filter("State", "state", Filter.Kind.ENUM),
                            new Filter("Seller", "tenant_name", Filter.Kind.ENUM))),

            new Report("VALUATIONS", "Valuations",
                    "Turnaround from request to signed report.",
                    "v_report_valuations", "requested_at",
                    ordered("Reference", "reference", "Listing", "property_title",
                            "Valuer", "valuer_name", "State", "state",
                            "Requested", "requested_at", "Assigned", "assigned_at",
                            "Completed", "completed_at", "Days taken", "days_to_complete"),
                    List.of(), false,
                    List.of(new Filter("State", "state", Filter.Kind.ENUM),
                            new Filter("Valuer", "valuer_name", Filter.Kind.ENUM))),

            new Report("AUCTIONS", "Auctions",
                    "What went under the hammer, and what it fetched against the guide.",
                    "v_report_auctions", "auction_date",
                    ordered("Reference", "reference", "Lot", "title", "Brought by", "tenant_name",
                            "County", "county", "Auctioneer", "auctioneer_name",
                            "Guide", "guide_price", "Fetched", "sold_price",
                            "% of guide", "percent_of_guide", "State", "state",
                            "Auction", "auction_date"),
                    List.of("guide_price", "sold_price"), false,
                    List.of(new Filter("State", "state", Filter.Kind.ENUM),
                            new Filter("County", "county", Filter.Kind.ENUM))),

            new Report("COMPLIANCE", "Compliance",
                    "Where every organisation stands with Compliance, and what they have live.",
                    "v_report_compliance", "onboarded_at",
                    ordered("Organisation", "tenant_name", "Kind", "organisation_kind",
                            "Seller type", "seller_type", "Standing", "onboarding_status",
                            "Cleared", "cleared_people", "Waiting", "waiting_people",
                            "Live listings", "live_listings", "Onboarded", "onboarded_at"),
                    List.of("live_listings"),
                    // Every organisation's compliance standing beside every other's is the platform's view
                    // of its own market, and nobody else's business.
                    true,
                    List.of(new Filter("Standing", "onboarding_status", Filter.Kind.ENUM),
                            new Filter("Kind", "organisation_kind", Filter.Kind.ENUM),
                            new Filter("Seller type", "seller_type", Filter.Kind.ENUM))),

            new Report("DEVELOPMENT_FINANCE", "Development finance",
                    "Every development's budget, spend, facility and sales, one row each.",
                    "v_development_finance", "created_at",
                    ordered("Reference", "reference", "Development", "development_name",
                            "Organisation", "owner_name", "Build", "construction_status",
                            "Complete %", "percent_complete", "Units", "units_total", "Sold", "units_sold",
                            "Contracted", "contracted", "Collected", "collected", "Receivable", "receivable",
                            "Overdue", "overdue", "Budget", "budget_amount", "Planned to date", "planned_to_date",
                            "Committed", "committed", "Spent", "spent", "Facility", "facility_amount",
                            "Drawn", "drawn", "Target", "projected_completion_on", "Forecast", "forecast_on",
                            "Phases late", "phases_late", "Set up", "created_at"),
                    List.of("percent_complete", "units_total", "units_sold", "contracted", "collected",
                            "receivable", "overdue", "budget_amount", "planned_to_date", "committed", "spent",
                            "facility_amount", "drawn", "phases_late"),
                    false,
                    List.of(new Filter("Organisation", "owner_name", Filter.Kind.ENUM),
                            new Filter("Build", "construction_status", Filter.Kind.ENUM)),
                    true),

            new Report("RATINGS", "Reviews",
                    "What buyers said, by subject.",
                    "v_report_ratings", "created_at",
                    ordered("Reference", "reference", "About", "subject_label",
                            "Kind", "subject_type", "Score", "score", "Verified", "verified",
                            "State", "state", "Reports", "report_count", "Written", "created_at"),
                    List.of(), false,
                    List.of(new Filter("State", "state", Filter.Kind.ENUM),
                            new Filter("Kind", "subject_type", Filter.Kind.ENUM),
                            new Filter("Verified", "verified", Filter.Kind.BOOLEAN))));

    public static List<Report> all() {
        return REPORTS;
    }

    public static Report byCode(String code) {
        return REPORTS.stream()
                .filter(r -> r.code().equalsIgnoreCase(code == null ? "" : code.trim()))
                .findFirst()
                .orElse(null);
    }

    /** Pairs of (display name, column), kept in order. */
    private static Map<String, String> ordered(String... pairs) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]);
        return java.util.Collections.unmodifiableMap(out);
    }
}
