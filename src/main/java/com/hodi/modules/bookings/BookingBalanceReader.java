package com.hodi.modules.bookings;

import com.hodi.modules.bookings.BookingDtos.BalanceRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;

/**
 * Reads {@code v_booking_balances}, and is the only thing that does.
 *
 * <h2>Why a view and not a calculation in Java</h2>
 *
 * <p>There is no ledger in this system, so a stored {@code paid_total} would have no referee: the first time it
 * disagreed with the payments beneath it, nothing could say which was right. And a balance computed in the
 * service would be a second implementation — the screen's figure and the report's figure would agree until the
 * day one of them was changed.
 *
 * <p>So one view, read here, used by both.
 *
 * <h2>Why JDBC rather than JPA</h2>
 *
 * <p>A view has no identity and nothing maps a nullable aggregate cleanly. It is also what the reporting paths
 * already use, and a view read two ways is a view somebody has to keep two mappings for.
 *
 * <p>Every query is parameterised. The {@code tenant_id} column is on the view specifically so
 * {@code TenantScope.sqlPredicate} can be spliced onto it by the report paths — an aggregate that cannot be
 * scoped is where one organisation reads another's sales figures.
 */
@Component
@RequiredArgsConstructor
public class BookingBalanceReader {

    private final JdbcTemplate jdbc;

    private static final String COLUMNS = """
            booking_id, reference, state, currency, price_agreed, deposit_due,
            scheduled, paid, balance, overdue, next_due_on
            """;

    @Transactional(readOnly = true)
    public Optional<BalanceRow> forBooking(Long bookingId) {
        if (bookingId == null) return Optional.empty();
        List<BalanceRow> rows = jdbc.query(
                "select " + COLUMNS + " from v_booking_balances where booking_id = ?",
                (rs, i) -> map(rs), bookingId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /**
     * Every booking on a development with something outstanding, worst first.
     *
     * <p>The chase list. Ordered by what is overdue rather than by the balance, because a buyer three years
     * into a plan owes a lot and is not behind on anything — sorting by balance would put them at the top and
     * bury the one who missed last month's instalment.
     */
    @Transactional(readOnly = true)
    public List<BalanceRow> arrearsForDevelopment(Long developmentId) {
        return jdbc.query(
                "select " + COLUMNS + " from v_booking_balances "
                        + "where development_id = ? and state in ('RESERVED', 'AGREED') and overdue > 0 "
                        + "order by overdue desc, next_due_on nulls last",
                (rs, i) -> map(rs), developmentId);
    }

    private BalanceRow map(ResultSet rs) throws java.sql.SQLException {
        Date next = rs.getDate("next_due_on");
        return new BalanceRow(
                rs.getLong("booking_id"),
                rs.getString("reference"),
                rs.getString("state"),
                rs.getString("currency"),
                rs.getBigDecimal("price_agreed"),
                rs.getBigDecimal("deposit_due"),
                zeroIfNull(rs.getBigDecimal("scheduled")),
                zeroIfNull(rs.getBigDecimal("paid")),
                zeroIfNull(rs.getBigDecimal("balance")),
                zeroIfNull(rs.getBigDecimal("overdue")),
                next == null ? null : next.toLocalDate());
    }

    /** A booking with no schedule and no payments reads as zero, not as null. */
    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
