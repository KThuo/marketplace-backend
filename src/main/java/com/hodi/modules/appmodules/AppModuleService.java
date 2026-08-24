package com.hodi.modules.appmodules;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.usertypes.UserType;
import com.hodi.modules.usertypes.UserTypeRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The module catalogue, and the {@code allowed_user_types} editor that is access axis (1).
 *
 * <p>This is the smallest surface with the largest reach in the whole access model: one CSV field per module
 * decides which classes of user can reach it at all, above anything an organisation configures. Editing it is
 * platform-only, and the two guards below exist because the failure modes are asymmetric — a CSV that is too
 * narrow annoys people, and a CSV that is too wide is a privilege escalation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppModuleService {

    private final AppModuleRepository repository;
    private final UserTypeRepository userTypes;
    private final AuditService audit;

    public record AppModuleResponse(
            String id,
            String code,
            String name,
            String description,
            List<String> allowedUserTypes,
            boolean core,
            Integer sortOrder,
            Integer status,
            String statusFlag) {}

    public record UpdateAppModuleRequest(
            @NotBlank(message = "A name is required")
            @Size(max = 128, message = "A name is at most 128 characters")
            String name,
            @Size(max = 2000) String description,
            Integer sortOrder,
            /*
             * The user-type codes this module admits. A list on the wire, CSV in the column: the client gets
             * a multi-select it can bind without parsing, and the column stays readable to whoever opens the
             * table. Never matched with SQL LIKE — see AppModule.allows().
             */
            List<String> allowedUserTypes) {}

    @Transactional(readOnly = true)
    public PagedResponse<AppModuleResponse> list(PagedDataRequest request) {
        Specification<AppModule> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()));
        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.ASC, "sortOrder", "name")));
        return PagedResponse.from(page, AppModuleService::toResponse);
    }

    @Transactional(readOnly = true)
    public List<AppModuleResponse> options() {
        return repository.findByStatusNotOrderBySortOrderAsc(AppConstant.STATUS_DELETED).stream()
                .map(AppModuleService::toResponse)
                .toList();
    }

    /**
     * Edits a module, including the user types it admits.
     *
     * <p>There is no create and no delete. The catalogue is seeded from {@code AppModuleEnum} and reconciled
     * on every boot: a module the code does not know about would have no permissions, no routes and no
     * screens, so being able to invent one through the API would only produce a row that does nothing.
     */
    @Transactional
    public AppModuleResponse update(String hashId, UpdateAppModuleRequest request) {
        AppModule module = require(hashId);
        String before = snapshot(module);

        module.setName(request.name().trim());
        module.setDescription(request.description());
        if (request.sortOrder() != null) module.setSortOrder(request.sortOrder());
        if (request.allowedUserTypes() != null) {
            module.setAllowedUserTypes(validateAndJoin(request.allowedUserTypes()));
        }
        module.setStatus(AppConstant.STATUS_EDITED);
        module.setStatusFlag(AppConstant.FLAG_EDITED);
        module.setUpdatedBy(AuthContext.username());

        AppModule saved = repository.save(module);
        /*
         * Audited with the before and after CSV explicitly, because this is the field somebody will want to
         * reconstruct after "why can our officers suddenly see that". The generic diff would record that
         * allowedUserTypes changed; the snapshot records what it changed from.
         */
        audit.record(AppConstant.ACTION_UPDATE, "AppModule", saved.getId(), before, snapshot(saved));
        log.info("Module {} now admits {}", saved.getCode(), saved.getAllowedUserTypes());
        return toResponse(saved);
    }

    @Transactional
    public void deactivate(String hashId, String reason) {
        AppModule module = require(hashId);
        if (module.isCore()) {
            // A core module is one the application assumes is present — users, groups, settings. Switching
            // one off would not disable a feature so much as break the screens that administer everything
            // else, including the screen you would need to switch it back on.
            throw new HodiException("Core modules cannot be switched off.", HttpStatus.CONFLICT);
        }
        String before = snapshot(module);
        module.setStatus(AppConstant.STATUS_INACTIVE);
        module.setStatusFlag(AppConstant.FLAG_INACTIVE);
        module.setDeactivationReason(reason);
        module.setUpdatedBy(AuthContext.username());
        repository.save(module);
        audit.record(AppConstant.ACTION_DEACTIVATE, "AppModule", module.getId(), before, snapshot(module));
    }

    @Transactional
    public void activate(String hashId) {
        AppModule module = require(hashId);
        String before = snapshot(module);
        module.setStatus(AppConstant.STATUS_ACTIVE);
        module.setStatusFlag(AppConstant.FLAG_ACTIVE);
        module.setDeactivationReason(null);
        module.setUpdatedBy(AuthContext.username());
        repository.save(module);
        audit.record(AppConstant.ACTION_ACTIVATE, "AppModule", module.getId(), before, snapshot(module));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Normalises the submitted codes into the stored CSV, refusing anything that is not a real user type.
     *
     * <p>Two things happen here that both matter. Codes are uppercased and de-duplicated, so the stored value
     * has one canonical form and {@code allows()} — which compares uppercased tokens — cannot miss because
     * somebody typed lowercase. And every code is checked against {@code user_types}: a typo would otherwise
     * persist happily and simply never match, producing a module that quietly admits nobody, which presents
     * as "the permissions do not work" with nothing wrong on the permission side.
     */
    private String validateAndJoin(List<String> codes) {
        Set<String> normalised = codes.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(c -> c.trim().toUpperCase())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalised.isEmpty()) return "";

        Set<String> known = userTypes.findByStatusNotOrderBySortOrderAsc(AppConstant.STATUS_DELETED)
                .stream()
                .map(UserType::getCode)
                .collect(Collectors.toSet());
        List<String> unknown = normalised.stream().filter(c -> !known.contains(c)).toList();
        if (!unknown.isEmpty()) {
            throw new HodiException("Unknown user type code: " + String.join(", ", unknown),
                    HttpStatus.BAD_REQUEST);
        }
        String joined = String.join(",", normalised);
        if (joined.length() > 512) {
            throw new HodiException("That is more user types than this module can list.",
                    HttpStatus.BAD_REQUEST);
        }
        return joined;
    }

    private AppModule require(String hashId) {
        Long id = HashIdUtil.decodeId(hashId);
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Module", hashId));
    }

    private static AppModuleResponse toResponse(AppModule module) {
        return new AppModuleResponse(
                HashIdUtil.encodeId(module.getId()),
                module.getCode(),
                module.getName(),
                module.getDescription(),
                List.copyOf(module.allowedTypeSet()),
                module.isCore(),
                module.getSortOrder(),
                module.getStatus(),
                module.getStatusFlag());
    }

    private static String snapshot(AppModule module) {
        return "{\"code\":\"%s\",\"name\":\"%s\",\"allowedUserTypes\":\"%s\",\"status\":%d}"
                .formatted(module.getCode(), module.getName(),
                        module.getAllowedUserTypes() == null ? "" : module.getAllowedUserTypes(),
                        module.getStatus());
    }
}
