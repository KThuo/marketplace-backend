package com.hodi.modules.valuations;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.valuations.ValuationService.*;
import com.hodi.modules.valuations.ValuerService.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Valuation, for all four of its audiences.
 *
 * <p>One controller because it is one workflow seen from four sides, and the permissions do the separating:
 * {@code VALUATIONS_REQUEST} is the requester's, {@code VALUATIONS_ASSIGN} is the platform's (and
 * platform-only, so a seller cannot choose their own valuer), and {@code VALUATIONS_WORK} is the valuer's.
 *
 * <p>Every read passes through {@link ValuationScope}, so {@code VALUATIONS_VIEW} means "see the valuations
 * that are yours" rather than "see valuations" — the permission gets you to the endpoint and the scope
 * decides what comes back.
 */
@RestController
@RequiredArgsConstructor
public class ValuationController {

    private final ValuationService valuations;
    private final ValuerService valuers;

    // ── jobs ──────────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/valuations/list")
    @PreAuthorize("hasAuthority('VALUATIONS_VIEW')")
    public ApiResponse<PagedResponse<ValuationResponse>> list(
            @ModelAttribute ValuationListRequest request) {
        return ApiResponse.success(valuations.list(request));
    }

    @GetMapping("/api/v1/valuations/{reference}")
    @PreAuthorize("hasAuthority('VALUATIONS_VIEW')")
    public ApiResponse<ValuationResponse> find(@PathVariable String reference) {
        return ApiResponse.success(valuations.find(reference));
    }

    @PostMapping("/api/v1/valuations/request")
    @PreAuthorize("hasAuthority('VALUATIONS_REQUEST')")
    @RequestAction("REQUEST VALUATION")
    public ApiResponse<ValuationResponse> raise(@Valid @RequestBody RaiseRequest request) {
        return ApiResponse.success("Valuation requested", valuations.raise(request));
    }

    @PostMapping("/api/v1/valuations/{reference}/assign")
    @PreAuthorize("hasAuthority('VALUATIONS_ASSIGN')")
    @RequestAction("ASSIGN VALUER")
    public ApiResponse<ValuationResponse> assign(@PathVariable String reference,
                                                 @RequestBody(required = false) AssignRequest request) {
        return ApiResponse.success("Valuer assigned", valuations.assign(reference, request));
    }

    @PostMapping("/api/v1/valuations/{reference}/cancel")
    @PreAuthorize("hasAuthority('VALUATIONS_CANCEL')")
    @RequestAction("CANCEL VALUATION")
    public ApiResponse<ValuationResponse> cancel(@PathVariable String reference,
                                                 @RequestBody(required = false) DeclineRequest request) {
        return ApiResponse.success("Cancelled", valuations.cancel(reference, request));
    }

    /** The reviewer's decision on a submitted report: APPROVED, SENT_BACK or REJECTED, with a reason. */
    @PostMapping("/api/v1/valuations/{reference}/review")
    @PreAuthorize("hasAuthority('VALUATIONS_APPROVE')")
    @RequestAction("REVIEW VALUATION REPORT")
    public ApiResponse<ValuationResponse> review(@PathVariable String reference,
                                                 @Valid @RequestBody ReviewRequest request) {
        ValuationResponse decided = valuations.review(reference, request);
        return ApiResponse.success("APPROVED".equalsIgnoreCase(request.decision()) ? "Approved — the figure stands"
                : "Sent back to the valuer", decided);
    }

    @GetMapping("/api/v1/valuations/unassigned-count")
    @PreAuthorize("hasAuthority('VALUATIONS_ASSIGN')")
    public ApiResponse<Map<String, Long>> unassigned() {
        return ApiResponse.success(Map.of("unassigned", valuations.unassignedCount()));
    }

    // ── the valuer's own verbs ────────────────────────────────────────────────

    @PostMapping("/api/v1/valuations/{reference}/accept")
    @PreAuthorize("hasAuthority('VALUATIONS_WORK')")
    @RequestAction("ACCEPT VALUATION")
    public ApiResponse<ValuationResponse> accept(@PathVariable String reference) {
        return ApiResponse.success("Taken on", valuations.accept(reference));
    }

    @PostMapping("/api/v1/valuations/{reference}/decline")
    @PreAuthorize("hasAuthority('VALUATIONS_WORK')")
    @RequestAction("DECLINE VALUATION")
    public ApiResponse<ValuationResponse> decline(@PathVariable String reference,
                                                  @Valid @RequestBody DeclineRequest request) {
        return ApiResponse.success("Handed back", valuations.decline(reference, request));
    }

