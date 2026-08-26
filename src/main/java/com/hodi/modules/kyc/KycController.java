package com.hodi.modules.kyc;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.kyc.KycService.DecisionRequest;
import com.hodi.modules.kyc.KycService.SubmissionListRequest;
import com.hodi.modules.kyc.KycService.SubmissionResponse;
import com.hodi.modules.kyc.KycService.VerdictRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * KYC, and the one way to a document in the vault.
 *
 * <h2>The download endpoint</h2>
 *
 * <p>{@link #document} is deliberately the only route to vault bytes in this application. It is gated on
 * {@code KYC_VIEW} — which merely gets you to the door — and then the service checks the document's own ACL
 * and writes an audit row whether it allows or refuses. There is no URL form of a vault object anywhere, and
 * nothing here returns one.
 *
 * <p>{@code Cache-Control: no-store} and {@code Content-Disposition: attachment} on the response, so a
 * shared browser does not keep somebody's identity document in its cache and a mis-typed content type
 * cannot get it rendered inline.
 */
@RestController
@RequestMapping("/api/v1/kyc")
@RequiredArgsConstructor
public class KycController {

    private final KycService service;
    private final DocumentService documents;

    // ── the seller's own pack ─────────────────────────────────────────────────

    @GetMapping("/my-pack")
    @PreAuthorize("hasAuthority('KYC_VIEW')")
    public ApiResponse<SubmissionResponse> myPack() {
        return ApiResponse.success(service.mine());
    }

    @PostMapping("/my-pack/documents")
    @PreAuthorize("hasAuthority('KYC_SUBMIT')")
    @RequestAction("UPLOAD KYC DOCUMENT")
    public ApiResponse<SubmissionResponse> upload(
            @RequestParam String documentCode,
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issuedOn,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresOn) {
        return ApiResponse.success("Uploaded", service.upload(documentCode, file, issuedOn, expiresOn));
    }

    /** A fresh pack, after a decision. For a renewal, or after being turned down. */
    @PostMapping("/my-pack/renew")
    @PreAuthorize("hasAuthority('KYC_SUBMIT')")
    @RequestAction("START KYC PACK")
    public ApiResponse<SubmissionResponse> renew() {
        return ApiResponse.success("New pack started", service.renew());
    }

    @PostMapping("/my-pack/submit")
    @PreAuthorize("hasAuthority('KYC_SUBMIT')")
    @RequestAction("SUBMIT KYC PACK")
    public ApiResponse<SubmissionResponse> submit() {
        return ApiResponse.success("Sent to Compliance", service.submit());
    }

    // ── Compliance ────────────────────────────────────────────────────────────

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    public ApiResponse<PagedResponse<SubmissionResponse>> list(
            @ModelAttribute SubmissionListRequest request) {
        return ApiResponse.success(service.list(request));
    }

    @GetMapping("/{reference}")
    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    public ApiResponse<SubmissionResponse> find(@PathVariable String reference) {
        return ApiResponse.success(service.find(reference));
    }

    @PostMapping("/{reference}/verdict")
    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    @RequestAction("RECORD KYC VERDICT")
    public ApiResponse<SubmissionResponse> verdict(@PathVariable String reference,
                                                   @Valid @RequestBody VerdictRequest request) {
        return ApiResponse.success("Recorded", service.setVerdict(reference, request));
    }

    @PostMapping("/{reference}/decide")
    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    @RequestAction("DECIDE KYC PACK")
    public ApiResponse<SubmissionResponse> decide(@PathVariable String reference,
                                                  @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.success("Decision recorded", service.decide(reference, request));
    }

    @GetMapping("/waiting-count")
    @PreAuthorize("hasAuthority('KYC_REVIEW')")
    public ApiResponse<Map<String, Long>> waiting() {
        return ApiResponse.success(Map.of("waiting", service.waitingCount()));
    }

    // ── the catalogue ─────────────────────────────────────────────────────────

    @GetMapping("/requirements")
    @PreAuthorize("hasAuthority('KYC_VIEW')")
    public ApiResponse<List<KycRequirement>> requirements(
            @RequestParam(required = false) String entityType) {
        return ApiResponse.success(service.catalogue(entityType));
    }

    // ── the vault's one door ──────────────────────────────────────────────────

    /**
     * The bytes of one document, if this caller's ACL allows it.
     *
     * <p>{@code KYC_VIEW} gets you here; the ACL decides whether you leave with anything. Both outcomes are
     * recorded. Note the response is built by hand rather than through {@code ApiResponse} — this is the one
     * endpoint in the application that returns something other than the envelope, because a PDF is not JSON.
     */
    @GetMapping("/documents/{reference}")
    @PreAuthorize("hasAuthority('KYC_VIEW')")
    @RequestAction("READ VAULT DOCUMENT")
    public ResponseEntity<byte[]> document(@PathVariable String reference) {
        DocumentService.Fetched fetched = documents.read(reference);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        fetched.contentType() == null ? "application/octet-stream" : fetched.contentType()))
                // Attachment, never inline: a mis-typed content type should not get somebody's identity
                // document rendered in a tab, and no-store keeps it out of a shared browser's cache.
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(fetched.fileName() == null ? "document" : fetched.fileName())
                        .build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store, no-cache, must-revalidate, private")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(fetched.bytes());
    }
}
