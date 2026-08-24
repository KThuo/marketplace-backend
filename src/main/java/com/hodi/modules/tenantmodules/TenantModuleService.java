package com.hodi.modules.tenantmodules;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.modules.appmodules.AppModule;
import com.hodi.modules.appmodules.AppModuleRepository;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.tenants.Tenant;
import com.hodi.modules.tenants.TenantRepository;
import com.hodi.security.TenantScope;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Which modules are switched on for one seller organisation — access axis (1), seller side.
 *
 * <p>Core modules are enabled at onboarding and cannot be switched off: they are the ones administering
 * everything else, so disabling one would break the screen you would need to re-enable it from.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantModuleService {

    private final TenantModuleRepository repository;
    private final AppModuleRepository appModules;
    private final TenantRepository tenants;
    private final AuditService audit;

    public record TenantModuleResponse(
            String id, String moduleCode, String moduleName, String description,
            boolean core, boolean enabled, OffsetDateTime enabledAt) {}

    /**
     * Every module, with whether this organisation has it — not only the enabled ones.
     *
     * <p>The screen is a set of switches, so it needs the off ones too. Returning only what is enabled would
     * make "what else could we turn on" unanswerable from the API.
     */
    @Transactional(readOnly = true)
    public List<TenantModuleResponse> forTenant(String tenantHashId) {
        Long tenantId = HashIdUtil.decodeId(tenantHashId);
        TenantScope.assertAllowed(tenantId);

        var held = repository.findByTenantId(tenantId);
        return appModules.findByStatusNotOrderBySortOrderAsc(AppConstant.STATUS_DELETED).stream()
                .map(module -> {
                    var row = held.stream()
                            .filter(h -> h.getAppModuleId().equals(module.getId()))
                            .findFirst();
                    boolean enabled = row.map(r -> AppConstant.isLive(r.getStatus())).orElse(false);
                    return new TenantModuleResponse(
                            row.map(r -> HashIdUtil.encodeId(r.getId())).orElse(null),
                            module.getCode(),
                            module.getName(),
                            module.getDescription(),
                            module.isCore(),
                            enabled,
                            row.map(TenantModule::getEnabledAt).orElse(null));
                })
                .toList();
    }

    @Transactional
    public void setEnabled(String tenantHashId, String moduleCode, boolean enabled) {
        Long tenantId = HashIdUtil.decodeId(tenantHashId);
        TenantScope.assertAllowed(tenantId);

        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Organisation", tenantHashId));
        AppModule module = appModules.findByCode(moduleCode)
                .orElseThrow(() -> new ResourceNotFoundException("Module", moduleCode));

        if (module.isCore() && !enabled) {
            throw new HodiException(
                    "%s is a core module and cannot be switched off.".formatted(module.getName()),
                    HttpStatus.CONFLICT);
        }

        var existing = repository.findByTenantIdAndAppModuleId(tenantId, module.getId());
        TenantModule row = existing.orElseGet(() -> TenantModule.builder()
                .tenantId(tenantId)
                .appModuleId(module.getId())
                .moduleCode(module.getCode())
                .moduleName(module.getName())
                .createdBy(AuthContext.username())
                .build());

        boolean was = row.getId() != null && AppConstant.isLive(row.getStatus());
        row.setStatus(enabled ? AppConstant.STATUS_ACTIVE : AppConstant.STATUS_INACTIVE);
        row.setStatusFlag(enabled ? AppConstant.FLAG_ACTIVE : AppConstant.FLAG_INACTIVE);
        if (enabled) {
            row.setEnabledAt(OffsetDateTime.now());
            row.setDisabledAt(null);
        } else {
            row.setDisabledAt(OffsetDateTime.now());
        }
        row.setUpdatedBy(AuthContext.username());
        repository.save(row);

        /*
         * Nothing to invalidate. The effective permission set is resolved per request from this table rather
         * than cached, so switching a module off takes effect on the organisation's staff at their very next
         * call. That is the whole reason EffectivePermissionResolver refuses to cache.
         */
        audit.record(enabled ? AppConstant.ACTION_ACTIVATE : AppConstant.ACTION_DEACTIVATE,
                "TenantModule", row.getId(),
                "%s=%s".formatted(module.getCode(), was), "%s=%s".formatted(module.getCode(), enabled));
        log.info("Module {} {} for organisation {}", module.getCode(),
                enabled ? "enabled" : "disabled", tenant.getSlug());
    }

    /**
     * Switches on every core module for a newly onboarded organisation.
     *
     * <p>Called from {@code TenantService.create}, and idempotent so it can be re-run to repair an
     * organisation created before a new core module existed — which is the normal way a core module ships.
     */
    @Transactional
    public int enableCoreModules(Long tenantId) {
        int added = 0;
        for (AppModule module : appModules.findByCoreTrueAndStatusNot(AppConstant.STATUS_DELETED)) {
            var existing = repository.findByTenantIdAndAppModuleId(tenantId, module.getId());
            if (existing.isPresent() && AppConstant.isLive(existing.get().getStatus())) continue;

            TenantModule row = existing.orElseGet(() -> TenantModule.builder()
                    .tenantId(tenantId)
                    .appModuleId(module.getId())
                    .moduleCode(module.getCode())
                    .moduleName(module.getName())
                    .createdBy(AuthContext.username())
                    .build());
            row.setModuleName(module.getName());
            row.setStatus(AppConstant.STATUS_ACTIVE);
            row.setStatusFlag(AppConstant.FLAG_ACTIVE);
            row.setEnabledAt(OffsetDateTime.now());
            row.setDisabledAt(null);
            repository.save(row);
            added++;
        }
        return added;
    }
}
