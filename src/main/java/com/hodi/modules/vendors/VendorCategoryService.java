package com.hodi.modules.vendors;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.DuplicateResourceException;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.modules.audit.AuditService;
import com.hodi.security.principal.AuthContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * The taxonomy every vendor and every catalogue item is filed under (M10).
 *
 * <p>Platform-maintained, and deliberately not deletable. A category with vendors in it cannot be removed —
 * only deactivated, which stops it being chosen without orphaning the rows already filed under it. Deleting
 * would either cascade into other people's businesses or leave dangling references, and neither is a thing
 * an administrator should be able to do by clicking once.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VendorCategoryService {

    private final VendorCategoryRepository repository;
    private final VendorProfileRepository vendors;
    private final AuditService audit;

    public record CategoryResponse(
            String code,
            String name,
            String description,
            String icon,
            int sortOrder,
            /** How many approved vendors are filed under it. What makes deactivation a considered act. */
            long vendorCount,
            Integer status,
            String statusFlag) {}

    public record SaveCategoryRequest(
            @NotBlank(message = "A code is required") @Size(max = 32) String code,
            @NotBlank(message = "A name is required") @Size(max = 120) String name,
            String description,
            @Size(max = 8) String icon,
            Integer sortOrder) {}

    /** Everything, for the platform's own screen. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> all() {
        return repository.findAllLive().stream().map(this::toResponse).toList();
    }

    /** What a vendor or a buyer may choose from: the ones still in use. */
    @Transactional(readOnly = true)
    public List<CategoryResponse> active() {
        return repository.findAllActive().stream().map(this::toResponse).toList();
    }

    @Transactional
    public CategoryResponse create(SaveCategoryRequest request) {
        String code = normalise(request.code());
        if (repository.existsByCodeIgnoreCase(code)) {
            throw new DuplicateResourceException("A category with the code \"" + code + "\" already exists");
        }
        VendorCategory saved = repository.save(VendorCategory.builder()
                .code(code)
                .name(request.name().trim())
                .description(blankToNull(request.description()))
                .icon(blankToNull(request.icon()))
                .sortOrder(request.sortOrder() == null ? 100 : request.sortOrder())
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy(AuthContext.username())
                .build());
        audit.record(AppConstant.ACTION_CREATE, "VendorCategory", saved.getId(), null, snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public CategoryResponse update(String code, SaveCategoryRequest request) {
        VendorCategory category = require(code);
        String before = snapshot(category);
        // The code is not editable. It is what catalogue items and vendors were filed under, and renaming a
        // key so that its label reads better is how references stop resolving.
        category.setName(request.name().trim());
        category.setDescription(blankToNull(request.description()));
        category.setIcon(blankToNull(request.icon()));
        if (request.sortOrder() != null) category.setSortOrder(request.sortOrder());
        category.setStatus(AppConstant.STATUS_EDITED);
        category.setStatusFlag(AppConstant.FLAG_EDITED);
        category.setUpdatedBy(AuthContext.username());
        VendorCategory saved = repository.save(category);
        audit.record(AppConstant.ACTION_UPDATE, "VendorCategory", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public CategoryResponse deactivate(String code, String reason) {
        VendorCategory category = require(code);
        String before = snapshot(category);
        category.setStatus(AppConstant.STATUS_INACTIVE);
        category.setStatusFlag(AppConstant.FLAG_INACTIVE);
        category.setUpdatedBy(AuthContext.username());
        VendorCategory saved = repository.save(category);
        audit.record(AppConstant.ACTION_DEACTIVATE, "VendorCategory", saved.getId(), before,
                reason == null || reason.isBlank() ? snapshot(saved) : reason);
        long affected = vendors.countByCategoryIdAndStatusNot(saved.getId(), AppConstant.STATUS_DELETED);
        if (affected > 0) {
            // Said in the log because it is said on the screen too: the vendors stay, the category simply
            // stops being offered to new ones.
            log.info("Category {} deactivated with {} vendor(s) still filed under it", code, affected);
        }
        return toResponse(saved);
    }

    @Transactional
    public CategoryResponse activate(String code) {
        VendorCategory category = require(code);
        String before = snapshot(category);
        category.setStatus(AppConstant.STATUS_ACTIVE);
        category.setStatusFlag(AppConstant.FLAG_ACTIVE);
        category.setUpdatedBy(AuthContext.username());
        VendorCategory saved = repository.save(category);
        audit.record(AppConstant.ACTION_ACTIVATE, "VendorCategory", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    /** For the services that file something under a category and need the row to exist and be usable. */
    VendorCategory requireUsable(String code) {
        VendorCategory category = require(code);
        if (!AppConstant.isLive(category.getStatus())) {
            throw new HodiException("That category is no longer offered — choose another.",
                    HttpStatus.CONFLICT);
        }
        return category;
    }

    VendorCategory require(String code) {
        return repository.findByCodeIgnoreCase(normalise(code))
                .orElseThrow(() -> new ResourceNotFoundException("Category", code));
    }

    private CategoryResponse toResponse(VendorCategory c) {
        return new CategoryResponse(c.getCode(), c.getName(), c.getDescription(), c.getIcon(),
                c.getSortOrder(),
                vendors.countByCategoryIdAndStatusNot(c.getId(), AppConstant.STATUS_DELETED),
                c.getStatus(), c.getStatusFlag());
    }

    private static String normalise(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    }

    private static String snapshot(VendorCategory c) {
        return "{\"code\":\"%s\",\"name\":\"%s\",\"status\":%d}".formatted(
                c.getCode(), c.getName(), c.getStatus());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
