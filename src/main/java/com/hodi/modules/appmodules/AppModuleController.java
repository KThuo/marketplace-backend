package com.hodi.modules.appmodules;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.DeactivateRequest;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.modules.appmodules.AppModuleService.AppModuleResponse;
import com.hodi.modules.appmodules.AppModuleService.UpdateAppModuleRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/app-modules")
@RequiredArgsConstructor
public class AppModuleController {

    private final AppModuleService service;

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('APP_MODULES_VIEW')")
    public ApiResponse<PagedResponse<AppModuleResponse>> list(@ModelAttribute PagedDataRequest request) {
        return ApiResponse.success(service.list(request));
    }

    /**
     * The module list for pickers — the per-organisation enablement screen and the permission picker's
     * grouping both need it.
     */
    @GetMapping("/options")
    @PreAuthorize("hasAnyAuthority('APP_MODULES_VIEW','TENANT_MODULES_MANAGE','USER_GROUPS_VIEW')")
    public ApiResponse<List<AppModuleResponse>> options() {
        return ApiResponse.success(service.options());
    }

    @PostMapping("/update/{hashId}")
    @PreAuthorize("hasAuthority('APP_MODULES_UPDATE')")
    public ApiResponse<AppModuleResponse> update(@PathVariable String hashId,
                                                 @Valid @RequestBody UpdateAppModuleRequest request) {
        return ApiResponse.success("Module updated", service.update(hashId, request));
    }

    @PostMapping("/deactivate/{hashId}")
    @PreAuthorize("hasAuthority('APP_MODULES_UPDATE')")
    public ApiResponse<Void> deactivate(@PathVariable String hashId,
                                        @Valid @RequestBody DeactivateRequest request) {
        service.deactivate(hashId, request.reason());
        return ApiResponse.success("Module switched off", null);
    }

    @PostMapping("/activate/{hashId}")
    @PreAuthorize("hasAuthority('APP_MODULES_UPDATE')")
    public ApiResponse<Void> activate(@PathVariable String hashId) {
        service.activate(hashId);
        return ApiResponse.success("Module switched on", null);
    }
}
