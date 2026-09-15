package com.hodi.modules.sellers;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.modules.sellers.SellerDtos.DecisionRequest;
import com.hodi.modules.sellers.SellerDtos.SaveApplicationRequest;
import com.hodi.modules.sellers.SellerDtos.SellerApplicationListRequest;
import com.hodi.modules.sellers.SellerDtos.SellerApplicationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The application after registration: the applicant's own, and the bank's queue.
 *
 * <p>The applicant's three endpoints are gated on being signed in and nothing else — they are reading and
 * writing their own row, found by their user id rather than by an id they pass, so there is no id to
 * tamper with. The bank's are gated on {@code SELLERS_VIEW} and {@code SELLERS_DECIDE}, both platform-only:
 * deciding who may sell through Co-op is Co-op's, and the applicant has no organisation to delegate it to.
 */
@RestController
@RequestMapping("/api/v1/seller-applications")
@RequiredArgsConstructor
public class SellerApplicationController {

    private final SellerApplicationService service;

    // ── the applicant's own ──

    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<SellerApplicationResponse> mine() {
        return ApiResponse.success(service.mine());
    }

    @PostMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<SellerApplicationResponse> save(
            @Valid @RequestBody SaveApplicationRequest request) {
        return ApiResponse.success("Saved", service.save(request));
    }

    @PostMapping("/mine/submit")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<SellerApplicationResponse> submit() {
        return ApiResponse.success("Sent to the bank for review", service.submit());
    }

    /** The checklist for the kind of seller they said they are, and what they have answered. */
    @GetMapping("/mine/documents")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<java.util.List<SellerApplicationService.RequiredDocument>> documents() {
        return ApiResponse.success(service.requiredDocuments());
    }

    /**
     * One document against one line of the checklist.
     *
     * <p>Multipart, and the file goes into the vault rather than anywhere this module owns — see
     * {@code SellerApplicationService.uploadDocument}.
     */
    @PostMapping("/mine/documents")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<java.util.List<SellerApplicationService.RequiredDocument>> upload(
            @RequestParam String documentCode,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        return ApiResponse.success("Uploaded", service.uploadDocument(documentCode, file));
    }

    // ── the bank's ──

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('SELLERS_VIEW')")
    public ApiResponse<PagedResponse<SellerApplicationResponse>> list(
            @ModelAttribute SellerApplicationListRequest request) {
        return ApiResponse.success(service.list(request));
    }

    @GetMapping("/waiting-count")
    @PreAuthorize("hasAuthority('SELLERS_VIEW')")
    public ApiResponse<Map<String, Long>> waiting() {
        return ApiResponse.success(Map.of("waiting", service.waitingCount()));
    }

    @GetMapping("/{reference}")
    @PreAuthorize("hasAuthority('SELLERS_VIEW')")
    public ApiResponse<SellerApplicationResponse> find(@PathVariable String reference) {
        return ApiResponse.success(service.find(reference));
    }

    @PostMapping("/{reference}/decide")
    @PreAuthorize("hasAuthority('SELLERS_DECIDE')")
    public ApiResponse<SellerApplicationResponse> decide(@PathVariable String reference,
                                                         @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.success("Decision recorded", service.decide(reference, request));
    }
}
