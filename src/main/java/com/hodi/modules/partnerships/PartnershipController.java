package com.hodi.modules.partnerships;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.DeactivateRequest;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.modules.partnerships.PartnershipService.PartnershipResponse;
import com.hodi.modules.partnerships.PartnershipService.ProposeRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/partnerships")
@RequiredArgsConstructor
public class PartnershipController {

    private final PartnershipService service;

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('PARTNERSHIPS_VIEW')")
    public ApiResponse<PagedResponse<PartnershipResponse>> list(
            @ModelAttribute PagedDataRequest request) {
        return ApiResponse.success(service.list(request));
    }

    @PostMapping("/propose")
    @PreAuthorize("hasAuthority('PARTNERSHIPS_REQUEST')")
    public ApiResponse<PartnershipResponse> propose(@Valid @RequestBody ProposeRequest request) {
        return ApiResponse.success("Partnership proposed", service.propose(request));
    }

    /**
     * Approves a proposal — the endpoint that actually grants one organisation sight of another's data.
     *
     * <p>The permission is only half the check: {@code PartnershipService} additionally refuses whichever side
     * proposed it, so holding {@code PARTNERSHIPS_APPROVE} does not let an organisation wave through its own
     * request.
     */
    @PostMapping("/approve/{hashId}")
    @PreAuthorize("hasAuthority('PARTNERSHIPS_APPROVE')")
    public ApiResponse<PartnershipResponse> approve(@PathVariable String hashId) {
        return ApiResponse.success("Partnership approved", service.approve(hashId));
    }

    @PostMapping("/revoke/{hashId}")
    @PreAuthorize("hasAuthority('PARTNERSHIPS_REVOKE')")
    public ApiResponse<PartnershipResponse> revoke(@PathVariable String hashId,
                                                   @Valid @RequestBody DeactivateRequest request) {
        return ApiResponse.success("Partnership ended", service.revoke(hashId, request.reason()));
    }
}
