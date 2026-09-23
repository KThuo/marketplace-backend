package com.hodi.modules.finance;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.finance.FinanceDtos.AffordabilityListRequest;
import com.hodi.modules.finance.FinanceDtos.AffordabilityRequest;
import com.hodi.modules.finance.FinanceDtos.AffordabilityResponse;
import com.hodi.modules.finance.FinanceDtos.AffordabilitySummary;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Affordability checks — the person's own, and the platform's list of them.
 *
 * <p>Two mappings in one file because they are two halves of the same rule about one table, and separating
 * them would make that rule easy to lose sight of. The person's endpoints sit under {@code /me}, are scoped
 * by the signed-in identity and carry no permission — there is nothing to grant beyond being yourself. The
 * platform's list sits under {@code /affordability}, requires {@code AFFORDABILITY_VIEW}, and returns
 * summaries: outcomes and derived figures. One check may be opened in full — the working, line by line —
 * and that reading carries no identity: the platform sees how a figure was arrived at, not whose it is.
 */
@RestController
@RequiredArgsConstructor
public class AffordabilityController {

    private final AffordabilityService service;

    // ── the person's own ──────────────────────────────────────────────────────

    /** The calculator, with the answer kept. */
    @PostMapping("/api/v1/me/affordability")
    @RequestAction("RUN AFFORDABILITY CHECK")
    public ApiResponse<AffordabilityResponse> run(@Valid @RequestBody AffordabilityRequest request) {
        return ApiResponse.success("Saved to your account", service.record(request));
    }

    @GetMapping("/api/v1/me/affordability")
    public ApiResponse<PagedResponse<AffordabilitySummary>> mine(
            @ModelAttribute AffordabilityListRequest request) {
        return ApiResponse.success(service.mine(request));
    }

    @GetMapping("/api/v1/me/affordability/{reference}")
    public ApiResponse<AffordabilityResponse> mineByReference(@PathVariable String reference) {
        return ApiResponse.success(service.mineByReference(reference));
    }

    // ── the platform's list ───────────────────────────────────────────────────

    @GetMapping("/api/v1/affordability/list")
    @PreAuthorize("hasAuthority('AFFORDABILITY_VIEW')")
    public ApiResponse<PagedResponse<AffordabilitySummary>> list(
            @ModelAttribute AffordabilityListRequest request) {
        return ApiResponse.success(service.list(request));
    }

    /** One check with its working, and nothing about who ran it. */
    @GetMapping("/api/v1/affordability/{reference}")
    @PreAuthorize("hasAuthority('AFFORDABILITY_VIEW')")
    public ApiResponse<AffordabilityResponse> find(@PathVariable String reference) {
        return ApiResponse.success(service.find(reference));
    }
}
