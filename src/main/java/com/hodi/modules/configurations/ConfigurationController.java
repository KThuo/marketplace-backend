package com.hodi.modules.configurations;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.modules.configurations.ConfigurationAdminService.ConfigurationLogResponse;
import com.hodi.modules.configurations.ConfigurationAdminService.ConfigurationResponse;
import com.hodi.modules.configurations.ConfigurationAdminService.OverrideResponse;
import com.hodi.modules.configurations.ConfigurationAdminService.UpdateConfigRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/configurations")
@RequiredArgsConstructor
public class ConfigurationController {

    private final ConfigurationAdminService service;

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('APP_SETTINGS_VIEW')")
    public ApiResponse<PagedResponse<ConfigurationResponse>> list(
            @ModelAttribute PagedDataRequest request,
            @RequestParam(required = false) String category) {
        return ApiResponse.success(service.list(request, category));
    }

    @GetMapping("/categories")
    @PreAuthorize("hasAuthority('APP_SETTINGS_VIEW')")
    public ApiResponse<List<String>> categories() {
        return ApiResponse.success(service.categories());
    }

    /** Global settings are platform policy — {@code APP_SETTINGS_UPDATE} is a platform-only permission. */
    @PostMapping("/update/{hashId}")
    @PreAuthorize("hasAuthority('APP_SETTINGS_UPDATE')")
    public ApiResponse<ConfigurationResponse> update(@PathVariable String hashId,
                                                     @RequestBody UpdateConfigRequest request) {
        return ApiResponse.success("Setting updated", service.updateGlobal(hashId, request));
    }

    /**
     * Reveals one secret in the clear.
     *
     * <p>POST rather than GET, deliberately: a GET would land in browser history, proxy logs and any
     * screen-shared URL bar, which is the wrong place for a gateway credential to end up.
     */
    @PostMapping("/reveal/{configKey}")
    @PreAuthorize("hasAuthority('APP_SETTINGS_VIEW_SECRET')")
    public ApiResponse<Map<String, String>> reveal(@PathVariable String configKey) {
        // Called once and held: reveal() writes an audit row, so calling it twice to null-check the result
        // would record two reads for one request.
        String value = service.reveal(configKey);
        return ApiResponse.success(Map.of("value", value == null ? "" : value));
    }

    // ── per-organisation overrides ────────────────────────────────────────────

    @GetMapping("/overrides")
    @PreAuthorize("hasAuthority('APP_SETTINGS_VIEW')")
    public ApiResponse<List<OverrideResponse>> overrides() {
        return ApiResponse.success(service.overrides());
    }

    @PostMapping("/overrides/{configKey}")
    @PreAuthorize("hasAuthority('APP_SETTINGS_OVERRIDE')")
    public ApiResponse<Void> setOverride(@PathVariable String configKey,
                                         @RequestBody UpdateConfigRequest request) {
        service.setOverride(configKey, request);
        return ApiResponse.success("Setting overridden for this organisation", null);
    }

    @PostMapping("/overrides/{configKey}/clear")
    @PreAuthorize("hasAuthority('APP_SETTINGS_OVERRIDE')")
    public ApiResponse<Void> clearOverride(@PathVariable String configKey) {
        service.clearOverride(configKey);
        return ApiResponse.success("Back to the platform default", null);
    }

    @GetMapping("/log")
    @PreAuthorize("hasAuthority('APP_SETTINGS_VIEW')")
    public ApiResponse<PagedResponse<ConfigurationLogResponse>> log(
            @ModelAttribute PagedDataRequest request) {
        return ApiResponse.success(service.log(request));
    }
}