    @PostMapping("/api/v1/valuations/{reference}/report")
    @PreAuthorize("hasAuthority('VALUATIONS_WORK')")
    @RequestAction("SUBMIT VALUATION REPORT")
    public ApiResponse<ValuationResponse> report(@PathVariable String reference,
                                                 @Valid @RequestBody SubmitReportRequest request) {
        return ApiResponse.success("Report submitted", valuations.submitReport(reference, request));
    }

    @PostMapping("/api/v1/valuations/{reference}/inspection")
    @PreAuthorize("hasAuthority('VALUATIONS_WORK')")
    @RequestAction("BOOK VALUATION INSPECTION")
    public ApiResponse<ValuationResponse> inspection(@PathVariable String reference,
                                                     @Valid @RequestBody InspectionRequest request) {
        return ApiResponse.success("Inspection booked", valuations.scheduleInspection(reference, request));
    }

    @PostMapping(value = "/api/v1/valuations/{reference}/report/document",
            consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('VALUATIONS_WORK')")
    @RequestAction("ATTACH SIGNED VALUATION REPORT")
    public ApiResponse<ValuationResponse> attachReport(
            @PathVariable String reference,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return ApiResponse.success("Signed report attached", valuations.attachReportDocument(reference, file));
    }

    /** The signed report, streamed to anyone the job is visible to. Never cached: it is the bank's evidence. */
    @GetMapping("/api/v1/valuations/{reference}/report/document")
    @PreAuthorize("hasAuthority('VALUATIONS_VIEW')")
    public org.springframework.http.ResponseEntity<byte[]> reportDocument(@PathVariable String reference) {
        com.hodi.modules.kyc.DocumentService.Fetched fetched = valuations.reportDocument(reference);
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        fetched.contentType() == null ? "application/pdf" : fetched.contentType()))
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment()
                                .filename(fetched.fileName() == null ? "valuation-report.pdf" : fetched.fileName())
                                .build().toString())
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, private")
                .body(fetched.bytes());
    }

    // ── the panel ─────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/valuers/list")
    @PreAuthorize("hasAuthority('VALUER_PANEL_VIEW')")
    public ApiResponse<PagedResponse<ValuerResponse>> panel(@ModelAttribute ValuerListRequest request) {
        return ApiResponse.success(valuers.list(request));
    }

    /** The signed-in valuer's own record. No permission beyond being on the panel. */
    @GetMapping("/api/v1/valuers/me")
    @PreAuthorize("hasAuthority('VALUER_PANEL_VIEW')")
    public ApiResponse<ValuerResponse> me() {
        return ApiResponse.success(valuers.me());
    }

    @GetMapping("/api/v1/valuers/{reference}")
    @PreAuthorize("hasAuthority('VALUER_PANEL_MANAGE')")
    public ApiResponse<ValuerResponse> findValuer(@PathVariable String reference) {
        return ApiResponse.success(valuers.find(reference));
    }

    @PostMapping("/api/v1/valuers/onboard")
    @PreAuthorize("hasAuthority('VALUER_PANEL_MANAGE')")
    @RequestAction("ONBOARD VALUER")
    public ApiResponse<OnboardedValuer> onboard(@Valid @RequestBody OnboardValuerRequest request) {
        return ApiResponse.success("Valuer added to the panel", valuers.onboard(request));
    }

    @PostMapping("/api/v1/valuers/{reference}/update")
    @PreAuthorize("hasAuthority('VALUER_PANEL_MANAGE')")
    @RequestAction("UPDATE VALUER")
    public ApiResponse<ValuerResponse> updateValuer(@PathVariable String reference,
                                                    @Valid @RequestBody UpdateValuerRequest request) {
        return ApiResponse.success("Saved", valuers.update(reference, request));
    }

    @PostMapping("/api/v1/valuers/{reference}/suspend")
    @PreAuthorize("hasAuthority('VALUER_PANEL_MANAGE')")
    @RequestAction("SUSPEND VALUER")
    public ApiResponse<ValuerResponse> suspend(@PathVariable String reference,
                                               @RequestBody(required = false) PanelRequest request) {
        return ApiResponse.success("Off the panel", valuers.setOnPanel(reference, false, request));
    }

    @PostMapping("/api/v1/valuers/{reference}/restore")
    @PreAuthorize("hasAuthority('VALUER_PANEL_MANAGE')")
    @RequestAction("RESTORE VALUER")
    public ApiResponse<ValuerResponse> restore(@PathVariable String reference) {
        return ApiResponse.success("Back on the panel", valuers.setOnPanel(reference, true, null));
    }
}
