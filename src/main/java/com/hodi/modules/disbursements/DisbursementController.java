package com.hodi.modules.disbursements;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.disbursements.DisbursementDtos.*;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Money out. Proposing takes one permission, releasing another, and the queue in between is the
 * approvals module's — this controller never decides anything.
 */
@RestController
@RequestMapping("/api/v1/disbursements")
@RequiredArgsConstructor
public class DisbursementController {

    private final DisbursementService service;

    /** Who holds this account. Asked from the form before the maker commits to anything. */
    @PostMapping("/validate")
    @PreAuthorize("hasAuthority('DISBURSEMENTS_MAKE')")
    public ApiResponse<ValidateResponse> validate(@Valid @RequestBody ValidateRequest request) {
        ValidateResponse result = service.validate(request);
        return ApiResponse.success(result.message(), result);
    }

    @PostMapping("/propose")
    @PreAuthorize("hasAuthority('DISBURSEMENTS_MAKE')")
    @RequestAction("PROPOSE_DISBURSEMENT")
    public ApiResponse<DisbursementResponse> propose(@Valid @RequestBody ProposeRequest request) {
        DisbursementResponse proposed = service.propose(request);
        return ApiResponse.success("Sent for approval. Nothing moves until a second person releases it.", proposed);
    }

    /** What the pay-a-beneficiary form needs for one development, in one read. */
    @GetMapping("/pay-from/{developmentId}/options")
    @PreAuthorize("hasAnyAuthority('DISBURSEMENTS_MAKE','DEVELOPMENTS_FINANCE_VIEW')")
    public ApiResponse<PaymentOptions> paymentOptions(@PathVariable String developmentId) {
        return ApiResponse.success(service.paymentOptions(developmentId));
    }

    /** A development pays one of its beneficiaries. Written and put in front of the managing side's checker. */
    @PostMapping("/pay-from/{developmentId}")
    @PreAuthorize("hasAuthority('DISBURSEMENTS_MAKE')")
    @RequestAction("PAY_BENEFICIARY_FROM_DEVELOPMENT")
    public ApiResponse<DisbursementResponse> payFromDevelopment(@PathVariable String developmentId,
                                                                @Valid @RequestBody PayFromDevelopmentRequest request) {
        DisbursementResponse proposed = service.payFromDevelopment(developmentId, request);
        return ApiResponse.success(proposed.reference() + " sent for approval", proposed);
    }

    @PostMapping(value = "/{hashId}/evidence", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('DISBURSEMENTS_MAKE')")
    @RequestAction("ATTACH_PAYMENT_EVIDENCE")
    public ApiResponse<DisbursementResponse> attachEvidence(
            @PathVariable String hashId,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return ApiResponse.success("Attached", service.attachEvidence(hashId, file));
    }

    @GetMapping("/{hashId}/evidence")
    @PreAuthorize("hasAuthority('DISBURSEMENTS_VIEW')")
    @RequestAction("READ PAYMENT EVIDENCE")
    public org.springframework.http.ResponseEntity<byte[]> evidence(@PathVariable String hashId) {
        com.hodi.modules.kyc.DocumentService.Fetched fetched = service.evidence(hashId);
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        fetched.contentType() == null ? "application/octet-stream" : fetched.contentType()))
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment()
                                .filename(fetched.fileName() == null ? "evidence" : fetched.fileName()).build().toString())
                .header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, private")
                .body(fetched.bytes());
    }

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('DISBURSEMENTS_VIEW')")
    public ApiResponse<PagedResponse<DisbursementResponse>> list(@ModelAttribute ListRequest request) {
        return ApiResponse.success(service.list(request));
    }

    @GetMapping("/find/{hashId}")
    @PreAuthorize("hasAuthority('DISBURSEMENTS_VIEW')")
    public ApiResponse<DisbursementDetail> find(@PathVariable String hashId) {
        return ApiResponse.success(service.find(hashId));
    }

    /** An operator asking the bank now. Neither counted nor capped. */
    @PostMapping("/{hashId}/query")
    @PreAuthorize("hasAuthority('DISBURSEMENTS_MAKE') or hasAuthority('DISBURSEMENTS_APPROVE')")
    public ApiResponse<DisbursementDetail> query(@PathVariable String hashId) {
        service.query(HashIdUtil.decodeId(hashId), false, AuthContext.username());
        DisbursementDetail after = service.find(hashId);
        return ApiResponse.success(after.disbursement().processingReason(), after);
    }
}
