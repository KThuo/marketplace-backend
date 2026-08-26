package com.hodi.modules.reports;

import com.hodi.common.ApiResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.reports.ReportService.FilterOption;
import com.hodi.modules.reports.ReportService.ReportQuery;
import com.hodi.modules.reports.ReportService.ReportResult;
import com.hodi.modules.reports.ReportService.ReportSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reports (M15).
 *
 * <p>A catalogue, a run, its filter options, and an export in three formats. Everything a request can vary
 * is either a number, a value bound as a parameter, or the name of a column the report already declares —
 * and that last case is checked against the catalogue before it goes anywhere near the SQL.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reports;

    @GetMapping
    @PreAuthorize("hasAuthority('REPORTS_VIEW')")
    public ApiResponse<List<ReportSummary>> catalogue() {
        return ApiResponse.success(reports.catalogue());
    }

    /** The filters this report offers, with the option lists read from the caller's own scope. */
    @GetMapping("/{code}/filters")
    @PreAuthorize("hasAuthority('REPORTS_VIEW')")
    public ApiResponse<List<FilterOption>> filters(@PathVariable String code) {
        return ApiResponse.success(reports.filters(code));
    }

    @GetMapping("/{code}")
    @PreAuthorize("hasAuthority('REPORTS_VIEW')")
    public ApiResponse<ReportResult> run(
            @PathVariable String code,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<String> columns,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam Map<String, String> all) {
        return ApiResponse.success(reports.run(code, query(from, to, search, columns, page, size, all)));
    }

    /**
     * Filters arrive as {@code filter.<column>=<value>} and are picked out of the raw parameter map.
     *
     * <p>A prefix rather than a fixed set of parameters, because which filters exist is a property of the
     * report and this controller should not have to know. Prefixed rather than bare so a filter can never
     * collide with {@code page}, {@code size} or a future parameter of this endpoint — and the column is
     * still checked against the report's declarations in the service before it reaches the SQL.
     */
    private ReportQuery query(LocalDate from, LocalDate to, String search, List<String> columns,
                              int page, int size, Map<String, String> all) {
        Map<String, String> filters = new LinkedHashMap<>();
        all.forEach((k, v) -> {
            if (k.startsWith("filter.") && k.length() > 7) filters.put(k.substring(7), v);
        });
        return new ReportQuery(from, to, search, filters,
                columns == null ? List.of() : columns, page, size);
    }

    /**
     * The same rows as a file.
     *
     * <p>A separate permission: reading a figure on a screen and walking out with the underlying rows are
     * different acts, and only one of them leaves the building.
     */
    @GetMapping("/{code}/export.{format}")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT')")
    @RequestAction("EXPORT A REPORT")
    public ResponseEntity<byte[]> export(
            @PathVariable String code,
            @PathVariable String format,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<String> columns,
            @RequestParam Map<String, String> all) {
        ReportQuery q = query(from, to, search, columns, 0, 0, all);
        String kind = format == null ? "csv" : format.toLowerCase();

        byte[] body;
        String type;
        switch (kind) {
            case "csv" -> {
                body = reports.csv(code, q).getBytes(StandardCharsets.UTF_8);
                type = "text/csv; charset=UTF-8";
            }
            case "xlsx" -> {
                body = reports.xlsx(code, q);
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            }
            case "pdf" -> {
                body = reports.pdf(code, q);
                type = "application/pdf";
            }
            default -> throw new com.hodi.common.exception.HodiException(
                    "A report can be exported as csv, xlsx or pdf — not \"" + kind + "\".",
                    org.springframework.http.HttpStatus.BAD_REQUEST);
        }

        String filename = code.toLowerCase() + "-" + LocalDate.now() + "." + kind;
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(type))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }
}
