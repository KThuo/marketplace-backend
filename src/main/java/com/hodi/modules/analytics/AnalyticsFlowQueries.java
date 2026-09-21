package com.hodi.modules.analytics;

import com.hodi.modules.analytics.AnalyticsViews.ChannelMonth;
import com.hodi.modules.analytics.AnalyticsViews.DuePoint;
import com.hodi.modules.analytics.AnalyticsViews.Interval;
import com.hodi.modules.analytics.AnalyticsViews.Lateness;
import com.hodi.modules.analytics.AnalyticsViews.PromptPoint;
import com.hodi.modules.analytics.AnalyticsViews.Slice;
import com.hodi.security.OwnerScopeSql;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How money is collected against what is due, and how enquiries become sales.
 *
 * <p>The same rules as {@link AnalyticsQueries}: every query starts from the caller's scope predicate, every
 * window edge and development is a bound parameter, and nothing from a request is spliced into SQL.
 *
 * <h2>Reading payments against instalments</h2>
 *
 * <p>Nothing allocates a payment to an instalment when it arrives — money lands on the booking and the
 * balance moves. So lateness is read the way a person reads a schedule beside a receipt list: instalments
 * and payments are each run up cumulatively per booking, and a payment is taken to meet the first instalment
 * whose running total its own running total reaches. Overpayments and payments on a booking with no plan
 * meet no instalment and are left out of the on-time and late columns rather than guessed at.
 */
@Repository
@RequiredArgsConstructor
public class AnalyticsFlowQueries {

    /** How many days past the due date still counts as paid on time. Banks clear in a day or two; three is fair. */
    public static final int GRACE_DAYS = 3;

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

    private static String leadScope(String alias) {
        return OwnerScopeSql.predicate(alias + ".tenant_id", null, null);
    }

    private static void window(Q q, String column, AnalyticsWindow window) {
        q.add(" AND " + column + " >= ? AND " + column + " < ?", window.startDate(), window.endDateExclusive());
    }

    private static void development(Q q, String column, Long developmentId) {
        if (developmentId != null) q.add(" AND " + column + " = ?", developmentId);
    }

    /** The current plan\u2019s instalments only: a rescheduled booking has one schedule that counts. */
    private static final String CURRENT_PLAN = " AND i.plan_no = (SELECT max(i2.plan_no) FROM booking_instalments i2"
            + " WHERE i2.booking_id = i.booking_id AND i2.status <> 5)";

    // ── collections ───────────────────────────────────────────────────────────

    /** Due against collected, month by month, quiet months included. */
    public List<DuePoint> dueAndCollected(AnalyticsWindow window, Long developmentId) {
        Q due = new Q();
        due.add("SELECT date_trunc('month', i.due_on)::date AS bucket, coalesce(sum(i.amount), 0) AS value"
                + " FROM booking_instalments i JOIN unit_bookings b ON b.id = i.booking_id"
                + " WHERE " + scope("b") + " AND b.status <> 5 AND b.state IN ('RESERVED','AGREED','COMPLETED')"
                + " AND i.status <> 5" + CURRENT_PLAN);
        window(due, "i.due_on", window);
        development(due, "b.development_id", developmentId);
        due.add(" GROUP BY 1");
        Map<YearMonth, BigDecimal> dueBy = monthMap(due);

        Q paid = new Q();
        paid.add("SELECT date_trunc('month', p.paid_on)::date AS bucket, coalesce(sum(p.amount), 0) AS value"
                + " FROM payments p WHERE " + scope("p") + " AND p.status = 1");
        window(paid, "p.paid_on", window);
        development(paid, "p.development_id", developmentId);
        paid.add(" GROUP BY 1");
        Map<YearMonth, BigDecimal> paidBy = monthMap(paid);

        List<DuePoint> out = new ArrayList<>();
        for (YearMonth m : window.eachMonth()) {
            out.add(new DuePoint(m.getYear(), m.getMonthValue(), AnalyticsWindow.shortLabel(m),
                    dueBy.getOrDefault(m, BigDecimal.ZERO), paidBy.getOrDefault(m, BigDecimal.ZERO)));
        }
        return out;
    }

