package com.hodi.modules.permissions;

import com.hodi.common.ApiResponse;
import com.hodi.common.AppConstant;
import com.hodi.modules.appmodules.AppModule;
import com.hodi.modules.appmodules.AppModuleRepository;
import com.hodi.modules.tenantmodules.TenantModuleRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The permission catalogue, read-only. It is seeded from {@code AppPermissionEnum} and reconciled on every
 * boot, so there is nothing here to create or edit — a permission that is not in the enum does not exist.
 */
@RestController
@RequestMapping("/api/v1/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionRepository permissions;
    private final AppModuleRepository appModules;
    private final TenantModuleRepository tenantModules;

    /** One permission, as the picker renders it. */
    public record PermissionResponse(
            String id, String actionCode, String actionName,
            String moduleCode, String moduleName, boolean platformOnly) {}

    /** The picker's shape: modules in display order, each with its own permissions. */
    public record ModuleGroup(
            String moduleCode, String moduleName, Integer sortOrder,
            List<PermissionResponse> permissions) {}

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('PERMISSIONS_VIEW')")
    public ApiResponse<List<PermissionResponse>> list() {
        return ApiResponse.success(
                permissions.findByStatusNot(AppConstant.STATUS_DELETED).stream()
                        .map(PermissionController::toResponse)
                        .toList());
    }

    /**
     * The permission picker, already filtered to what the caller may actually grant.
     *
     * <p><strong>Three filters, and each one closes a different hole.</strong> Doing this server-side rather
     * than shipping the whole catalogue and hiding rows in the browser matters: a hidden row is still a row
     * somebody can post back.
     *
     * <ol>
     *   <li><strong>Modules that admit the group's user type.</strong> Offering a permission whose module
     *       does not admit the type would let an organisation build a group full of authorities that
     *       {@code EffectivePermissionResolver} then silently drops — a role that looks granted and does
     *       nothing, which is worse than a role that cannot be built.
     *   <li><strong>Modules enabled for the caller's organisation.</strong> Same reasoning, one axis over.
     *   <li><strong>Platform-only permissions, unless the caller is platform staff.</strong> This is the one
     *       that is a security boundary rather than a usability one.
     * </ol>
     *
     * @param userTypeCode the user type the group being edited is bound to
     */
    @GetMapping("/catalogue")
    @PreAuthorize("hasAnyAuthority('PERMISSIONS_VIEW','USER_GROUPS_VIEW')")
    @Transactional(readOnly = true)
    public ApiResponse<List<ModuleGroup>> catalogue(@RequestParam(required = false) String userTypeCode) {
        UserPrincipal caller = AuthContext.require();
        boolean platformCaller = caller.isPlatformStaff();

        // Null means "the tenant-module axis does not apply" — platform and lender callers. An empty set
        // would mean the opposite and would offer nothing at all.
        Set<String> tenantEnabled = caller.getTenantId() == null
                ? null
                : Set.copyOf(tenantModules.findEnabledModuleCodes(caller.getTenantId()));

        Map<String, AppModule> modules = new LinkedHashMap<>();
        appModules.findByStatusNotOrderBySortOrderAsc(AppConstant.STATUS_DELETED)
                .forEach(m -> modules.put(m.getCode(), m));

        Map<String, List<PermissionResponse>> byModule = new LinkedHashMap<>();
        for (var permission : permissions.findByStatusNot(AppConstant.STATUS_DELETED)) {
            if (permission.isPlatformOnly() && !platformCaller) continue;

            AppModule module = modules.get(permission.getModuleCode());
            if (module == null || !AppConstant.isLive(module.getStatus())) continue;
            if (tenantEnabled != null && !tenantEnabled.contains(module.getCode())) continue;
            if (userTypeCode != null && !userTypeCode.isBlank() && !module.allows(userTypeCode)) continue;

            byModule.computeIfAbsent(module.getCode(), k -> new ArrayList<>())
                    .add(toResponse(permission));
        }

        List<ModuleGroup> grouped = new ArrayList<>();
        for (var entry : byModule.entrySet()) {
            AppModule module = modules.get(entry.getKey());
            grouped.add(new ModuleGroup(module.getCode(), module.getName(),
                    module.getSortOrder(), entry.getValue()));
        }
        grouped.sort((a, b) -> Integer.compare(
                a.sortOrder() == null ? 0 : a.sortOrder(),
                b.sortOrder() == null ? 0 : b.sortOrder()));
        return ApiResponse.success(grouped);
    }

    private static PermissionResponse toResponse(Permission p) {
        return new PermissionResponse(
                HashIdUtil.encodeId(p.getId()),
                p.getActionCode(),
                p.getActionName(),
                p.getModuleCode(),
                p.getModuleName(),
                p.isPlatformOnly());
    }
}
