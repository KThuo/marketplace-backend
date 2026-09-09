package com.hodi.modules.developments;

import com.hodi.common.ApiResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.developments.DevelopmentFinanceDtos.CategoryResponse;
import com.hodi.modules.developments.DevelopmentFinanceDtos.SaveCategoryRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The cost categories: read by anyone who records or reads a development's money, written by the platform.
 */
@RestController
@RequestMapping("/api/v1/cost-categories")
@RequiredArgsConstructor
public class CostCategoryController {

    private final CostCategoryService service;

    /** What the record-a-cost form offers. */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('DEVELOPMENTS_FINANCE_VIEW','DEVELOPMENTS_FINANCE_RECORD','COST_CATEGORIES_MANAGE')")
    public ApiResponse<List<CategoryResponse>> available() {
        return ApiResponse.success(service.available());
    }

    /** Everything, suspended ones included — the admin screen. */
    @GetMapping("/all")
    @PreAuthorize("hasAuthority('COST_CATEGORIES_MANAGE')")
    public ApiResponse<List<CategoryResponse>> all() {
        return ApiResponse.success(service.all());
    }

    @PostMapping("/create")
    @PreAuthorize("hasAuthority('COST_CATEGORIES_MANAGE')")
    @RequestAction("CREATE_COST_CATEGORY")
    public ApiResponse<CategoryResponse> create(@Valid @RequestBody SaveCategoryRequest request) {
        return ApiResponse.success("Category added", service.create(request));
    }

    @PostMapping("/update/{hashId}")
    @PreAuthorize("hasAuthority('COST_CATEGORIES_MANAGE')")
    @RequestAction("UPDATE_COST_CATEGORY")
    public ApiResponse<CategoryResponse> update(@PathVariable String hashId,
                                                @Valid @RequestBody SaveCategoryRequest request) {
        return ApiResponse.success("Saved", service.update(hashId, request));
    }

    @PostMapping("/{hashId}/status")
    @PreAuthorize("hasAuthority('COST_CATEGORIES_MANAGE')")
    @RequestAction("SET_COST_CATEGORY_STATUS")
    public ApiResponse<Void> setStatus(@PathVariable String hashId, @RequestParam boolean active) {
        return ApiResponse.success(service.setStatus(hashId, active), null);
    }
}
