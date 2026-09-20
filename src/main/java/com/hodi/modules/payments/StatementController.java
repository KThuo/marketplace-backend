package com.hodi.modules.payments;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.payments.StatementDtos.AttachRequest;
import com.hodi.modules.payments.StatementDtos.SetAsideRequest;
import com.hodi.modules.payments.StatementDtos.StatementDetail;
import com.hodi.modules.payments.StatementDtos.StatementListRequest;
import com.hodi.modules.payments.StatementDtos.StatementResponse;
import com.hodi.modules.payments.StatementDtos.UploadOutcome;
import com.hodi.modules.payments.StatementDtos.UploadSpec;
import com.hodi.modules.payments.StatementDtos.Waiting;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

/**
 * The bank's notifications: what arrived, what it was placed on, and what is still waiting for a person.
 *
 * <p>One list serves the ledger and the worklist — {@code state=UNMAPPED} is the queue — and three
 * decisions a person may take about an unplaced row. Reading is {@code STATEMENTS_VIEW}, scoped to the
 * caller's own accounts; deciding is {@code STATEMENTS_RECONCILE}, which is the platform's.
 */
@RestController
@RequestMapping("/api/v1/statements")
@RequiredArgsConstructor
public class StatementController {

    private final StatementService service;
    private final StatementUploadService uploads;

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('STATEMENTS_VIEW')")
    public ApiResponse<PagedResponse<StatementResponse>> list(@ModelAttribute StatementListRequest request) {
        return ApiResponse.success(service.list(request));
    }

    @GetMapping("/waiting")
    @PreAuthorize("hasAuthority('STATEMENTS_VIEW')")
    public ApiResponse<Waiting> waiting() {
        return ApiResponse.success(service.waiting());
    }

    @GetMapping("/find/{hashId}")
    @PreAuthorize("hasAuthority('STATEMENTS_VIEW')")
    public ApiResponse<StatementDetail> find(@PathVariable String hashId) {
        return ApiResponse.success(service.find(hashId));
    }

    @PostMapping("/{hashId}/attach")
    @PreAuthorize("hasAuthority('STATEMENTS_RECONCILE')")
    @RequestAction("ATTACH_STATEMENT")
    public ApiResponse<StatementResponse> attach(@PathVariable String hashId,
                                                 @Valid @RequestBody AttachRequest request) {
        StatementResponse applied = service.attach(hashId, request);
        return ApiResponse.success(applied.currency() + " " + applied.amount().toPlainString()
                + " applied to booking " + applied.bookingReference() + " as receipt "
                + applied.paymentReference() + ".", applied);
    }

    @PostMapping("/{hashId}/set-aside")
    @PreAuthorize("hasAuthority('STATEMENTS_RECONCILE')")
    @RequestAction("SET_ASIDE_STATEMENT")
    public ApiResponse<StatementResponse> setAside(@PathVariable String hashId,
                                                   @Valid @RequestBody SetAsideRequest request) {
        return ApiResponse.success("Set aside. It stays on record and stops appearing as work.",
                service.setAside(hashId, request));
    }

    // ── a bank statement, as a file ──────────────────────────────────────────

    /** What the CSV should contain, for the form to say before anybody downloads anything. */
    @GetMapping("/upload/spec")
    @PreAuthorize("hasAuthority('STATEMENTS_RECONCILE')")
    public ApiResponse<UploadSpec> uploadSpec() {
        return ApiResponse.success(uploads.spec());
    }

    /** The header and one example row. */
    @GetMapping("/upload/template")
    @PreAuthorize("hasAuthority('STATEMENTS_RECONCILE')")
    public ResponseEntity<byte[]> uploadTemplate() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"bank-statement-template.csv\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(uploads.template().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Every row stored; those naming a booking or a listing placed on it; the rest queued for a person.
     *
     * <p>A hundred whose-money-is-this decisions at once is more authority than one, so this is the
     * reconciliation permission, not the recording one.
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('STATEMENTS_RECONCILE')")
    @RequestAction("UPLOAD_STATEMENT")
    public ApiResponse<UploadOutcome> upload(@RequestParam("accountId") String accountId,
                                             @RequestParam("file") MultipartFile file) {
        UploadOutcome outcome = uploads.upload(accountId, file);
        return ApiResponse.success(outcome.rows() + " rows: " + outcome.placed() + " applied, "
                + outcome.queued() + " for a person, " + outcome.skipped() + " already known, "
                + outcome.failed() + " could not be read.", outcome);
    }

    @PostMapping("/{hashId}/restore")
    @PreAuthorize("hasAuthority('STATEMENTS_RECONCILE')")
    @RequestAction("RESTORE_STATEMENT")
    public ApiResponse<StatementResponse> restore(@PathVariable String hashId) {
        return ApiResponse.success("Back in the queue.", service.restore(hashId));
    }
}
