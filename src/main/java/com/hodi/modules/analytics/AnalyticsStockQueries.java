package com.hodi.modules.analytics;

import com.hodi.modules.analytics.AnalyticsViews.DisbursementPoint;
import com.hodi.modules.analytics.AnalyticsViews.FlowPoint;
import com.hodi.modules.analytics.AnalyticsViews.SoldPoint;
import com.hodi.modules.analytics.AnalyticsViews.StatementPoint;
import com.hodi.modules.analytics.AnalyticsViews.StockRow;
import com.hodi.security.OwnerScopeSql;
import com.hodi.security.hashid.HashIdUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The stock, and the bank's own money.
 *
 * <p>The same rules as {@link AnalyticsQueries}: the caller's scope predicate first, every window edge and
 * development a bound parameter, nothing from a request spliced. The bank queries carry no scope predicate
 * because they are refused to anyone but the platform before they run — see {@code AnalyticsService#bank}.
 */
@Repository
@RequiredArgsConstructor
public class AnalyticsStockQueries {

    private final JdbcTemplate jdbc;

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

    private static String scopeDevelopments(String alias) {
        return OwnerScopeSql.predicate(alias + ".tenant_id", alias + ".institution_id", alias + ".id");
    }

    private static void window(Q q, String column, AnalyticsWindow window) {
        q.add(" AND " + column + " >= ? AND " + column + " < ?", window.startDate(), window.endDateExclusive());
    }

    private static void development(Q q, String column, Long developmentId) {
        if (developmentId != null) q.add(" AND " + column + " = ?", developmentId);
    }

    // ── inventory ─────────────────────────────────────────────────────────────

    /** Units marked sold and bookings made, month by month, quiet months included. */
    public List<SoldPoint> soldAndBooked(AnalyticsWindow window, Long developmentId) {
        Q sold = new Q();
        sold.add("SELECT date_trunc('month', u.sold_at)::date AS bucket, count(*) AS n"
                + " FROM properties u JOIN developments d ON d.id = u.development_id"
                + " WHERE " + scopeDevelopments("d") + " AND u.listing_kind = 'UNIT' AND u.status <> 5 AND d.status <> 5"
                + " AND u.sale_state = 'SOLD'");
        window(sold, "u.sold_at", window);
        development(sold, "d.id", developmentId);
        sold.add(" GROUP BY 1");
        Map<YearMonth, Integer> soldBy = countMap(sold);

        Q booked = new Q();
        booked.add("SELECT date_trunc('month', b.booked_on)::date AS bucket, count(*) AS n FROM unit_bookings b"
                + " WHERE " + scope("b") + " AND b.status <> 5 AND b.state IN ('RESERVED','AGREED','COMPLETED')");
        window(booked, "b.booked_on", window);
        development(booked, "b.development_id", developmentId);
        booked.add(" GROUP BY 1");
        Map<YearMonth, Integer> bookedBy = countMap(booked);

        List<SoldPoint> out = new ArrayList<>();
        for (YearMonth m : window.eachMonth()) {
            out.add(new SoldPoint(m.getYear(), m.getMonthValue(), AnalyticsWindow.shortLabel(m),
                    soldBy.getOrDefault(m, 0), bookedBy.getOrDefault(m, 0)));
        }
        return out;
    }

    /**
     * The stock by development and kind of home, as it stands today.
     *
     * <p>Price per square metre is the mean over the units that have both a price and a floor area, either
     * their own or their kind's; a kind with neither reads null rather than a figure divided by nothing.
     */
    public List<StockRow> stock(Long developmentId) {
        Q q = new Q();
        q.add("SELECT d.id AS development_id, d.name AS development_name, coalesce(t.name, 'Units') AS unit_type,"
                + " count(*) AS total,"
                + " count(*) FILTER (WHERE u.sale_state = 'AVAILABLE') AS available,"
                + " count(*) FILTER (WHERE u.sale_state IN ('HELD','RESERVED')) AS held,"
                + " count(*) FILTER (WHERE u.sale_state = 'SOLD') AS sold,"
                + " t.list_price,"
                + " avg(coalesce(u.price, t.list_price) / nullif(coalesce(u.floor_area_sqm, t.floor_area_sqm), 0)) AS per_sqm"
                + " FROM properties u JOIN developments d ON d.id = u.development_id"
                + " LEFT JOIN development_unit_types t ON t.id = u.unit_type_id"
                + " WHERE " + scopeDevelopments("d") + " AND u.listing_kind = 'UNIT' AND u.status <> 5 AND d.status <> 5");
        development(q, "d.id", developmentId);
        q.add(" GROUP BY d.id, d.name, t.id, t.name, t.list_price ORDER BY d.name, t.list_price NULLS LAST, t.name");
        return jdbc.query(q.sql.toString(), (rs, i) -> new StockRow(
                HashIdUtil.encodeId(rs.getLong("development_id")), rs.getString("development_name"), rs.getString("unit_type"),
                rs.getInt("total"), rs.getInt("available"), rs.getInt("held"), rs.getInt("sold"),
                rs.getBigDecimal("list_price"), scale(rs.getBigDecimal("per_sqm"))), q.params.toArray());
    }

    /** Holds that were agreed, and holds that ran out, in the window. */
    public int[] holdOutcomes(AnalyticsWindow window, Long developmentId) {
        Q q = new Q();
        q.add("SELECT"
                + " (SELECT count(*) FROM unit_bookings b WHERE " + scope("b") + " AND b.status <> 5 AND b.agreed_at IS NOT NULL");
        window(q, "b.agreed_at", window);
        development(q, "b.development_id", developmentId);
        q.add(") AS agreed,"
                + " (SELECT count(*) FROM unit_bookings b WHERE " + scope("b") + " AND b.status <> 5 AND b.state = 'LAPSED'");
        window(q, "b.closed_at", window);
        development(q, "b.development_id", developmentId);
        q.add(") AS lapsed");
        return jdbc.queryForObject(q.sql.toString(), (rs, i) -> new int[] {rs.getInt("agreed"), rs.getInt("lapsed")},
                q.params.toArray());
    }

    // ── the bank ──────────────────────────────────────────────────────────────

    /** Statements by the month they arrived, and what became of each. */
    public List<StatementPoint> statementsByMonth(AnalyticsWindow window) {
        Q q = new Q();
        q.add("SELECT date_trunc('month', s.created_at)::date AS bucket, count(*) AS arrived, coalesce(sum(s.amount), 0) AS amount,"
                + " count(*) FILTER (WHERE s.state = 'MAPPED' AND s.mapped_by = 'system') AS automatic,"
                + " count(*) FILTER (WHERE s.state = 'MAPPED' AND coalesce(s.mapped_by, '') <> 'system') AS by_hand,"
                + " count(*) FILTER (WHERE s.state = 'IGNORED') AS set_aside,"
                // Waiting for its account is unplaced too: money the bank confirmed that no booking has yet.
                + " count(*) FILTER (WHERE s.state IN ('UNMAPPED', 'NO_ACCOUNT')) AS unplaced"
                + " FROM coop_statements s WHERE s.status <> 5");
        window(q, "s.created_at", window);
        q.add(" GROUP BY 1");
        Map<YearMonth, StatementPoint> by = new HashMap<>();
        jdbc.query(q.sql.toString(), (RowCallbackHandler) rs -> {
            YearMonth m = YearMonth.from(rs.getDate("bucket").toLocalDate());
            by.put(m, new StatementPoint(m.getYear(), m.getMonthValue(), AnalyticsWindow.shortLabel(m), rs.getInt("arrived"),
                    money(rs, "amount"), rs.getInt("automatic"), rs.getInt("by_hand"), rs.getInt("set_aside"), rs.getInt("unplaced")));
        }, q.params.toArray());
        List<StatementPoint> out = new ArrayList<>();
        for (YearMonth m : window.eachMonth()) {
            out.add(by.getOrDefault(m, new StatementPoint(m.getYear(), m.getMonthValue(), AnalyticsWindow.shortLabel(m),
                    0, BigDecimal.ZERO, 0, 0, 0, 0)));
        }
        return out;
    }

    /** From arrival to placing, for the statements placed in the window. Minutes, median. */
    public Integer medianMinutesToPlace(AnalyticsWindow window) {
        Q q = new Q();
        q.add("SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY extract(epoch FROM (s.mapped_at - s.created_at)) / 60.0)"
                + " FROM coop_statements s WHERE s.status <> 5 AND s.state = 'MAPPED' AND s.mapped_at IS NOT NULL");
        window(q, "s.created_at", window);
        Double minutes = jdbc.queryForObject(q.sql.toString(), Double.class, q.params.toArray());
        return minutes == null ? null : (int) Math.round(Math.max(0, minutes));
    }

    /** Transfers by the month they were proposed, and how they ended. */
    public List<DisbursementPoint> disbursementsByMonth(AnalyticsWindow window) {
        Q q = new Q();
        q.add("SELECT date_trunc('month', d.created_at)::date AS bucket, count(*) AS n, coalesce(sum(d.amount), 0) AS amount,"
                + " count(*) FILTER (WHERE d.state = 'SUCCEEDED') AS succeeded,"
                + " count(*) FILTER (WHERE d.state IN ('FAILED','REFUSED')) AS failed,"
                + " count(*) FILTER (WHERE d.state NOT IN ('SUCCEEDED','FAILED','REFUSED')) AS pending"
                + " FROM disbursements d WHERE d.status <> 5");
        window(q, "d.created_at", window);
        q.add(" GROUP BY 1");
        Map<YearMonth, DisbursementPoint> by = new HashMap<>();
        jdbc.query(q.sql.toString(), (RowCallbackHandler) rs -> {
            YearMonth m = YearMonth.from(rs.getDate("bucket").toLocalDate());
            by.put(m, new DisbursementPoint(m.getYear(), m.getMonthValue(), AnalyticsWindow.shortLabel(m), rs.getInt("n"),
                    money(rs, "amount"), rs.getInt("succeeded"), rs.getInt("failed"), rs.getInt("pending")));
        }, q.params.toArray());
        List<DisbursementPoint> out = new ArrayList<>();
        for (YearMonth m : window.eachMonth()) {
            out.add(by.getOrDefault(m, new DisbursementPoint(m.getYear(), m.getMonthValue(), AnalyticsWindow.shortLabel(m),
                    0, BigDecimal.ZERO, 0, 0, 0)));
        }
        return out;
    }

    /** Money received against money that left, by month. Out counts only what the bank confirmed. */
    public List<FlowPoint> flow(AnalyticsWindow window) {
        Q in = new Q();
        in.add("SELECT date_trunc('month', p.paid_on)::date AS bucket, coalesce(sum(p.amount), 0) AS value FROM payments p WHERE p.status = 1");
        window(in, "p.paid_on", window);
        in.add(" GROUP BY 1");
        Map<YearMonth, BigDecimal> inBy = moneyMap(in);
        Q out = new Q();
        out.add("SELECT date_trunc('month', d.settled_at)::date AS bucket, coalesce(sum(d.amount), 0) AS value FROM disbursements d"
                + " WHERE d.status <> 5 AND d.state = 'SUCCEEDED'");
        window(out, "d.settled_at", window);
        out.add(" GROUP BY 1");
        Map<YearMonth, BigDecimal> outBy = moneyMap(out);
        List<FlowPoint> points = new ArrayList<>();
        for (YearMonth m : window.eachMonth()) {
            points.add(new FlowPoint(m.getYear(), m.getMonthValue(), AnalyticsWindow.shortLabel(m),
                    inBy.getOrDefault(m, BigDecimal.ZERO), outBy.getOrDefault(m, BigDecimal.ZERO)));
        }
        return points;
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    private Map<YearMonth, Integer> countMap(Q q) {
        Map<YearMonth, Integer> out = new HashMap<>();
        jdbc.query(q.sql.toString(), (RowCallbackHandler) rs ->
                out.put(YearMonth.from(rs.getDate("bucket").toLocalDate()), rs.getInt("n")), q.params.toArray());
        return out;
    }

    private Map<YearMonth, BigDecimal> moneyMap(Q q) {
        Map<YearMonth, BigDecimal> out = new HashMap<>();
        jdbc.query(q.sql.toString(), (RowCallbackHandler) rs ->
                out.put(YearMonth.from(rs.getDate("bucket").toLocalDate()), money(rs, "value")), q.params.toArray());
        return out;
    }

    private static BigDecimal money(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(0, java.math.RoundingMode.HALF_UP);
    }
}
