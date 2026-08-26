package com.hodi.modules.tenants;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.DeactivateRequest;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.modules.tenants.TenantService.CreateTenantRequest;
import com.hodi.modules.tenants.TenantService.OnboardedTenant;
import com.hodi.modules.tenants.TenantService.TenantResponse;
import com.hodi.modules.tenants.TenantService.UpdateTenantRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService service;

    /**
     * The organisation list, scoped by who is asking.
     *
     * <p>{@code TENANTS_VIEW} is platform-only, but a lender's staff need this list too — it is how they
     * see which sellers they may work. So the gate also admits {@code PARTNERSHIPS_VIEW}, and
     * {@code TenantScope} narrows the rows to the partnered set. The permission says "you may look at
     * organisations"; the scope says which ones.
     */
    @GetMapping("/list")
    @PreAuthorize("hasAnyAuthority('TENANTS_VIEW','PARTNERSHIPS_VIEW')")
    public ApiResponse<PagedResponse<TenantResponse>> list(
            @ModelAttribute TenantService.TenantListRequest request) {
        return ApiResponse.success(service.list(request));
    }

    @GetMapping("/find/{hashId}")
    @PreAuthorize("hasAnyAuthority('TENANTS_VIEW','PARTNERSHIPS_VIEW')")
    public ApiResponse<TenantResponse> find(@PathVariable String hashId) {
        return ApiResponse.success(service.find(hashId));
    }

    /** A seller reading their own organisation — no id in the request, resolved from the principal. */
    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('TENANT_SELF_VIEW')")
    public ApiResponse<TenantResponse> mine() {
        return ApiResponse.success(service.mine());
    }

    /**
     * Onboards a seller organisation, its owner group and its first owner in one transaction.
     *
     * <p>Returns the owner's temporary password once. There is no way to read it again — only to issue a new
     * one through the user module — because a credential that can be re-read is a credential the audit trail
     * cannot account for.
     */
    @PostMapping("/create")
    @PreAuthorize("hasAuthority('TENANTS_CREATE')")
    public ApiResponse<OnboardedTenant> create(@Valid @RequestBody CreateTenantRequest request) {
        return ApiResponse.success("Organisation onboarded", service.create(request));
    }

    @PostMapping("/update/{hashId}")
    @PreAuthorize("hasAnyAuthority('TENANTS_UPDATE','TENANT_SELF_UPDATE')")
    public ApiResponse<TenantResponse> update(@PathVariable String hashId,
                                              @Valid @RequestBody UpdateTenantRequest request) {
        return ApiResponse.success("Organisation updated", service.update(hashId, request));
    }

    @PostMapping("/suspend/{hashId}")
    @PreAuthorize("hasAuthority('TENANTS_SUSPEND')")
    public ApiResponse<Void> suspend(@PathVariable String hashId,
                                     @Valid @RequestBody DeactivateRequest request) {
        service.suspend(hashId, request.reason());
        return ApiResponse.success("Organisation suspended", null);
    }

    @PostMapping("/reinstate/{hashId}")
    @PreAuthorize("hasAuthority('TENANTS_REINSTATE')")
    public ApiResponse<Void> reinstate(@PathVariable String hashId) {
        service.reinstate(hashId);
        return ApiResponse.success("Organisation reinstated", null);
    }

    @PostMapping("/terminate/{hashId}")
    @PreAuthorize("hasAuthority('TENANTS_TERMINATE')")
    public ApiResponse<Void> terminate(@PathVariable String hashId,
                                       @Valid @RequestBody DeactivateRequest request) {
        service.terminate(hashId, request.reason());
        return ApiResponse.success("Organisation closed", null);
    }

    @PostMapping("/deactivate/{hashId}")
    @PreAuthorize("hasAuthority('TENANTS_DEACTIVATE')")
    public ApiResponse<Void> deactivate(@PathVariable String hashId,
                                        @Valid @RequestBody DeactivateRequest request) {
        service.deactivate(hashId, request.reason());
        return ApiResponse.success("Organisation deactivated", null);
    }

    @PostMapping("/activate/{hashId}")
    @PreAuthorize("hasAuthority('TENANTS_ACTIVATE')")
    public ApiResponse<Void> activate(@PathVariable String hashId) {
        service.activate(hashId);
        return ApiResponse.success("Organisation activated", null);
    }
}
