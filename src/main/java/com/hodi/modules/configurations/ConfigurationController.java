package com.hodi.modules.configurations;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.modules.configurations.ConfigurationAdminService.ConfigurationLogResponse;
import com.hodi.modules.configurations.ConfigurationAdminService.ConfigurationResponse;
import com.hodi.modules.configurations.ConfigurationAdminService.OverrideResponse;
import com.hodi.modules.configurations.ConfigurationAdminService.UpdateConfigRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import com.hodi.infra.storage.StorageService;
import com.hodi.logging.RequestAction;
import com.hodi.tenant.TenantContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/configurations")
@RequiredArgsConstructor
public class ConfigurationController {

    private final ConfigurationAdminService service;
    private final ThemeService themeService;
    private final StorageService storage;

    /**
     * The brand as this caller sees it.
     *
     * <p>The same shape as {@code /api/v1/public/theme} and deliberately so — the client's theme store handles
     * one response, not two. The difference is only which layer answers: a seller's request has their tenant
     * bound, so their overrides apply and their workspace carries their colours, while the public marketplace
     * keeps the platform's.
     *
     * <p>Authenticated but not permission-gated: this is what the caller's own screen looks like, and requiring
     * APP_SETTINGS_VIEW to know your own workspace's colour would leave everybody without that permission
     * looking at the platform default.
     */
    @GetMapping("/theme")
    public ApiResponse<Map<String, Object>> theme() {
        return ApiResponse.success(themeService.theme());
    }

    /**
     * Uploads a brand image and hands back its URL, for the caller to save into a theme key.
     *
     * <p>An upload rather than a URL field, because the alternative is asking somebody to host their own logo
     * and paste a link — which is how a brand ends up pointing at a Dropbox share that expires. Nothing is
     * saved to configuration here: the URL goes into the form's draft, so a mistaken pick is undone by not
     * saving.
     *
     * <p>Stored per organisation when a seller uploads (their prefix, their export, their deletion) and in the
     * shared prefix when the platform does — a logo every visitor sees does not belong inside one seller's
     * folder.
     */
    @PostMapping("/brand-logo")
    @PreAuthorize("hasAuthority('APP_SETTINGS_UPDATE') or hasAuthority('APP_SETTINGS_OVERRIDE')")
    @RequestAction("UPLOAD_BRAND_LOGO")
    public ApiResponse<Map<String, String>> brandLogo(@RequestParam("file") MultipartFile file) {
        var stored = TenantContext.getTenantId() == null
                ? storage.storeShared(file, "brand")
                : storage.store(file, "brand");
        return ApiResponse.success("Uploaded", Map.of("url", stored.url(), "key", stored.key()));
    }

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
                                                     @Valid @RequestBody UpdateConfigRequest request) {
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
                                         @Valid @RequestBody UpdateConfigRequest request) {
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
