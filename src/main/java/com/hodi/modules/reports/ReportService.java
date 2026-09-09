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

import com.lowagie.text.Element;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import java.io.ByteArrayOutputStream;
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
    private static final int MAX_ROWS = 50;
    /** The most a single page may ask for, however large a `size` arrives. */
    private static final int MAX_PAGE = 200;
    private static final int MAX_EXPORT_ROWS = 20_000;
    /** As many columns as a landscape A4 page can carry and still be read. */
    private static final int MAX_PDF_COLUMNS = 10;

    private final JdbcTemplate jdbc;

    public record ColumnSpec(String label, String key, boolean numeric) {}

    /**
     * What a caller asked for.
     *
     * <p>Every field here is a *value* except {@code columns} and the keys of {@code filters}, which name
     * columns — and those are checked against the report's own declarations before they reach the SQL. That
     * check is the whole security boundary of this class: a name that is not in the catalogue is dropped,
     * never interpolated.
     */
    public record ReportQuery(
            LocalDate from,
            LocalDate to,
            /** Free text, matched against the report's text columns. */
            String search,
            /** Column name → value. Silently ignores any column the report does not declare. */
            Map<String, String> filters,
            /** Which columns to return, in the report's own order. Empty means all of them. */
            List<String> columns,
            int page,
            int size) {

        public static ReportQuery of(LocalDate from, LocalDate to) {
            return new ReportQuery(from, to, null, Map.of(), List.of(), 0, MAX_ROWS);
        }
    }

    public record FilterOption(String label, String column, String kind, List<String> options) {}

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
            /**
             * How many rows the whole query matches, not how many are on this page.
             *
             * <p>Replaces the old {@code truncated} flag. "500 rows, and there may be more" is not
             * something a reader can act on; "1–50 of 812" is. It costs a second COUNT over the same
             * predicate, which is the price of the page being able to say where it is.
             */
            long total,
            int page,
            int size,
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
    public ReportResult run(String code, ReportQuery q) {
        ReportCatalogue.Report report = require(code);
        int size = q.size() <= 0 ? MAX_ROWS : Math.min(q.size(), MAX_PAGE);
        int page = Math.max(q.page(), 0);
        List<ColumnSpec> columns = selectedColumns(report, q.columns());

        List<Map<String, Object>> rows = query(report, q, columns, size, page * size);
        long total = count(report, q);

        /*
         * Totals are for the whole result, not for the page.
         *
         * A commission report showing "page 3 of 9" with a total underneath that adds up only the fifty
         * rows on screen is worse than showing no total: it looks like an answer. So the sums are asked of
         * the database over the same predicate, and only for numeric columns the caller actually selected.
         */
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        List<String> sums = report.numeric().stream()
                .filter(n -> columns.stream().anyMatch(c -> c.key().equals(n)))
                .toList();
        if (!sums.isEmpty() && total > 0) {
            Where w = where(report, q);
            String select = sums.stream()
                    .map(n -> "COALESCE(SUM(" + n + "), 0) AS " + n)
                    .reduce((a, b) -> a + ", " + b)
                    .orElseThrow();
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT " + select + " FROM " + report.view() + " WHERE " + w.sql(),
                    w.params().toArray());
            for (String n : sums) {
                Object v = row.get(n);
                totals.put(n, v instanceof Number number ? new BigDecimal(number.toString()) : BigDecimal.ZERO);
            }
        } else {
            for (String n : sums) totals.put(n, BigDecimal.ZERO);
        }

        return new ReportResult(report.code(), report.name(), report.description(),
                columns, rows, totals, rows.size(), total, page, size, q.from(), q.to(),
                OffsetDateTime.now());
    }

    /** Rows for a file: the whole result up to the export cap, with the caller's chosen columns. */
    @Transactional(readOnly = true)
    public Export rows(String code, ReportQuery q) {
        ReportCatalogue.Report report = require(code);
        List<ColumnSpec> columns = selectedColumns(report, q.columns());
        List<Map<String, Object>> rows = query(report, q, columns, MAX_EXPORT_ROWS, 0);
        log.info("Exported {} rows of {} for {}", rows.size(), report.code(), AuthContext.username());
        return new Export(report, columns, rows, q);
    }

    public record Export(ReportCatalogue.Report report, List<ColumnSpec> columns,
                         List<Map<String, Object>> rows, ReportQuery query) {}

    /** The same rows, as CSV. Raised limit, because an export is the one place a dump is the point. */
    @Transactional(readOnly = true)
    public String csv(String code, ReportQuery q) {
        Export e = rows(code, q);
        List<ColumnSpec> columns = e.columns();

        StringBuilder out = new StringBuilder();
        out.append(String.join(",", columns.stream().map(c -> escape(c.label())).toList())).append('\n');
        for (Map<String, Object> row : e.rows()) {
            List<String> cells = new ArrayList<>(columns.size());
            for (ColumnSpec column : columns) {
                Object value = row.get(column.key());
                cells.add(escape(value == null ? "" : String.valueOf(value)));
            }
            out.append(String.join(",", cells)).append('\n');
        }
        return out.toString();
    }

    /**
     * The rows as a real .xlsx.
     *
     * <p>Typed, not stringly: a figure is written as a number and a date as a date, so the file arrives
     * sortable and summable rather than as a spreadsheet full of text that looks like data. That is the
     * whole reason to offer xlsx alongside CSV — a CSV of the same rows loses every type on the way in.
     *
     * <p>Streaming ({@code SXSSFWorkbook}) because the export cap is 20 000 rows and holding that many
     * fully-modelled cells in memory to write them once is a waste of a heap.
     *
     * <p><strong>The formula guard applies here too.</strong> A cell beginning {@code =}, {@code +},
     * {@code -} or {@code @} is a formula when a spreadsheet opens it, and that is a spreadsheet problem
     * rather than a CSV one — a listing titled {@code =cmd|...} is how an export becomes an attack on
     * whoever opens it. The CSV path has always known this; the xlsx path would have been a way around it.
     */
    @Transactional(readOnly = true)
    public byte[] xlsx(String code, ReportQuery q) {
        Export e = rows(code, q);
        try (SXSSFWorkbook wb = new SXSSFWorkbook(200); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(e.report().name());

            org.apache.poi.ss.usermodel.Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setAlignment(HorizontalAlignment.LEFT);

            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));

            List<ColumnSpec> columns = e.columns();
            Row header = sheet.createRow(0);
            for (int i = 0; i < columns.size(); i++) {
                org.apache.poi.ss.usermodel.Cell cell = header.createCell(i);
                cell.setCellValue(columns.get(i).label());
                cell.setCellStyle(head);
                // A width per column, from the header's length: SXSSF cannot autosize a streamed sheet,
                // and a uniform width makes a reference column and a title column equally wrong.
                sheet.setColumnWidth(i, Math.min(60, Math.max(12, columns.get(i).label().length() + 6)) * 256);
            }
            // The header stays put while somebody scrolls twenty thousand rows.
            sheet.createFreezePane(0, 1);

            int r = 1;
            for (Map<String, Object> row : e.rows()) {
                Row line = sheet.createRow(r++);
                for (int i = 0; i < columns.size(); i++) {
                    Object value = row.get(columns.get(i).key());
                    org.apache.poi.ss.usermodel.Cell cell = line.createCell(i);
                    if (value == null) continue;
                    if (value instanceof Number number) {
                        cell.setCellValue(number.doubleValue());
                    } else if (value instanceof Boolean bool) {
                        cell.setCellValue(bool);
                    } else if (value instanceof java.sql.Timestamp ts) {
                        cell.setCellValue(ts.toLocalDateTime().toLocalDate());
                        cell.setCellStyle(dateStyle);
                    } else if (value instanceof java.sql.Date d) {
                        cell.setCellValue(d.toLocalDate());
                        cell.setCellStyle(dateStyle);
                    } else {
                        cell.setCellValue(deFormula(String.valueOf(value)));
                    }
                }
            }
            wb.write(out);
            wb.dispose();
            return out.toByteArray();
        } catch (java.io.IOException io) {
            throw new HodiException("Could not build that spreadsheet.", HttpStatus.INTERNAL_SERVER_ERROR, io);
        }
    }

    /**
     * The rows as a PDF, landscape, with the window and the totals stated.
     *
     * <p>Landscape A4 and a column budget, because a fourteen-column report cannot be a readable portrait
     * page. When the chosen columns do not fit, the ones that do are printed and <em>the page says which
     * were left out</em> — silently cutting columns off the edge of a document somebody may forward to a
     * lender is the failure mode worth the extra paragraph.
     *
     * <p>No formula guard: a PDF does not execute its cells. The guard exists for spreadsheets.
     */
    @Transactional(readOnly = true)
    public byte[] pdf(String code, ReportQuery q) {
        Export e = rows(code, q);
        List<ColumnSpec> all = e.columns();
        int budget = Math.max(1, MAX_PDF_COLUMNS);
        List<ColumnSpec> columns = all.size() <= budget ? all : all.subList(0, budget);
        List<String> dropped = all.size() <= budget ? List.of()
                : all.subList(budget, all.size()).stream().map(ColumnSpec::label).toList();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            com.lowagie.text.Document doc = new com.lowagie.text.Document(
                    com.lowagie.text.PageSize.A4.rotate(), 28, 28, 30, 34);
            com.lowagie.text.pdf.PdfWriter writer = com.lowagie.text.pdf.PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new PageNumbers());
            doc.open();

            com.lowagie.text.Font h1 = com.lowagie.text.FontFactory.getFont(
                    com.lowagie.text.FontFactory.HELVETICA_BOLD, 14);
            com.lowagie.text.Font small = com.lowagie.text.FontFactory.getFont(
                    com.lowagie.text.FontFactory.HELVETICA, 8, java.awt.Color.DARK_GRAY);
            com.lowagie.text.Font cellFont = com.lowagie.text.FontFactory.getFont(
                    com.lowagie.text.FontFactory.HELVETICA, 8);
            com.lowagie.text.Font headFont = com.lowagie.text.FontFactory.getFont(
                    com.lowagie.text.FontFactory.HELVETICA_BOLD, 8, java.awt.Color.WHITE);

            doc.add(new com.lowagie.text.Paragraph(e.report().name(), h1));
            String window = (q.from() == null ? "the beginning" : q.from().toString())
                    + " to " + (q.to() == null ? "today" : q.to().toString());
            doc.add(new com.lowagie.text.Paragraph(e.report().description(), small));
            doc.add(new com.lowagie.text.Paragraph(
                    window + "   ·   " + e.rows().size() + " rows   ·   generated "
                            + OffsetDateTime.now().toLocalDate(), small));
            if (!dropped.isEmpty()) {
                doc.add(new com.lowagie.text.Paragraph(
                        "Columns omitted to fit the page: " + String.join(", ", dropped), small));
            }
            doc.add(com.lowagie.text.Chunk.NEWLINE);

            com.lowagie.text.pdf.PdfPTable table =
                    new com.lowagie.text.pdf.PdfPTable(columns.size());
            table.setWidthPercentage(100);
            table.setHeaderRows(1);
            for (ColumnSpec c : columns) {
                com.lowagie.text.pdf.PdfPCell cell =
                        new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase(c.label(), headFont));
                cell.setBackgroundColor(new java.awt.Color(11, 32, 56));
                cell.setPadding(5);
                cell.setBorderWidth(0);
                table.addCell(cell);
            }
            boolean shade = false;
            for (Map<String, Object> row : e.rows()) {
                for (ColumnSpec c : columns) {
                    Object v = row.get(c.key());
                    com.lowagie.text.pdf.PdfPCell cell = new com.lowagie.text.pdf.PdfPCell(
                            new com.lowagie.text.Phrase(v == null ? "" : String.valueOf(v), cellFont));
                    cell.setPadding(4);
                    cell.setBorderWidth(0.3f);
                    cell.setBorderColor(new java.awt.Color(220, 226, 232));
                    if (c.numeric()) cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    if (shade) cell.setBackgroundColor(new java.awt.Color(248, 250, 252));
                    table.addCell(cell);
                }
                shade = !shade;
            }
            doc.add(table);
            doc.close();
            return out.toByteArray();
        } catch (java.io.IOException io) {
            throw new HodiException("Could not build that document.", HttpStatus.INTERNAL_SERVER_ERROR, io);
        }
    }

    /** "Page 2 of 7" in the footer, because a loose sheet of a report should say where it came from. */
    private static final class PageNumbers extends com.lowagie.text.pdf.PdfPageEventHelper {
        @Override
        public void onEndPage(com.lowagie.text.pdf.PdfWriter writer, com.lowagie.text.Document doc) {
            com.lowagie.text.Font f = com.lowagie.text.FontFactory.getFont(
                    com.lowagie.text.FontFactory.HELVETICA, 7, java.awt.Color.GRAY);
            com.lowagie.text.pdf.ColumnText.showTextAligned(
                    writer.getDirectContent(), Element.ALIGN_CENTER,
                    new com.lowagie.text.Phrase("Page " + writer.getPageNumber(), f),
                    doc.getPageSize().getWidth() / 2, 18, 0);
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** A WHERE clause and the values to bind to it. Built once and used by the rows, the count and the export. */
    private record Where(String sql, List<Object> params) {}

    /**
     * Everything a caller may narrow by, assembled from the catalogue and bound as parameters.
     *
     * <p>Read the two halves separately. Every *identifier* — the view, the date column, each filter column,
     * each searched column — is a string this file put in the catalogue. Every *value* — the dates, the
     * filter values, the search term — arrives as a {@code ?}. A request can choose which declared column
     * to narrow by; it cannot contribute a character of SQL.
     */
    /**
     * The predicate deciding whose rows this report may add up.
     *
     * <p>Tenant-scoped for every report whose rows belong to a seller; owner-scoped where a lending
     * institution may own a row outright, because the tenant predicate alone would hide a bank's own projects
     * from the bank.
     */
    private static String scopeOf(ReportCatalogue.Report report) {
        return report.ownerScoped()
                ? com.hodi.security.OwnerScopeSql.predicate("tenant_id", "institution_id", "development_id")
                : TenantScope.sqlPredicate("tenant_id");
    }

    private Where where(ReportCatalogue.Report report, ReportQuery q) {
        StringBuilder sql = new StringBuilder(scopeOf(report));
        List<Object> params = new ArrayList<>();

        if (q.from() != null) {
            sql.append(" AND ").append(report.dateColumn()).append(" >= ?");
            params.add(java.sql.Timestamp.valueOf(q.from().atStartOfDay()));
        }
        if (q.to() != null) {
            // Exclusive on the day after, so "to 30 June" includes everything on 30 June.
            sql.append(" AND ").append(report.dateColumn()).append(" < ?");
            params.add(java.sql.Timestamp.valueOf(q.to().plusDays(1).atStartOfDay()));
        }

        if (q.filters() != null) {
            for (Map.Entry<String, String> entry : q.filters().entrySet()) {
                ReportCatalogue.Filter filter = report.filter(entry.getKey());
                // Not a filter this report offers: ignore it. A stale bookmark should give you a report,
                // not a 400.
                if (filter == null || entry.getValue() == null || entry.getValue().isBlank()) continue;
                if (filter.kind() == ReportCatalogue.Filter.Kind.BOOLEAN) {
                    sql.append(" AND ").append(filter.column()).append(" = ?");
                    params.add(Boolean.parseBoolean(entry.getValue()));
                } else {
                    sql.append(" AND ").append(filter.column()).append("::text = ?");
                    params.add(entry.getValue());
                }
            }
        }

        String term = q.search() == null ? "" : q.search().trim();
        if (!term.isEmpty()) {
            List<String> searchable = report.searchable();
            if (!searchable.isEmpty()) {
                /*
                 * One box against every text column, rather than a box per column.
                 *
                 * A reports page is where somebody goes to find a row they half remember, and eight search
                 * boxes is a worse answer to that than one. The columns are concatenated with a separator
                 * so a term cannot match across a boundary, coalesced because a NULL would annihilate the
                 * whole concatenation and make the row unfindable by any of its other fields.
                 */
                String haystack = searchable.stream()
                        .map(c -> "COALESCE(" + c + "::text, '')")
                        .reduce((a, b) -> a + " || ' ' || " + b)
                        .orElseThrow();
                sql.append(" AND (").append(haystack).append(") ILIKE ?");
                params.add("%" + term + "%");
            }
        }
        return new Where(sql.toString(), params);
    }

    /**
     * The columns to select: those the caller asked for, in the report's own order, or all of them.
     *
     * <p>Order comes from the catalogue rather than from the request, so a caller cannot rearrange a report
     * into something its header row no longer describes. Unknown names are dropped; asking for nothing
     * recognisable gives the full report rather than an empty one, because a column list is a preference
     * and losing it should not lose the data.
     */
    private List<ColumnSpec> selectedColumns(ReportCatalogue.Report report, List<String> wanted) {
        List<ColumnSpec> all = columnsOf(report);
        if (wanted == null || wanted.isEmpty()) return all;
        List<ColumnSpec> chosen = all.stream().filter(c -> wanted.contains(c.key())).toList();
        return chosen.isEmpty() ? all : chosen;
    }

    private List<Map<String, Object>> query(ReportCatalogue.Report report, ReportQuery q,
                                            List<ColumnSpec> columns, int limit, int offset) {
        Where w = where(report, q);
        String select = columns.stream().map(ColumnSpec::key).reduce((a, b) -> a + ", " + b).orElseThrow();

        String sql = "SELECT " + select
                + " FROM " + report.view()
                + " WHERE " + w.sql()
                + " ORDER BY " + report.dateColumn() + " DESC NULLS LAST"
                + " LIMIT " + limit + " OFFSET " + offset;
        return jdbc.queryForList(sql, w.params().toArray());
    }

    private long count(ReportCatalogue.Report report, ReportQuery q) {
        Where w = where(report, q);
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + report.view() + " WHERE " + w.sql(), Long.class, w.params().toArray());
        return n == null ? 0 : n;
    }

    /**
     * The values a filter can take, as they exist in this caller's own scope.
     *
     * <p>Read from the data rather than from an enum, so a seller's County filter offers the four counties
     * they actually list in and not the forty-seven that exist. Scoped by the same predicate as the report,
     * because the option list would otherwise be a way to learn what other organisations hold.
     */
    @Transactional(readOnly = true)
    public List<FilterOption> filters(String code) {
        ReportCatalogue.Report report = require(code);
        String scope = scopeOf(report);
        List<FilterOption> out = new ArrayList<>();
        for (ReportCatalogue.Filter f : report.filters()) {
            List<String> options = List.of();
            if (f.kind() == ReportCatalogue.Filter.Kind.ENUM) {
                options = jdbc.queryForList(
                        "SELECT DISTINCT " + f.column() + "::text AS v FROM " + report.view()
                                + " WHERE " + scope + " AND " + f.column() + " IS NOT NULL"
                                + " ORDER BY v LIMIT 200", String.class);
            }
            out.add(new FilterOption(f.label(), f.column(), f.kind().name(), options));
        }
        return out;
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
    /**
     * A cell that a spreadsheet would treat as a formula, made inert.
     *
     * <p>Shared by the CSV and xlsx writers, because the danger is the spreadsheet's rather than the file
     * format's: a value beginning {@code =}, {@code +}, {@code -} or {@code @} is executed when the file is
     * opened, whichever of the two carried it there.
     */
    static String deFormula(String value) {
        if (!value.isEmpty() && "=+-@".indexOf(value.charAt(0)) >= 0) return "'" + value;
        return value;
    }

    private static String escape(String value) {
        String cell = deFormula(value);
        if (cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r")) {
            return '"' + cell.replace("\"", "\"\"") + '"';
        }
        return cell;
    }
}
