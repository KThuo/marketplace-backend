package com.hodi.modules.tenantmodules;

import com.hodi.common.ApiResponse;
import com.hodi.modules.tenantmodules.TenantModuleService.TenantModuleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tenant-modules")
@RequiredArgsConstructor
public class TenantModuleController {

    private final TenantModuleService service;

    /**
     * Readable by whoever may see the organisation, not only by whoever may change its modules.
     *
     * <p>A seller owner needs to know what their organisation has even when the platform owns the switch —
     * "which modules do we have" is a fair question for the person paying, and the write below is where the
     * authority actually matters.
     */
    @GetMapping("/{tenantHashId}")
    @PreAuthorize("hasAnyAuthority('TENANT_MODULES_MANAGE','TENANTS_VIEW','TENANT_SELF_VIEW')")
    public ApiResponse<List<TenantModuleResponse>> forTenant(@PathVariable String tenantHashId) {
        return ApiResponse.success(service.forTenant(tenantHashId));
    }

    @PostMapping("/{tenantHashId}/set")
    @PreAuthorize("hasAuthority('TENANT_MODULES_MANAGE')")
    public ApiResponse<Void> set(@PathVariable String tenantHashId,
                                 @RequestBody Map<String, Object> body) {
        String moduleCode = String.valueOf(body.get("moduleCode"));
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        service.setEnabled(tenantHashId, moduleCode, enabled);
        return ApiResponse.success(enabled ? "Module switched on" : "Module switched off", null);
    }
}