    /** Each payment in the window against the instalment it met. See the class note. */
    public Lateness lateness(AnalyticsWindow window, Long developmentId) {
        Q q = new Q();
        q.add("WITH inst AS ("
                + " SELECT i.booking_id, i.due_on,"
                + "        sum(i.amount) OVER (PARTITION BY i.booking_id ORDER BY i.due_on, i.sequence_no) AS cum"
                + "   FROM booking_instalments i WHERE i.status <> 5" + CURRENT_PLAN
                + "), pay AS ("
                + " SELECT p.id, p.booking_id, p.paid_on, p.amount,"
                + "        sum(p.amount) OVER (PARTITION BY p.booking_id ORDER BY p.paid_on, p.id) AS cum"
                + "   FROM payments p WHERE " + scope("p") + " AND p.status = 1");
        development(q, "p.development_id", developmentId);
        q.add("), matched AS ("
                + " SELECT p.paid_on, p.amount,"
                + "        (SELECT min(i.due_on) FROM inst i WHERE i.booking_id = p.booking_id AND i.cum >= p.cum - 0.01) AS due_on"
                + "   FROM pay p WHERE p.paid_on >= ? AND p.paid_on < ?"
                + ") SELECT"
                + " count(*) FILTER (WHERE due_on IS NOT NULL) AS scheduled,"
                + " count(*) FILTER (WHERE due_on IS NOT NULL AND paid_on <= due_on + ?) AS on_time,"
                + " count(*) FILTER (WHERE due_on IS NOT NULL AND paid_on > due_on + ?) AS late,"
                + " coalesce(sum(amount) FILTER (WHERE due_on IS NOT NULL AND paid_on > due_on + ?), 0) AS late_amount,"
                + " percentile_cont(0.5) WITHIN GROUP (ORDER BY (paid_on - due_on))"
                + "   FILTER (WHERE due_on IS NOT NULL AND paid_on > due_on + ?) AS median_late"
                + " FROM matched",
                window.startDate(), window.endDateExclusive(), GRACE_DAYS, GRACE_DAYS, GRACE_DAYS, GRACE_DAYS);
        return jdbc.queryForObject(q.sql.toString(), (rs, i) -> {
            Object median = rs.getObject("median_late");
            return new Lateness(rs.getInt("scheduled"), rs.getInt("on_time"), rs.getInt("late"), GRACE_DAYS,
                    median == null ? null : (int) Math.round(((Number) median).doubleValue()), money(rs, "late_amount"));
        }, q.params.toArray());
    }

    /** What came in each month, by how it arrived. Rows only for months and channels with money in them. */
    public List<ChannelMonth> channelsByMonth(AnalyticsWindow window, Long developmentId) {
        Q q = new Q();
        q.add("SELECT date_trunc('month', p.paid_on)::date AS bucket, coalesce(p.payment_type_name, p.method) AS channel,"
                + " coalesce(sum(p.amount), 0) AS amount, count(*) AS n FROM payments p WHERE " + scope("p") + " AND p.status = 1");
        window(q, "p.paid_on", window);
        development(q, "p.development_id", developmentId);
        q.add(" GROUP BY 1, 2 ORDER BY 1, 2");
        return jdbc.query(q.sql.toString(), (rs, i) -> {
            YearMonth m = YearMonth.from(rs.getDate("bucket").toLocalDate());
            return new ChannelMonth(m.getYear(), m.getMonthValue(), AnalyticsWindow.shortLabel(m),
                    pretty(rs.getString("channel")), money(rs, "amount"), rs.getInt("n"));
        }, q.params.toArray());
    }

