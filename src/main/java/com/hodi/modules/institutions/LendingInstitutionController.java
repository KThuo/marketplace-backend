package com.hodi.modules.institutions;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.DeactivateRequest;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.modules.institutions.LendingInstitutionService.CreateInstitutionRequest;
import com.hodi.modules.institutions.LendingInstitutionService.InstitutionResponse;
import com.hodi.modules.institutions.LendingInstitutionService.RegisteredInstitution;
import com.hodi.modules.institutions.LendingInstitutionService.UpdateInstitutionRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/institutions")
@RequiredArgsConstructor
public class LendingInstitutionController {

    private final LendingInstitutionService service;

    /**
     * Also readable by whoever may see partnerships: a seller owner choosing a finance partner needs the
     * directory, and {@code INSTITUTIONS_VIEW} is platform-only.
     */
    @GetMapping("/list")
    @PreAuthorize("hasAnyAuthority('INSTITUTIONS_VIEW','PARTNERSHIPS_VIEW')")
    public ApiResponse<PagedResponse<InstitutionResponse>> list(
            @ModelAttribute PagedDataRequest request) {
        return ApiResponse.success(service.list(request));
    }

    @GetMapping("/find/{hashId}")
    @PreAuthorize("hasAnyAuthority('INSTITUTIONS_VIEW','PARTNERSHIPS_VIEW')")
    public ApiResponse<InstitutionResponse> find(@PathVariable String hashId) {
        return ApiResponse.success(service.find(hashId));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('INSTITUTION_SELF_VIEW')")
    public ApiResponse<InstitutionResponse> mine() {
        return ApiResponse.success(service.mine());
    }

    /** Lenders this seller could propose a partnership to — the "add partner" picker. */
    @GetMapping("/partnerable")
    @PreAuthorize("hasAuthority('PARTNERSHIPS_REQUEST')")
    public ApiResponse<List<InstitutionResponse>> partnerable() {
        return ApiResponse.success(service.partnerable());
    }

    @PostMapping("/create")
    @PreAuthorize("hasAuthority('INSTITUTIONS_CREATE')")
    public ApiResponse<RegisteredInstitution> create(
            @Valid @RequestBody CreateInstitutionRequest request) {
        return ApiResponse.success("Institution registered", service.create(request));
    }

    @PostMapping("/update/{hashId}")
    @PreAuthorize("hasAnyAuthority('INSTITUTIONS_UPDATE','INSTITUTION_SELF_UPDATE')")
    public ApiResponse<InstitutionResponse> update(@PathVariable String hashId,
                                                   @Valid @RequestBody UpdateInstitutionRequest request) {
        return ApiResponse.success("Institution updated", service.update(hashId, request));
    }

    @PostMapping("/deactivate/{hashId}")
    @PreAuthorize("hasAuthority('INSTITUTIONS_DEACTIVATE')")
    public ApiResponse<Void> deactivate(@PathVariable String hashId,
                                        @Valid @RequestBody DeactivateRequest request) {
        service.deactivate(hashId, request.reason());
        return ApiResponse.success("Institution deactivated", null);
    }

    @PostMapping("/activate/{hashId}")
    @PreAuthorize("hasAuthority('INSTITUTIONS_ACTIVATE')")
    public ApiResponse<Void> activate(@PathVariable String hashId) {
        service.activate(hashId);
        return ApiResponse.success("Institution activated", null);
    }
}
