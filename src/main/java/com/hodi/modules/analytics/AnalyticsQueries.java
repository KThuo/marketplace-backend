package com.hodi.modules.analytics;

import com.hodi.modules.analytics.AnalyticsViews.*;
import com.hodi.security.OwnerScopeSql;
import com.hodi.security.hashid.HashIdUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * Every sum the dashboard and the analytics page draw.
 *
 * <h2>Scope becomes SQL in exactly one place</h2>
 *
 * <p>Every query here starts from {@link OwnerScopeSql#predicate}. The failure this prevents has already
 * happened once in this codebase: a chart that spliced the caller's <i>tenant</i> ids into an
 * {@code institution_id} predicate, which was wrong for every lender. A total that reaches past the caller's
 * scope leaks the shape of another organisation's business without ever showing a row — and silently, because
 * a wrong total looks exactly like a right one.
 *
 * <h2>Nothing from a request is spliced</h2>
 *
 * <p>The scope predicate contains only ids the server resolved for the signed-in caller. Every window edge,
 * every development filter and every page size is a bound parameter.
 *
 * <h2>Two clocks</h2>
 *
 * <p>A booking is counted in the month it was <i>booked</i>, a payment in the month it was <i>paid</i>, a cost
 * in the month it was <i>incurred</i>, a drawdown in the month it was <i>drawn</i>. Receivable, overdue and the
 * unit tallies have no month: they are where things stand today.
 */
@Repository
@RequiredArgsConstructor
public class AnalyticsQueries {

    private final JdbcTemplate jdbc;

    /** A query under construction: SQL and the parameters bound to its placeholders, in order. */
    private static final class Q {
        final StringBuilder sql = new StringBuilder();
        final List<Object> params = new ArrayList<>();

        Q add(String fragment, Object... values) {
            sql.append(fragment);
            params.addAll(Arrays.asList(values));
            return this;
        }
    }

    private static String scope(String alias) {
        return OwnerScopeSql.predicate(alias + ".tenant_id", alias + ".institution_id", alias + ".development_id");
    }

    /** Developments carry their own ids; units and phases reach theirs through the join. */
    private static String scopeDevelopments(String alias) {
        return OwnerScopeSql.predicate(alias + ".tenant_id", alias + ".institution_id", alias + ".id");
    }

    private static void window(Q q, String column, AnalyticsWindow window) {
        q.add(" AND " + column + " >= ? AND " + column + " < ?", window.startDate(), window.endDateExclusive());
    }

    private static void year(Q q, String column, Integer year) {
        if (year == null) return;
        q.add(" AND " + column + " >= ? AND " + column + " < ?", LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1));
    }

    private static void development(Q q, String column, Long developmentId) {
        if (developmentId != null) q.add(" AND " + column + " = ?", developmentId);
    }

    // ── money over a window ───────────────────────────────────────────────────

    public MoneyTotals totals(AnalyticsWindow window, Long developmentId) {
        return totals(window, null, developmentId);
    }

    /** All time when both are null; a year; or a window. */
    public MoneyTotals totals(AnalyticsWindow window, Integer year, Long developmentId) {
        Q q = new Q();
        q.add("SELECT");
        q.add(" (SELECT coalesce(sum(b.price_agreed), 0) FROM unit_bookings b WHERE " + scope("b")
                + " AND b.status <> 5 AND b.state IN ('RESERVED','AGREED','COMPLETED')");
        period(q, "b.booked_on", window, year); development(q, "b.development_id", developmentId);
        q.add(") AS contracted,");
        q.add(" (SELECT count(*) FROM unit_bookings b WHERE " + scope("b")
                + " AND b.status <> 5 AND b.state IN ('RESERVED','AGREED','COMPLETED')");
        period(q, "b.booked_on", window, year); development(q, "b.development_id", developmentId);
        q.add(") AS bookings,");
        q.add(" (SELECT coalesce(sum(p.amount), 0) FROM payments p WHERE " + scope("p") + " AND p.status = 1");
        period(q, "p.paid_on", window, year); development(q, "p.development_id", developmentId);
        q.add(") AS collected,");
        q.add(" (SELECT count(*) FROM payments p WHERE " + scope("p") + " AND p.status = 1");
        period(q, "p.paid_on", window, year); development(q, "p.development_id", developmentId);
        q.add(") AS payments,");
        q.add(" (SELECT coalesce(sum(e.amount), 0) FROM development_expenditures e WHERE " + scope("e")
                + " AND e.status = 1 AND e.kind = 'SPENT'");
        period(q, "e.incurred_on", window, year); development(q, "e.development_id", developmentId);
        q.add(") AS spent,");
        q.add(" (SELECT coalesce(sum(e.amount), 0) FROM development_expenditures e WHERE " + scope("e")
                + " AND e.status = 1 AND e.kind = 'COMMITTED'");
        period(q, "e.incurred_on", window, year); development(q, "e.development_id", developmentId);
        q.add(") AS committed,");
        q.add(" (SELECT coalesce(sum(f.amount), 0) FROM facility_drawdowns f WHERE " + scope("f") + " AND f.status = 1");
        period(q, "f.drawn_on", window, year); development(q, "f.development_id", developmentId);
        q.add(") AS drawn,");
        q.add(" (SELECT count(*) FROM properties u JOIN developments d ON d.id = u.development_id WHERE "
                + scopeDevelopments("d") + " AND u.listing_kind = 'UNIT' AND u.status <> 5 AND d.status <> 5"
                + " AND u.sale_state = 'SOLD'");
        period(q, "u.sold_at", window, year); development(q, "d.id", developmentId);
        q.add(") AS units_sold");

        return jdbc.queryForObject(q.sql.toString(), (rs, i) -> new MoneyTotals(
                money(rs, "contracted"), rs.getInt("bookings"), money(rs, "collected"), rs.getInt("payments"),
                money(rs, "spent"), money(rs, "committed"), money(rs, "drawn"), rs.getInt("units_sold")),
                q.params.toArray());
    }

    private static void period(Q q, String column, AnalyticsWindow window, Integer year) {
        if (window != null) window(q, column, window);
        else year(q, column, year);
    }

    // ── where things stand today ──────────────────────────────────────────────

    public Positions positions(Long developmentId) {
        Q q = new Q();
        q.add("SELECT");
        q.add(" (SELECT coalesce(sum(v.balance), 0) FROM v_booking_balances v JOIN unit_bookings b ON b.id = v.booking_id"
                + " WHERE " + scope("b") + " AND b.status <> 5 AND b.state IN ('RESERVED','AGREED')");
        development(q, "b.development_id", developmentId);
        q.add(") AS receivable,");
        q.add(" (SELECT coalesce(sum(v.overdue), 0) FROM v_booking_balances v JOIN unit_bookings b ON b.id = v.booking_id"
                + " WHERE " + scope("b") + " AND b.status <> 5 AND b.state IN ('RESERVED','AGREED')");
        development(q, "b.development_id", developmentId);
        q.add(") AS overdue,");
        q.add(" (SELECT count(*) FROM unit_bookings b WHERE " + scope("b")
                + " AND b.status <> 5 AND b.state IN ('RESERVED','AGREED')");
        development(q, "b.development_id", developmentId);
        q.add(") AS live_bookings,");
        q.add(" (SELECT count(*) FROM v_booking_balances v JOIN unit_bookings b ON b.id = v.booking_id WHERE "
                + scope("b") + " AND b.status <> 5 AND b.state IN ('RESERVED','AGREED') AND v.overdue > 0");
        development(q, "b.development_id", developmentId);
        q.add(") AS overdue_bookings,");
        // The unit tallies come from the developments' own cached columns: one writer, already recounted.
        q.add(" (SELECT coalesce(sum(d.units_total), 0) FROM developments d WHERE " + scopeDevelopments("d") + " AND d.status <> 5");
        development(q, "d.id", developmentId);
        q.add(") AS units_total,");
        q.add(" (SELECT coalesce(sum(d.units_available), 0) FROM developments d WHERE " + scopeDevelopments("d") + " AND d.status <> 5");
        development(q, "d.id", developmentId);
        q.add(") AS units_available,");
        q.add(" (SELECT coalesce(sum(d.units_reserved), 0) FROM developments d WHERE " + scopeDevelopments("d") + " AND d.status <> 5");
        development(q, "d.id", developmentId);
        q.add(") AS units_reserved,");
        q.add(" (SELECT coalesce(sum(d.units_sold), 0) FROM developments d WHERE " + scopeDevelopments("d") + " AND d.status <> 5");
        development(q, "d.id", developmentId);
        q.add(") AS units_sold,");
        q.add(" (SELECT count(*) FROM v_development_finance f WHERE "
                + OwnerScopeSql.predicate("f.tenant_id", "f.institution_id", "f.development_id"));
        development(q, "f.development_id", developmentId);
        q.add(") AS developments,");
        q.add(" (SELECT count(*) FROM v_development_finance f WHERE "
                + OwnerScopeSql.predicate("f.tenant_id", "f.institution_id", "f.development_id") + " AND f.phases_late > 0");
        development(q, "f.development_id", developmentId);
        q.add(") AS developments_late,");
        q.add(" (SELECT count(*) FROM v_development_finance f WHERE "
                + OwnerScopeSql.predicate("f.tenant_id", "f.institution_id", "f.development_id")
                + " AND f.budget_amount IS NOT NULL AND f.spent > f.budget_amount");
        development(q, "f.development_id", developmentId);
        q.add(") AS developments_over_budget,");
        q.add(" (SELECT coalesce(sum(f.budget_amount), 0) FROM v_development_finance f WHERE "
                + OwnerScopeSql.predicate("f.tenant_id", "f.institution_id", "f.development_id"));
        development(q, "f.development_id", developmentId);
        q.add(") AS budget,");
        q.add(" (SELECT coalesce(sum(f.spent), 0) FROM v_development_finance f WHERE "
                + OwnerScopeSql.predicate("f.tenant_id", "f.institution_id", "f.development_id"));
        development(q, "f.development_id", developmentId);
        q.add(") AS spent_to_date,");
        q.add(" (SELECT coalesce(sum(f.facility_amount), 0) FROM v_development_finance f WHERE "
                + OwnerScopeSql.predicate("f.tenant_id", "f.institution_id", "f.development_id"));
        development(q, "f.development_id", developmentId);
        q.add(") AS facility,");
        q.add(" (SELECT coalesce(sum(f.drawn), 0) FROM v_development_finance f WHERE "
                + OwnerScopeSql.predicate("f.tenant_id", "f.institution_id", "f.development_id"));
        development(q, "f.development_id", developmentId);
        q.add(") AS drawn_to_date");

        return jdbc.queryForObject(q.sql.toString(), (rs, i) -> new Positions(
                money(rs, "receivable"), money(rs, "overdue"), rs.getInt("live_bookings"), rs.getInt("overdue_bookings"),
                rs.getInt("units_total"), rs.getInt("units_available"), rs.getInt("units_reserved"), rs.getInt("units_sold"),
                rs.getInt("developments"), rs.getInt("developments_late"), rs.getInt("developments_over_budget"),
                money(rs, "budget"), money(rs, "spent_to_date"), money(rs, "facility"), money(rs, "drawn_to_date")),
                q.params.toArray());
    }

    // ── month by month ────────────────────────────────────────────────────────

    /** One point per month in the window, quiet months included. */
    public List<TrendPoint> trend(AnalyticsWindow window, Long developmentId) {
        Map<YearMonth, BigDecimal> contracted = byMonth("unit_bookings", "b", "b.booked_on", "b.price_agreed",
                " AND b.status <> 5 AND b.state IN ('RESERVED','AGREED','COMPLETED')", window, developmentId);
        Map<YearMonth, BigDecimal> collected = byMonth("payments", "p", "p.paid_on", "p.amount",
                " AND p.status = 1", window, developmentId);
        Map<YearMonth, BigDecimal> spent = byMonth("development_expenditures", "e", "e.incurred_on", "e.amount",
                " AND e.status = 1 AND e.kind = 'SPENT'", window, developmentId);
        Map<YearMonth, BigDecimal> drawn = byMonth("facility_drawdowns", "f", "f.drawn_on", "f.amount",
                " AND f.status = 1", window, developmentId);

        List<TrendPoint> out = new ArrayList<>();
        for (YearMonth m : window.eachMonth()) {
            out.add(new TrendPoint(m.getYear(), m.getMonthValue(), AnalyticsWindow.shortLabel(m),
                    contracted.getOrDefault(m, BigDecimal.ZERO), collected.getOrDefault(m, BigDecimal.ZERO),
                    spent.getOrDefault(m, BigDecimal.ZERO), drawn.getOrDefault(m, BigDecimal.ZERO)));
        }
        return out;
    }

    private Map<YearMonth, BigDecimal> byMonth(String table, String alias, String dateColumn, String amountColumn,
                                               String extra, AnalyticsWindow window, Long developmentId) {
        Q q = new Q();
        q.add("SELECT date_trunc('month', " + dateColumn + ")::date AS bucket, coalesce(sum(" + amountColumn + "), 0) AS value"
                + " FROM " + table + " " + alias + " WHERE " + scope(alias) + extra);
        window(q, dateColumn, window);
        development(q, alias + ".development_id", developmentId);
        q.add(" GROUP BY 1");
        Map<YearMonth, BigDecimal> out = new HashMap<>();
        jdbc.query(q.sql.toString(), (RowCallbackHandler) rs -> {
            LocalDate bucket = rs.getDate("bucket").toLocalDate();
            out.put(YearMonth.from(bucket), money(rs, "value"));
        }, q.params.toArray());
        return out;
    }

    // ── what the money is made of ─────────────────────────────────────────────

    public List<Slice> collectionsByType(AnalyticsWindow window, Long developmentId) {
        Q q = new Q();
        q.add("SELECT coalesce(p.payment_type_name, p.method, 'Other') AS label, sum(p.amount) AS amount, count(*) AS n"
                + " FROM payments p WHERE " + scope("p") + " AND p.status = 1");
        window(q, "p.paid_on", window);
        development(q, "p.development_id", developmentId);
        q.add(" GROUP BY 1 ORDER BY 2 DESC");
        return jdbc.query(q.sql.toString(), SLICE, q.params.toArray());
    }

    public List<Slice> spendByCategory(AnalyticsWindow window, Long developmentId) {
        Q q = new Q();
        q.add("SELECT c.name AS label, sum(e.amount) AS amount, count(*) AS n"
                + " FROM development_expenditures e JOIN development_cost_categories c ON c.id = e.category_id"
                + " WHERE " + scope("e") + " AND e.status = 1 AND e.kind = 'SPENT'");
        window(q, "e.incurred_on", window);
        development(q, "e.development_id", developmentId);
        q.add(" GROUP BY 1 ORDER BY 2 DESC");
        return jdbc.query(q.sql.toString(), SLICE, q.params.toArray());
    }

    /** The stock is what exists now, so this one takes no window. Amount is the list value of those units. */
    public List<Slice> unitsByState(Long developmentId) {
        Q q = new Q();
        q.add("SELECT u.sale_state AS label, coalesce(sum(coalesce(u.price, t.list_price)), 0) AS amount, count(*) AS n"
                + " FROM properties u JOIN developments d ON d.id = u.development_id"
                + " LEFT JOIN development_unit_types t ON t.id = u.unit_type_id"
                + " WHERE " + scopeDevelopments("d") + " AND u.listing_kind = 'UNIT' AND u.status <> 5 AND d.status <> 5");
        development(q, "d.id", developmentId);
        q.add(" GROUP BY 1 ORDER BY 3 DESC");
        return jdbc.query(q.sql.toString(), SLICE, q.params.toArray());
    }

    // ── what is owed ──────────────────────────────────────────────────────────

    private static final int[][] BANDS = {{1, 30}, {31, 60}, {61, 90}, {91, 180}, {181, Integer.MAX_VALUE}};
    private static final String[] BAND_LABELS = {"1–30 days", "31–60 days", "61–90 days", "91–180 days", "Over 180 days"};

    /**
     * Overdue money by the age of the oldest instalment still unpaid.
     *
     * <p>Payments are not allocated to instalments here, so "oldest unpaid" is the first instalment whose running
     * total exceeds what has been paid — the same reading a person makes of a schedule beside a receipt list.
     * Every band is returned, empty ones included, so the chart's axis does not move between reads.
     */
    public List<AgeBucket> ageing(Long developmentId) {
        Q q = new Q();
        q.add("WITH owing AS ("
                + " SELECT v.booking_id, v.overdue, "
                + "   (SELECT min(s.due_on) FROM ("
                + "       SELECT i.due_on, sum(i.amount) OVER (ORDER BY i.due_on, i.id) AS cum"
                + "         FROM booking_instalments i WHERE i.booking_id = v.booking_id AND i.status <> 5) s"
                + "     WHERE s.due_on <= CURRENT_DATE AND s.cum > v.paid) AS oldest_due"
                + "   FROM v_booking_balances v JOIN unit_bookings b ON b.id = v.booking_id"
                + "  WHERE " + scope("b") + " AND b.status <> 5 AND b.state IN ('RESERVED','AGREED') AND v.overdue > 0");
        development(q, "b.development_id", developmentId);
        q.add(") SELECT coalesce(CURRENT_DATE - oldest_due, 1) AS age, overdue FROM owing");

        BigDecimal[] amounts = new BigDecimal[BANDS.length];
        int[] counts = new int[BANDS.length];
        Arrays.fill(amounts, BigDecimal.ZERO);
        jdbc.query(q.sql.toString(), (RowCallbackHandler) rs -> {
            int age = Math.max(1, rs.getInt("age"));
            for (int i = 0; i < BANDS.length; i++) {
                if (age >= BANDS[i][0] && age <= BANDS[i][1]) {
                    amounts[i] = amounts[i].add(money(rs, "overdue"));
                    counts[i]++;
                    break;
                }
            }
        }, q.params.toArray());

        List<AgeBucket> out = new ArrayList<>(BANDS.length);
        for (int i = 0; i < BANDS.length; i++) out.add(new AgeBucket(BAND_LABELS[i], BANDS[i][0], amounts[i], counts[i]));
        return out;
    }

    /** The bookings that owe most today, for the chase list. */
    public List<OwingBooking> worstBookings(Long developmentId, int limit) {
        Q q = new Q();
        q.add("SELECT b.id, b.reference, b.buyer_name, d.name AS development_name, u.unit_label, b.state,"
                + " b.price_agreed, v.balance, v.overdue, v.next_due_on,"
                + " (SELECT CURRENT_DATE - min(s.due_on) FROM ("
                + "     SELECT i.due_on, sum(i.amount) OVER (ORDER BY i.due_on, i.id) AS cum"
                + "       FROM booking_instalments i WHERE i.booking_id = b.id AND i.status <> 5) s"
                + "   WHERE s.due_on <= CURRENT_DATE AND s.cum > v.paid) AS days_overdue"
                + " FROM v_booking_balances v JOIN unit_bookings b ON b.id = v.booking_id"
                + " JOIN developments d ON d.id = b.development_id"
                + " LEFT JOIN properties u ON u.id = b.property_id"
                + " WHERE " + scope("b") + " AND b.status <> 5 AND b.state IN ('RESERVED','AGREED') AND v.overdue > 0");
        development(q, "b.development_id", developmentId);
        q.add(" ORDER BY v.overdue DESC, v.balance DESC LIMIT ?", limit);
        return jdbc.query(q.sql.toString(), (rs, i) -> new OwingBooking(
                HashIdUtil.encodeId(rs.getLong("id")), rs.getString("reference"), rs.getString("buyer_name"),
                rs.getString("development_name"), rs.getString("unit_label"), rs.getString("state"),
                money(rs, "price_agreed"), money(rs, "balance"), money(rs, "overdue"),
                rs.getDate("next_due_on") == null ? null : rs.getDate("next_due_on").toLocalDate(),
                rs.getObject("days_overdue") == null ? null : rs.getInt("days_overdue")),
                q.params.toArray());
    }

    // ── the developments, side by side ────────────────────────────────────────

    public List<DevelopmentComparison> developments(AnalyticsWindow window, Long developmentId, int cap) {
        Q q = new Q();
        q.add("SELECT f.*, coalesce(w.collected, 0) AS collected_in_window"
                + " FROM v_development_finance f"
                + " LEFT JOIN LATERAL (SELECT sum(p.amount) AS collected FROM payments p"
                + "   WHERE p.development_id = f.development_id AND p.status = 1 AND p.paid_on >= ? AND p.paid_on < ?) w ON true"
                + " WHERE " + OwnerScopeSql.predicate("f.tenant_id", "f.institution_id", "f.development_id"),
                window.startDate(), window.endDateExclusive());
        development(q, "f.development_id", developmentId);
        q.add(" ORDER BY collected_in_window DESC, f.development_name LIMIT ?", cap);
        return jdbc.query(q.sql.toString(), (rs, i) -> new DevelopmentComparison(
                HashIdUtil.encodeId(rs.getLong("development_id")), rs.getString("reference"),
                rs.getString("development_name"), rs.getString("owner_name"), rs.getString("construction_status"),
                rs.getShort("percent_complete"), rs.getInt("units_total"), rs.getInt("units_available"),
                rs.getInt("units_sold"), money(rs, "contracted"), money(rs, "collected"),
                money(rs, "collected_in_window"), money(rs, "receivable"), money(rs, "overdue"),
                rs.getBigDecimal("budget_amount"), money(rs, "committed"), money(rs, "spent"), money(rs, "drawn"),
                rs.getBigDecimal("facility_amount"), rs.getInt("phases_late"),
                rs.getDate("projected_completion_on") == null ? null : rs.getDate("projected_completion_on").toLocalDate(),
                rs.getDate("forecast_on") == null ? null : rs.getDate("forecast_on").toLocalDate()),
                q.params.toArray());
    }

    public int countDevelopments(Long developmentId) {
        Q q = new Q();
        q.add("SELECT count(*) FROM v_development_finance f WHERE "
                + OwnerScopeSql.predicate("f.tenant_id", "f.institution_id", "f.development_id"));
        development(q, "f.development_id", developmentId);
        Integer n = jdbc.queryForObject(q.sql.toString(), Integer.class, q.params.toArray());
        return n == null ? 0 : n;
    }

    // ── the pipeline ──────────────────────────────────────────────────────────

    /**
     * Leads over the window. Enquiries, viewings and offers sit on listings, which belong to a seller and to no
     * development, so the scope here is the tenant column alone and a development filter does not apply.
     */
    public PipelineStats pipeline(AnalyticsWindow window) {
        String scope = OwnerScopeSql.predicate("t.tenant_id", null, null);
        Q q = new Q();
        q.add("SELECT"
                + " (SELECT count(*) FROM enquiry_tickets t WHERE " + scope + " AND t.status <> 5");
        window(q, "t.created_at", window);
        q.add(") AS enquiries,"
                + " (SELECT count(*) FROM enquiry_tickets t WHERE " + scope + " AND t.status <> 5 AND t.awaiting_seller"
                + "   AND t.state <> 'CLOSED') AS awaiting,"
                + " (SELECT count(*) FROM site_visits t WHERE " + scope + " AND t.status <> 5");
        window(q, "t.requested_at", window);
        q.add(") AS visits,"
                + " (SELECT count(*) FROM purchase_requests t WHERE " + scope + " AND t.status <> 5");
        window(q, "t.created_at", window);
        q.add(") AS offers,"
                + " (SELECT coalesce(sum(t.offer_amount), 0) FROM purchase_requests t WHERE " + scope + " AND t.status <> 5");
        window(q, "t.created_at", window);
        q.add(") AS offers_amount");

        Map<String, Object> row = jdbc.queryForMap(q.sql.toString(), q.params.toArray());

        Q v = new Q();
        v.add("SELECT t.state AS label, 0 AS amount, count(*) AS n FROM site_visits t WHERE " + scope + " AND t.status <> 5");
        window(v, "t.requested_at", window);
        v.add(" GROUP BY 1 ORDER BY 3 DESC");
        Q o = new Q();
        o.add("SELECT t.state AS label, coalesce(sum(t.offer_amount), 0) AS amount, count(*) AS n FROM purchase_requests t"
                + " WHERE " + scope + " AND t.status <> 5");
        window(o, "t.created_at", window);
        o.add(" GROUP BY 1 ORDER BY 3 DESC");

        return new PipelineStats(
                ((Number) row.get("enquiries")).intValue(), ((Number) row.get("awaiting")).intValue(),
                ((Number) row.get("visits")).intValue(), jdbc.query(v.sql.toString(), SLICE, v.params.toArray()),
                ((Number) row.get("offers")).intValue(), new BigDecimal(row.get("offers_amount").toString()),
                jdbc.query(o.sql.toString(), SLICE, o.params.toArray()));
    }

    // ── the dashboard's month and year ────────────────────────────────────────

    /** Twelve months of the year, every one present. */
    public List<MonthlyCollection> collectionsByMonth(int year, Long developmentId) {
        AnalyticsWindow window = new AnalyticsWindow(year, 1, year, 12);
        Map<YearMonth, BigDecimal> collected = byMonth("payments", "p", "p.paid_on", "p.amount", " AND p.status = 1",
                window, developmentId);
        Map<YearMonth, BigDecimal> spent = byMonth("development_expenditures", "e", "e.incurred_on", "e.amount",
                " AND e.status = 1 AND e.kind = 'SPENT'", window, developmentId);
        Q q = new Q();
        q.add("SELECT date_trunc('month', p.paid_on)::date AS bucket, count(*) AS n FROM payments p WHERE "
                + scope("p") + " AND p.status = 1");
        window(q, "p.paid_on", window);
        development(q, "p.development_id", developmentId);
        q.add(" GROUP BY 1");
        Map<YearMonth, Integer> counts = new HashMap<>();
        jdbc.query(q.sql.toString(), (RowCallbackHandler) rs -> counts.put(YearMonth.from(rs.getDate("bucket").toLocalDate()), rs.getInt("n")),
                q.params.toArray());

        List<MonthlyCollection> out = new ArrayList<>(12);
        for (YearMonth m : window.eachMonth()) {
            out.add(new MonthlyCollection(m.getMonthValue(), AnalyticsWindow.shortLabel(m),
                    collected.getOrDefault(m, BigDecimal.ZERO), counts.getOrDefault(m, 0),
                    spent.getOrDefault(m, BigDecimal.ZERO)));
        }
        return out;
    }

    /** The month's receipts, newest first, a page at a time. */
    public CollectionsPage collections(int year, int month, Long developmentId, int page, int size) {
        AnalyticsWindow window = new AnalyticsWindow(year, month, year, month);
        Q count = new Q();
        count.add("SELECT count(*) FROM payments p WHERE " + scope("p") + " AND p.status = 1");
        window(count, "p.paid_on", window);
        development(count, "p.development_id", developmentId);
        Long total = jdbc.queryForObject(count.sql.toString(), Long.class, count.params.toArray());

        Q q = new Q();
        q.add("SELECT p.id, p.reference, p.paid_on, p.payer_name, p.buyer_name, p.development_name, p.unit_label,"
                + " coalesce(p.payment_type_name, p.method) AS payment_type, p.amount, p.currency"
                + " FROM payments p WHERE " + scope("p") + " AND p.status = 1");
        window(q, "p.paid_on", window);
        development(q, "p.development_id", developmentId);
        q.add(" ORDER BY p.paid_on DESC, p.id DESC LIMIT ? OFFSET ?", size, (long) page * size);
        List<Receipt> rows = jdbc.query(q.sql.toString(), (rs, i) -> new Receipt(
                HashIdUtil.encodeId(rs.getLong("id")), rs.getString("reference"), rs.getDate("paid_on").toLocalDate(),
                rs.getString("payer_name"), rs.getString("buyer_name"), rs.getString("development_name"),
                rs.getString("unit_label"), rs.getString("payment_type"), money(rs, "amount"), rs.getString("currency")),
                q.params.toArray());
        return new CollectionsPage(rows, page, size, total == null ? 0 : total);
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    private static final RowMapper<Slice> SLICE = (rs, i) ->
            new Slice(pretty(rs.getString("label")), money(rs, "amount"), rs.getInt("n"));

    private static BigDecimal money(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value;
    }

    /** UNDER_OFFER → Under offer. Codes stay codes on the wire only where a client keys on them. */
    private static String pretty(String code) {
        if (code == null || code.isBlank()) return "Other";
        if (!code.equals(code.toUpperCase(Locale.ROOT))) return code;
        String word = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