    /** Prompts sent each month and what became of them, quiet months included. */
    public List<PromptPoint> promptsByMonth(AnalyticsWindow window, Long developmentId) {
        Q q = new Q();
        q.add("SELECT date_trunc('month', i.created_at)::date AS bucket, count(*) AS sent,"
                + " count(*) FILTER (WHERE i.state = 'SUCCEEDED') AS paid,"
                + " count(*) FILTER (WHERE i.state = 'FAILED') AS failed,"
                + " count(*) FILTER (WHERE i.state IN ('PENDING','PROCESSING')) AS unanswered"
                + " FROM payment_intents i WHERE " + OwnerScopeSql.predicate("i.tenant_id", "i.institution_id", null)
                + " AND i.status <> 5");
        window(q, "i.created_at", window);
        if (developmentId != null) {
            q.add(" AND i.property_id IN (SELECT id FROM properties WHERE development_id = ?)", developmentId);
        }
        q.add(" GROUP BY 1");
        Map<YearMonth, int[]> by = new HashMap<>();
        jdbc.query(q.sql.toString(), (RowCallbackHandler) rs -> by.put(YearMonth.from(rs.getDate("bucket").toLocalDate()),
                new int[] {rs.getInt("sent"), rs.getInt("paid"), rs.getInt("failed"), rs.getInt("unanswered")}), q.params.toArray());
        List<PromptPoint> out = new ArrayList<>();
        for (YearMonth m : window.eachMonth()) {
            int[] v = by.getOrDefault(m, new int[4]);
            out.add(new PromptPoint(m.getYear(), m.getMonthValue(), AnalyticsWindow.shortLabel(m), v[0], v[1], v[2], v[3]));
        }
        return out;
    }

    // ── the funnel ────────────────────────────────────────────────────────────

    /** How many reached each stage in the window: asked, came to look, offered, booked, completed. */
    public int[] stageCounts(AnalyticsWindow window) {
        Q q = new Q();
        q.add("SELECT"
                + " (SELECT count(*) FROM enquiry_tickets t WHERE " + leadScope("t") + " AND t.status <> 5");
        window(q, "t.created_at", window);
        q.add(") AS enquiries,"
                + " (SELECT count(*) FROM site_visits t WHERE " + leadScope("t") + " AND t.status <> 5");
        window(q, "t.requested_at", window);
        q.add(") AS viewings,"
                + " (SELECT count(*) FROM purchase_requests t WHERE " + leadScope("t") + " AND t.status <> 5");
        window(q, "t.created_at", window);
        q.add(") AS offers,"
                + " (SELECT count(*) FROM unit_bookings b WHERE " + scope("b") + " AND b.status <> 5"
                + "   AND b.state IN ('RESERVED','AGREED','COMPLETED')");
        window(q, "b.booked_on", window);
        q.add(") AS bookings,"
                + " (SELECT count(*) FROM unit_bookings b WHERE " + scope("b") + " AND b.status <> 5 AND b.state = 'COMPLETED'");
        window(q, "b.completed_at", window);
        q.add(") AS completed");
        return jdbc.queryForObject(q.sql.toString(), (rs, i) -> new int[] {
                rs.getInt("enquiries"), rs.getInt("viewings"), rs.getInt("offers"), rs.getInt("bookings"), rs.getInt("completed")},
                q.params.toArray());
    }

    /**
     * How long each step took, for the people who took it.
     *
     * <p>Steps are tied by the person and the home: the enquiry that led to a viewing is the earliest one the
     * same buyer raised about the same listing before asking to see it. An offer that became a booking is
     * tied by the booking the offer records. Medians, because one buyer who took a year would otherwise be the
     * whole story.
     */
    public List<Interval> intervals(AnalyticsWindow window) {
        List<Interval> out = new ArrayList<>();
        out.add(median("enquiryToViewing", "Enquiry to viewing",
                "SELECT (v.requested_at::date - e.first_asked) AS days FROM site_visits v"
                        + " JOIN LATERAL (SELECT min(t.created_at)::date AS first_asked FROM enquiry_tickets t"
                        + "   WHERE t.user_id = v.user_id AND t.property_id = v.property_id AND t.status <> 5"
                        + "     AND t.created_at <= v.requested_at) e ON true"
                        + " WHERE " + leadScope("v") + " AND v.status <> 5 AND e.first_asked IS NOT NULL"
                        + "   AND v.requested_at >= ? AND v.requested_at < ?", window));
        out.add(median("viewingToOffer", "Viewing to offer",
                "SELECT (o.created_at::date - s.seen) AS days FROM purchase_requests o"
                        + " JOIN LATERAL (SELECT min(v.requested_at)::date AS seen FROM site_visits v"
                        + "   WHERE v.user_id = o.user_id AND v.property_id = o.property_id AND v.status <> 5"
                        + "     AND v.requested_at <= o.created_at) s ON true"
                        + " WHERE " + leadScope("o") + " AND o.status <> 5 AND s.seen IS NOT NULL"
                        + "   AND o.created_at >= ? AND o.created_at < ?", window));
        out.add(median("offerToBooking", "Offer to booking",
                "SELECT (b.booked_on - o.created_at::date) AS days FROM purchase_requests o"
                        + " JOIN unit_bookings b ON b.id = o.booking_id"
                        + " WHERE " + leadScope("o") + " AND o.status <> 5 AND b.status <> 5"
                        + "   AND b.booked_on >= ? AND b.booked_on < ?", window));
        out.add(median("enquiryToBooking", "Enquiry to booking",
                "SELECT (b.booked_on - e.first_asked) AS days FROM unit_bookings b"
                        + " JOIN LATERAL (SELECT min(t.created_at)::date AS first_asked FROM enquiry_tickets t"
                        + "   WHERE t.user_id = b.buyer_user_id AND t.property_id = b.property_id AND t.status <> 5"
                        + "     AND t.created_at::date <= b.booked_on) e ON true"
                        + " WHERE " + scope("b") + " AND b.status <> 5 AND b.buyer_user_id IS NOT NULL AND e.first_asked IS NOT NULL"
                        + "   AND b.booked_on >= ? AND b.booked_on < ?", window));
        return out;
    }

