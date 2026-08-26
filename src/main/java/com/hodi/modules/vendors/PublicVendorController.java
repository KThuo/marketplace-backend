package com.hodi.modules.vendors;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.vendors.PublicVendorService.*;
import com.hodi.modules.vendors.VendorCategoryService.CategoryResponse;
import com.hodi.modules.vendors.VendorService.RegisterVendorRequest;
import com.hodi.modules.vendors.VendorService.RegistrationOutcome;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The vendor directory, and applying to be in it (M10).
 *
 * <p>Public because the whole point is somebody who has just had an offer accepted and needs a conveyancer
 * this week. Nothing here needs an account, and nothing here exposes a vendor's registration number, PIN or
 * standing — the response records are separate for exactly that reason.
 */
@RestController
@RequestMapping("/api/v1/public/vendors")
@RequiredArgsConstructor
public class PublicVendorController {

    private final PublicVendorService directory;
    private final VendorCategoryService categories;
    private final VendorService vendors;

    @GetMapping("/search")
    public ApiResponse<PagedResponse<PublicVendor>> search(@ModelAttribute PublicSearchRequest request) {
        return ApiResponse.success(directory.searchVendors(request));
    }

    @GetMapping("/items")
    public ApiResponse<PagedResponse<PublicItem>> items(@ModelAttribute PublicSearchRequest request) {
        return ApiResponse.success(directory.searchItems(request));
    }

    @GetMapping("/facets")
    public ApiResponse<DirectoryFacets> facets() {
        return ApiResponse.success(directory.facets());
    }

    /** What a business choosing a category picks from, before they have an account. */
    @GetMapping("/categories")
    public ApiResponse<List<CategoryResponse>> categories() {
        return ApiResponse.success(categories.active());
    }

    @GetMapping("/{reference}")
    public ApiResponse<VendorDetail> find(@PathVariable String reference) {
        return ApiResponse.success(directory.find(reference));
    }

    /**
     * Applying.
     *
     * <p>Named individually in {@code SecurityConfig}: a POST under {@code /api/v1/public} is not public by
     * default, and this one creates an account.
     */
    @PostMapping("/apply")
    @RequestAction("VENDOR APPLICATION")
    public ApiResponse<RegistrationOutcome> apply(@Valid @RequestBody RegisterVendorRequest request) {
        return ApiResponse.success("Application received", vendors.register(request));
    }
}
