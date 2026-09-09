package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.developments.DevelopmentFinanceDtos.CategoryResponse;
import com.hodi.modules.developments.DevelopmentFinanceDtos.SaveCategoryRequest;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The cost categories every development's ledger is filed under.
 *
 * <p>A platform taxonomy, like property types and progress milestones: everybody reads it, the platform
 * writes it. Suspending a category stops it being offered on the form; the lines already filed under it keep
 * their name, which is why there is no delete.
 */
@Service
@RequiredArgsConstructor
public class CostCategoryService {

    private final DevelopmentCostCategoryRepository categories;
    private final DevelopmentExpenditureRepository expenditures;
    private final AuditService audit;

    /** What the form offers: switched on, in order. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> available() {
        Map<Long, Long> inUse = inUse();
        return categories.findAvailable().stream().map(c -> toResponse(c, inUse)).toList();
    }

    /** Everything not archived, for the admin screen. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> all() {
        Map<Long, Long> inUse = inUse();
        return categories.findAllLive().stream().map(c -> toResponse(c, inUse)).toList();
    }

    @Transactional
    public CategoryResponse create(SaveCategoryRequest request) {
        String code = code(request.code());
        if (categories.countByCodeExcept(code, -1L) > 0) {
            throw new HodiException("A category with the code " + code + " already exists.", HttpStatus.CONFLICT);
        }
        DevelopmentCostCategory saved = categories.save(DevelopmentCostCategory.builder()
                .code(code)
                .name(request.name().trim())
                .description(blankToNull(request.description()))
                .sortOrder(request.sortOrder() == null ? 100 : request.sortOrder())
                .createdBy(AuthContext.username())
                .updatedBy(AuthContext.username())
                .build());
        audit.record(AppConstant.ACTION_CREATE, "DevelopmentCostCategory", saved.getId(), null, snapshot(saved));
        return toResponse(saved, Map.of());
    }

    @Transactional
    public CategoryResponse update(String hashId, SaveCategoryRequest request) {
        DevelopmentCostCategory category = require(hashId);
        String code = code(request.code());
        if (categories.countByCodeExcept(code, category.getId()) > 0) {
            throw new HodiException("A category with the code " + code + " already exists.", HttpStatus.CONFLICT);
        }
        String before = snapshot(category);
        category.setCode(code);
        category.setName(request.name().trim());
        category.setDescription(blankToNull(request.description()));
        if (request.sortOrder() != null) category.setSortOrder(request.sortOrder());
        category.setUpdatedBy(AuthContext.username());
        DevelopmentCostCategory saved = categories.save(category);
        audit.record(AppConstant.ACTION_UPDATE, "DevelopmentCostCategory", saved.getId(), before, snapshot(saved));
        return toResponse(saved, inUse());
    }

    /** Suspend or restore. Off means the form stops offering it; the lines under it keep their name. */
    @Transactional
    public String setStatus(String hashId, boolean active) {
        DevelopmentCostCategory category = require(hashId);
        String before = snapshot(category);
        category.setStatus(active ? AppConstant.STATUS_ACTIVE : AppConstant.STATUS_INACTIVE);
        category.setStatusFlag(active ? AppConstant.FLAG_ACTIVE : AppConstant.FLAG_INACTIVE);
        category.setUpdatedBy(AuthContext.username());
        categories.save(category);
        audit.record(active ? AppConstant.ACTION_ACTIVATE : AppConstant.ACTION_DEACTIVATE,
                "DevelopmentCostCategory", category.getId(), before, snapshot(category));
        long lines = inUse().getOrDefault(category.getId(), 0L);
        if (active) return category.getName() + " is offered again.";
        return lines == 0
                ? category.getName() + " is suspended. It is no longer offered."
                : category.getName() + " is suspended. The " + lines + " line" + (lines == 1 ? "" : "s")
                        + " already filed under it keep the name.";
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private Map<Long, Long> inUse() {
        Map<Long, Long> out = new HashMap<>();
        for (Object[] row : expenditures.countByCategory()) out.put((Long) row[0], (Long) row[1]);
        return out;
    }

    private DevelopmentCostCategory require(String hashId) {
        return categories.findById(HashIdUtil.decodeId(hashId))
                .filter(c -> c.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Cost category", hashId));
    }

    /** Upper-cased and underscored, so codes are stable identifiers rather than labels with spaces. */
    private static String code(String raw) {
        String value = raw.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (value.isEmpty()) throw new HodiException("Give the category a code.", HttpStatus.BAD_REQUEST);
        return value.length() > 32 ? value.substring(0, 32) : value;
    }

    static CategoryResponse toResponse(DevelopmentCostCategory c, Map<Long, Long> inUse) {
        return new CategoryResponse(HashIdUtil.encodeId(c.getId()), c.getCode(), c.getName(),
                c.getDescription(), c.getSortOrder(), c.getStatus(), c.getStatusFlag(),
                inUse.getOrDefault(c.getId(), 0L));
    }

    private static String snapshot(DevelopmentCostCategory c) {
        return c.getCode() + " " + c.getName() + " order=" + c.getSortOrder() + " status=" + c.getStatus();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