    private Interval median(String key, String label, String daysSql, AnalyticsWindow window) {
        String sql = "SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY x.days) AS median, count(*) AS n FROM (" + daysSql + ") x";
        return jdbc.queryForObject(sql, (rs, i) -> {
            Object median = rs.getObject("median");
            return new Interval(key, label, median == null ? null : (int) Math.round(((Number) median).doubleValue()), rs.getInt("n"));
        }, window.startDate(), window.endDateExclusive());
    }

    /** Offers made in the window, by where they ended up, and how many became a booking. */
    public List<Slice> offersByOutcome(AnalyticsWindow window) {
        Q q = new Q();
        q.add("SELECT t.state AS label, coalesce(sum(t.offer_amount), 0) AS amount, count(*) AS n FROM purchase_requests t"
                + " WHERE " + leadScope("t") + " AND t.status <> 5");
        window(q, "t.created_at", window);
        q.add(" GROUP BY 1 ORDER BY 3 DESC");
        return jdbc.query(q.sql.toString(), SLICE, q.params.toArray());
    }

    public int offersConverted(AnalyticsWindow window) {
        Q q = new Q();
        q.add("SELECT count(*) FROM purchase_requests t WHERE " + leadScope("t") + " AND t.status <> 5 AND t.booking_id IS NOT NULL");
        window(q, "t.created_at", window);
        Integer n = jdbc.queryForObject(q.sql.toString(), Integer.class, q.params.toArray());
        return n == null ? 0 : n;
    }

    public List<Slice> viewingsByOutcome(AnalyticsWindow window) {
        Q q = new Q();
        q.add("SELECT t.state AS label, 0 AS amount, count(*) AS n FROM site_visits t WHERE " + leadScope("t") + " AND t.status <> 5");
        window(q, "t.requested_at", window);
        q.add(" GROUP BY 1 ORDER BY 3 DESC");
        return jdbc.query(q.sql.toString(), SLICE, q.params.toArray());
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    private Map<YearMonth, BigDecimal> monthMap(Q q) {
        Map<YearMonth, BigDecimal> out = new HashMap<>();
        jdbc.query(q.sql.toString(), (RowCallbackHandler) rs -> {
            LocalDate bucket = rs.getDate("bucket").toLocalDate();
            out.put(YearMonth.from(bucket), money(rs, "value"));
        }, q.params.toArray());
        return out;
    }

    private static final org.springframework.jdbc.core.RowMapper<Slice> SLICE = (rs, i) ->
            new Slice(pretty(rs.getString("label")), money(rs, "amount"), rs.getInt("n"));

    private static BigDecimal money(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? BigDecimal.ZERO : value;
    }

    /** UNDER_REVIEW → Under review; a name that is already words is left alone. */
    static String pretty(String code) {
        if (code == null || code.isBlank()) return "Other";
        if (!code.equals(code.toUpperCase(Locale.ROOT))) return code;
        String word = code.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }
}
