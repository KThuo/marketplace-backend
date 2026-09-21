package com.hodi.modules.dashboard;

import com.hodi.modules.analytics.AnalyticsViews.Receipt;
import com.hodi.security.OwnerScopeSql;
import com.hodi.security.hashid.HashIdUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * What is waiting for a person, counted.
 *
 * <p>Each count is the same question the module's own list screen answers when opened on its queue — the
 * statements page on "Unused", the approvals page on "Pending", the offers page on "New" — so the number on
 * the dashboard and the number of rows behind the link agree. The scope predicate is the same one every list
 * uses; nothing from a request is spliced.
 */
@Repository
@RequiredArgsConstructor
public class DashboardAttentionQueries {

    private final JdbcTemplate jdbc;

    /** A count, the money in it where money is the point, and how long the oldest has waited. */
    public record Tally(int count, BigDecimal amount, OffsetDateTime oldest) {
        public boolean any() { return count > 0; }
    }

    /** Credits the bank reported that nobody has placed on a booking. */
    public Tally unplacedCredits() {
        return tally("SELECT count(*), coalesce(sum(s.amount), 0), min(s.paid_at) FROM coop_statements s WHERE "
                + OwnerScopeSql.predicate("s.tenant_id", "s.institution_id", null)
                + " AND s.status <> 5 AND s.state = 'UNMAPPED'");
    }

    /** Requests waiting for a decision that this person may make: not their own. */
    public int approvalsAwaiting(Long userId) {
        return count("SELECT count(*) FROM approval_workflows w WHERE "
                + OwnerScopeSql.predicate("w.tenant_id", "w.institution_id", null)
                + " AND w.status <> 5 AND w.state = 'PENDING' AND w.submitted_by_user_id <> ?", userId);
    }

    public Tally disbursementsAwaitingRelease() {
        return tally("SELECT count(*), coalesce(sum(d.amount), 0), min(d.created_at) FROM disbursements d"
                + " WHERE d.status <> 5 AND d.state = 'AWAITING_APPROVAL'");
    }

    /** Sent, and the bank has said nothing past the time it promised to. */
    public Tally disbursementsUnanswered() {
        return tally("SELECT count(*), coalesce(sum(d.amount), 0), min(d.sent_at) FROM disbursements d"
                + " WHERE d.status <> 5 AND d.state IN ('SENDING', 'SENT')"
                + " AND d.sent_at + make_interval(secs => d.callback_timeout_seconds) < now()");
    }

    /** Prompts the customer's handset was sent that nobody has answered for, past their deadline. */
    public Tally promptsUnanswered() {
        return tally("SELECT count(*), coalesce(sum(i.amount), 0), min(i.created_at) FROM payment_intents i WHERE "
                + OwnerScopeSql.predicate("i.tenant_id", "i.institution_id", null)
                + " AND i.status <> 5 AND i.state = 'PROCESSING'"
                + " AND i.created_at + make_interval(secs => i.callback_timeout_seconds) < now()");
    }

    public Tally offersAwaiting() {
        return tally("SELECT count(*), coalesce(sum(t.offer_amount), 0), min(t.created_at) FROM purchase_requests t WHERE "
                + OwnerScopeSql.predicate("t.tenant_id", null, null)
                + " AND t.status <> 5 AND t.state IN ('SUBMITTED', 'UNDER_REVIEW')");
    }

    public Tally viewingsToConfirm() {
        return tally("SELECT count(*), null, min(t.requested_at) FROM site_visits t WHERE "
                + OwnerScopeSql.predicate("t.tenant_id", null, null)
                + " AND t.status <> 5 AND t.state = 'REQUESTED'");
    }

    public Tally enquiriesAwaiting() {
        return tally("SELECT count(*), null, min(coalesce(t.last_message_at, t.created_at)) FROM enquiry_tickets t WHERE "
                + OwnerScopeSql.predicate("t.tenant_id", null, null)
                + " AND t.status <> 5 AND t.awaiting_seller AND t.state <> 'CLOSED'");
    }

