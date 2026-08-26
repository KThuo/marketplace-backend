package com.hodi.modules.reports;

import com.hodi.common.ApiResponse;
import com.hodi.logging.RequestAction;
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
import java.util.List;

/**
 * Reports (M15).
 *
 * <p>Two verbs and a catalogue. Everything a request can vary — which report, which window, how many rows —
 * is checked against the catalogue or is a number; nothing from the request reaches the SQL as text.
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

    @GetMapping("/{code}")
    @PreAuthorize("hasAuthority('REPORTS_VIEW')")
    public ApiResponse<ReportResult> run(
            @PathVariable String code,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer limit) {
        return ApiResponse.success(reports.run(code, from, to, limit));
    }

    /**
     * The same rows as a file.
     *
     * <p>A separate permission: reading a figure on a screen and walking out with the underlying rows are
     * different acts, and only one of them leaves the building.
     */
    @GetMapping("/{code}/export")
    @PreAuthorize("hasAuthority('REPORTS_EXPORT')")
    @RequestAction("EXPORT A REPORT")
    public ResponseEntity<byte[]> export(
            @PathVariable String code,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        String csv = reports.csv(code, from, to);
        String filename = code.toLowerCase() + "-" + LocalDate.now() + ".csv";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }
}
