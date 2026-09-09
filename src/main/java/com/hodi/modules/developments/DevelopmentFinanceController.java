package com.hodi.modules.developments;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.developments.DevelopmentFinanceDtos.*;
import com.hodi.modules.kyc.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * A development's money: the summary, the cost ledger, the facility drawdowns and their evidence.
 *
 * <p>Nested under the development, like its phases and units, because every check starts from it: whether
 * the caller may see the project decides whether they may see its money, and {@code DEVELOPMENTS_FINANCE_VIEW}
 * decides whether they may see money at all — a collaborator posting progress on a bank's project holds the
 * first without the second.
 */
@RestController
@RequestMapping("/api/v1/developments/{hashId}/finance")
@RequiredArgsConstructor
public class DevelopmentFinanceController {

    private final DevelopmentFinanceService service;

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_FINANCE_VIEW')")
    public ApiResponse<FinanceSummary> summary(@PathVariable String hashId) {
        return ApiResponse.success(service.summary(hashId));
    }

    // ── the ledger ────────────────────────────────────────────────────────────

    @GetMapping("/expenditures")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_FINANCE_VIEW')")
    public ApiResponse<PagedResponse<ExpenditureResponse>> expenditures(
            @PathVariable String hashId, @ModelAttribute ExpenditureListRequest request) {
        return ApiResponse.success(service.expenditures(hashId, request));
    }

    @PostMapping("/expenditures")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_FINANCE_RECORD')")
    @RequestAction("RECORD_DEVELOPMENT_COST")
    public ApiResponse<ExpenditureResponse> record(@PathVariable String hashId,
                                                   @Valid @RequestBody RecordExpenditureRequest request) {
        ExpenditureResponse saved = service.recordExpenditure(hashId, request);
        return ApiResponse.success("Cost " + saved.reference() + " recorded", saved);
    }

    @PostMapping("/expenditures/{expenditureId}/void")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_FINANCE_RECORD')")
    @RequestAction("VOID_DEVELOPMENT_COST")
    public ApiResponse<ExpenditureResponse> voidExpenditure(@PathVariable String hashId,
                                                            @PathVariable String expenditureId,
                                                            @Valid @RequestBody VoidRequest request) {
        return ApiResponse.success("Voided", service.voidExpenditure(hashId, expenditureId, request));
    }

    /** The invoice or certificate behind a cost line, into the vault. */
    @PostMapping(value = "/expenditures/{expenditureId}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('DEVELOPMENTS_FINANCE_RECORD')")
    @RequestAction("ATTACH_COST_EVIDENCE")
    public ApiResponse<ExpenditureResponse> attachEvidence(@PathVariable String hashId,
                                                           @PathVariable String expenditureId,
                                                           @RequestParam("file") MultipartFile file) {
        return ApiResponse.success("Evidence attached", service.attachEvidence(hashId, expenditureId, file));
    }

    // ── the facility ──────────────────────────────────────────────────────────

    @GetMapping("/drawdowns")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_FINANCE_VIEW')")
    public ApiResponse<List<DrawdownResponse>> drawdowns(@PathVariable String hashId) {
        return ApiResponse.success(service.drawdowns(hashId));
    }

    @PostMapping("/drawdowns")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_FINANCE_RECORD')")
    @RequestAction("RECORD_FACILITY_DRAWDOWN")
    public ApiResponse<DrawdownResponse> recordDrawdown(@PathVariable String hashId,
                                                        @Valid @RequestBody RecordDrawdownRequest request) {
        DrawdownResponse saved = service.recordDrawdown(hashId, request);
        return ApiResponse.success("Drawdown " + saved.reference() + " recorded", saved);
    }

    @PostMapping("/drawdowns/{drawdownId}/void")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_FINANCE_RECORD')")
    @RequestAction("VOID_FACILITY_DRAWDOWN")
    public ApiResponse<DrawdownResponse> voidDrawdown(@PathVariable String hashId,
                                                      @PathVariable String drawdownId,
                                                      @Valid @RequestBody VoidRequest request) {
        return ApiResponse.success("Voided", service.voidDrawdown(hashId, drawdownId, request));
    }

    @PostMapping(value = "/drawdowns/{drawdownId}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('DEVELOPMENTS_FINANCE_RECORD')")
    @RequestAction("ATTACH_DRAWDOWN_EVIDENCE")
    public ApiResponse<DrawdownResponse> attachDrawdownEvidence(@PathVariable String hashId,
                                                                @PathVariable String drawdownId,
                                                                @RequestParam("file") MultipartFile file) {
        return ApiResponse.success("Evidence attached",
                service.attachDrawdownEvidence(hashId, drawdownId, file));
    }

    // ── evidence ──────────────────────────────────────────────────────────────

    /**
     * The bytes of one piece of evidence, if this caller may see the development's money.
     *
     * <p>Built by hand rather than through the envelope, because a PDF is not JSON. Attachment, never
     * inline, and never cached — the same rules the vault's own download follows.
     */
    @GetMapping("/evidence/{reference}")
    @PreAuthorize("hasAuthority('DEVELOPMENTS_FINANCE_VIEW')")
    @RequestAction("READ COST EVIDENCE")
    public ResponseEntity<byte[]> evidence(@PathVariable String hashId, @PathVariable String reference) {
        DocumentService.Fetched fetched = service.evidence(hashId, reference);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        fetched.contentType() == null ? "application/octet-stream" : fetched.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fetched.fileName() == null ? "evidence" : fetched.fileName())
                        .build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, private")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(fetched.bytes());
    }
}