    /** Live bookings with an instalment due and unpaid, and how much. */
    public Tally buyersBehind(Long developmentId) {
        String sql = "SELECT count(*), coalesce(sum(v.overdue), 0), null FROM v_booking_balances v"
                + " JOIN unit_bookings b ON b.id = v.booking_id WHERE "
                + OwnerScopeSql.predicate("b.tenant_id", "b.institution_id", "b.development_id")
                + " AND b.status <> 5 AND b.state IN ('RESERVED', 'AGREED') AND v.overdue > 0";
        return developmentId == null ? tally(sql) : tally(sql + " AND b.development_id = ?", developmentId);
    }

    /** Holds that run out within the next few days: a buyer to chase or a unit about to come back. */
    public Tally holdsLapsing(int days, Long developmentId) {
        String sql = "SELECT count(*), coalesce(sum(b.deposit_due), 0), min(b.expires_at) FROM unit_bookings b WHERE "
                + OwnerScopeSql.predicate("b.tenant_id", "b.institution_id", "b.development_id")
                + " AND b.status <> 5 AND b.state = 'RESERVED' AND b.expires_at IS NOT NULL"
                + " AND b.expires_at BETWEEN now() AND now() + make_interval(days => ?)";
        return developmentId == null ? tally(sql, days) : tally(sql + " AND b.development_id = ?", days, developmentId);
    }

    /** Listings a seller has sent for publication. The platform's queue. */
    public int listingsPending() {
        return count("SELECT count(*) FROM properties p WHERE p.status <> 5 AND p.listing_state = 'PENDING'");
    }

    public int sellerApplicationsPending() {
        return count("SELECT count(*) FROM seller_applications a WHERE a.status <> 5 AND a.decided_at IS NULL");
    }

    public int kycPending() {
        return count("SELECT count(*) FROM user_profiles p WHERE p.status <> 5 AND p.kyc_status IN ('PENDING', 'SUBMITTED')");
    }

    /** The latest receipts, newest first, for the corner of the dashboard that shows money arriving. */
    public List<Receipt> recentReceipts(int limit, Long developmentId) {
        String sql = "SELECT p.id, p.reference, p.paid_on, p.payer_name, p.buyer_name, p.development_name, p.unit_label,"
                + " coalesce(p.payment_type_name, p.method) AS payment_type, p.amount, p.currency"
                + " FROM payments p WHERE " + OwnerScopeSql.predicate("p.tenant_id", "p.institution_id", "p.development_id")
                + " AND p.status = 1" + (developmentId == null ? "" : " AND p.development_id = ?")
                + " ORDER BY p.paid_on DESC, p.id DESC LIMIT ?";
        Object[] args = developmentId == null ? new Object[] {limit} : new Object[] {developmentId, limit};
        return jdbc.query(sql, (rs, i) -> new Receipt(
                HashIdUtil.encodeId(rs.getLong("id")), rs.getString("reference"), rs.getDate("paid_on").toLocalDate(),
                rs.getString("payer_name"), rs.getString("buyer_name"), rs.getString("development_name"),
                rs.getString("unit_label"), rs.getString("payment_type"), rs.getBigDecimal("amount"), rs.getString("currency")),
                args);
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    private Tally tally(String sql, Object... args) {
        Map<String, Object> row = jdbc.queryForMap(sql, args);
        Object[] values = row.values().toArray();
        int count = ((Number) values[0]).intValue();
        BigDecimal amount = values[1] == null ? null : new BigDecimal(values[1].toString());
        OffsetDateTime oldest = values[2] == null ? null
                : values[2] instanceof OffsetDateTime o ? o
                : ((Timestamp) values[2]).toInstant().atOffset(ZoneOffset.UTC);
        return new Tally(count, amount, oldest);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
