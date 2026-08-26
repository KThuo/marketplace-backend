package com.hodi.modules.reports;

import com.hodi.common.exception.HodiException;
import com.hodi.security.TenantScope;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Running a report (M15).
 *
 * <h2>Declared, not composed</h2>
 *
 * <p>Every part of the SQL below comes from {@link ReportCatalogue}: the view, the date column, the column
 * list. The request supplies a report code, two dates and a page size — and the code is matched against the
 * catalogue rather than interpolated. That is the property that makes assembling native SQL here defensible
 * at all; a report engine that took a table name or an ORDER BY from a request would be an injection surface
 * wearing a business-intelligence hat.
 *
 * <h2>Scoping is spliced, not remembered</h2>
 *
 * <p>Every view carries {@code tenant_id}, and {@code TenantScope.sqlPredicate} produces the predicate.
 * Platform staff get {@code TRUE}, a seller gets their own id, a lender gets the sellers they are partnered
 * with, and somebody with nothing in view gets {@code FALSE} — which returns an empty report rather than
 * everybody's.
 *
 * <p>This is the first use of {@code sqlPredicate}, which existed for exactly this and had never been
 * called.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    /** A report is a page of a screen, not a data dump. Exports raise it; nothing removes it. */
    private static final int MAX_ROWS = 500;
    private static final int MAX_EXPORT_ROWS = 20_000;

    private final JdbcTemplate jdbc;

    public record ColumnSpec(String label, String key, boolean numeric) {}

    public record ReportSummary(String code, String name, String description, boolean platformOnly) {}

    public record ReportResult(
            String code,
            String name,
            String description,
            List<ColumnSpec> columns,
            List<Map<String, Object>> rows,
            /** Column key → total, for the numeric ones. Empty when the report has none. */
            Map<String, BigDecimal> totals,
            int rowCount,
            boolean truncated,
            LocalDate from,
            LocalDate to,
            OffsetDateTime generatedAt) {}

    /** The reports this caller may run. */
    public List<ReportSummary> catalogue() {
        boolean platform = AuthContext.current().map(p -> p.isPlatformStaff()).orElse(false);
        return ReportCatalogue.all().stream()
                .filter(r -> platform || !r.platformOnly())
                .map(r -> new ReportSummary(r.code(), r.name(), r.description(), r.platformOnly()))
                .toList();
    }

    @Transactional(readOnly = true)
    public ReportResult run(String code, LocalDate from, LocalDate to, Integer limit) {
        ReportCatalogue.Report report = require(code);
        int cap = Math.min(limit == null || limit <= 0 ? MAX_ROWS : limit, MAX_EXPORT_ROWS);

        List<Map<String, Object>> rows = query(report, from, to, cap + 1);
        boolean truncated = rows.size() > cap;
        if (truncated) rows = rows.subList(0, cap);

        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (String column : report.numeric()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Map<String, Object> row : rows) {
                Object value = row.get(column);
                if (value instanceof Number number) sum = sum.add(new BigDecimal(number.toString()));
            }
            totals.put(column, sum);
        }

        return new ReportResult(report.code(), report.name(), report.description(),
                columnsOf(report), rows, totals, rows.size(), truncated, from, to,
                OffsetDateTime.now());
    }

    /** The same rows, as CSV. Raised limit, because an export is the one place a dump is the point. */
    @Transactional(readOnly = true)
    public String csv(String code, LocalDate from, LocalDate to) {
        ReportCatalogue.Report report = require(code);
        List<Map<String, Object>> rows = query(report, from, to, MAX_EXPORT_ROWS);
        List<ColumnSpec> columns = columnsOf(report);

        StringBuilder out = new StringBuilder();
        out.append(String.join(",", columns.stream().map(c -> escape(c.label())).toList())).append('\n');
        for (Map<String, Object> row : rows) {
            List<String> cells = new ArrayList<>(columns.size());
            for (ColumnSpec column : columns) {
                Object value = row.get(column.key());
                cells.add(escape(value == null ? "" : String.valueOf(value)));
            }
            out.append(String.join(",", cells)).append('\n');
        }
        log.info("Exported {} rows of {} for {}", rows.size(), report.code(), AuthContext.username());
        return out.toString();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private List<Map<String, Object>> query(ReportCatalogue.Report report, LocalDate from, LocalDate to,
                                            int limit) {
        String columns = String.join(", ", report.columns().values());
        String scope = TenantScope.sqlPredicate("tenant_id");

        StringBuilder sql = new StringBuilder("SELECT ").append(columns)
                .append(" FROM ").append(report.view())
                .append(" WHERE ").append(scope);

        List<Object> params = new ArrayList<>(2);
        if (from != null) {
            sql.append(" AND ").append(report.dateColumn()).append(" >= ?");
            params.add(java.sql.Timestamp.valueOf(from.atStartOfDay()));
        }
        if (to != null) {
            // Exclusive on the day after, so "to 30 June" includes everything on 30 June.
            sql.append(" AND ").append(report.dateColumn()).append(" < ?");
            params.add(java.sql.Timestamp.valueOf(to.plusDays(1).atStartOfDay()));
        }
        sql.append(" ORDER BY ").append(report.dateColumn()).append(" DESC NULLS LAST")
                .append(" LIMIT ").append(limit);

        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    private ReportCatalogue.Report require(String code) {
        ReportCatalogue.Report report = ReportCatalogue.byCode(code);
        if (report == null) {
            throw new HodiException("There is no report called \"" + code + "\".", HttpStatus.NOT_FOUND);
        }
        boolean platform = AuthContext.current().map(p -> p.isPlatformStaff()).orElse(false);
        if (report.platformOnly() && !platform) {
            throw new HodiException("That report is the platform's.", HttpStatus.FORBIDDEN);
        }
        return report;
    }

    private List<ColumnSpec> columnsOf(ReportCatalogue.Report report) {
        return report.columns().entrySet().stream()
                .map(e -> new ColumnSpec(e.getKey(), e.getValue(),
                        report.numeric().contains(e.getValue())))
                .toList();
    }

    /**
     * CSV, carefully.
     *
     * <p>A leading {@code =}, {@code +}, {@code -} or {@code @} is prefixed with an apostrophe: a cell
     * beginning with one of those is a formula when the file is opened in a spreadsheet, and a listing
     * titled {@code =cmd|…} is how an export becomes an attack on whoever opens it.
     */
    private static String escape(String value) {
        String cell = value;
        if (!cell.isEmpty() && "=+-@".indexOf(cell.charAt(0)) >= 0) cell = "'" + cell;
        if (cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
            return '"' + cell.replace("\"", "\"\"") + '"';
        }
        return cell;
    }
}
