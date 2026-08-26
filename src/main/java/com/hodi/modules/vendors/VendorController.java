package com.hodi.modules.vendors;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.vendors.CatalogueService.*;
import com.hodi.modules.vendors.VendorCategoryService.CategoryResponse;
import com.hodi.modules.vendors.VendorCategoryService.SaveCategoryRequest;
import com.hodi.modules.vendors.VendorService.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * The vendor register, the taxonomy, and a vendor's own catalogue (M10).
 *
 * <p>One controller for a module whose three surfaces are read by the same two audiences: platform staff who
 * decide and maintain, and vendors who apply and publish. The permissions separate them — everything a
 * vendor may do is a {@code *_SELF_*} or {@code CATALOGUE_*} verb in a module that admits only vendors, and
 * everything the platform may do is platform-only.
 */
@RestController
@RequiredArgsConstructor
public class VendorController {

    private final VendorService vendors;
    private final VendorCategoryService categories;
    private final CatalogueService catalogue;

    // ── the register ──────────────────────────────────────────────────────────

    @GetMapping("/api/v1/vendors/list")
    @PreAuthorize("hasAuthority('VENDORS_VIEW')")
    public ApiResponse<PagedResponse<VendorResponse>> list(@ModelAttribute VendorListRequest request) {
        return ApiResponse.success(vendors.list(request));
    }

    @GetMapping("/api/v1/vendors/counts")
    @PreAuthorize("hasAuthority('VENDORS_VIEW')")
    public ApiResponse<VendorCounts> counts() {
        return ApiResponse.success(vendors.counts());
    }

    @GetMapping("/api/v1/vendors/{reference}")
    @PreAuthorize("hasAuthority('VENDORS_VIEW')")
    public ApiResponse<VendorResponse> find(@PathVariable String reference) {
        return ApiResponse.success(vendors.find(reference));
    }

    @PostMapping("/api/v1/vendors/{reference}/decide")
    @PreAuthorize("hasAuthority('VENDORS_DECIDE')")
    @RequestAction("DECIDE VENDOR APPLICATION")
    public ApiResponse<VendorResponse> decide(@PathVariable String reference,
                                              @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.success("Decision recorded", vendors.decide(reference, request));
    }

    // ── the taxonomy ──────────────────────────────────────────────────────────

    /**
     * Readable by anyone who can see the register — a vendor choosing what they offer needs the list, and a
     * platform screen filtering by category needs it too. Maintaining it is platform-only.
     */
    @GetMapping("/api/v1/vendor-categories")
    @PreAuthorize("hasAuthority('VENDORS_VIEW')")
    public ApiResponse<List<CategoryResponse>> categories() {
        return ApiResponse.success(categories.all());
    }

    @PostMapping("/api/v1/vendor-categories/create")
    @PreAuthorize("hasAuthority('VENDOR_CATEGORIES_MANAGE')")
    @RequestAction("CREATE VENDOR CATEGORY")
    public ApiResponse<CategoryResponse> createCategory(@Valid @RequestBody SaveCategoryRequest request) {
        return ApiResponse.success("Category added", categories.create(request));
    }

    @PostMapping("/api/v1/vendor-categories/{code}/update")
    @PreAuthorize("hasAuthority('VENDOR_CATEGORIES_MANAGE')")
    @RequestAction("UPDATE VENDOR CATEGORY")
    public ApiResponse<CategoryResponse> updateCategory(@PathVariable String code,
                                                        @Valid @RequestBody SaveCategoryRequest request) {
        return ApiResponse.success("Saved", categories.update(code, request));
    }

    @PostMapping("/api/v1/vendor-categories/{code}/deactivate")
    @PreAuthorize("hasAuthority('VENDOR_CATEGORIES_MANAGE')")
    @RequestAction("DEACTIVATE VENDOR CATEGORY")
    public ApiResponse<CategoryResponse> deactivateCategory(
            @PathVariable String code, @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.success("Category deactivated",
                categories.deactivate(code, body == null ? null : body.get("reason")));
    }

    @PostMapping("/api/v1/vendor-categories/{code}/activate")
    @PreAuthorize("hasAuthority('VENDOR_CATEGORIES_MANAGE')")
    @RequestAction("ACTIVATE VENDOR CATEGORY")
    public ApiResponse<CategoryResponse> activateCategory(@PathVariable String code) {
        return ApiResponse.success("Category activated", categories.activate(code));
    }

    // ── the vendor's own ──────────────────────────────────────────────────────

    @GetMapping("/api/v1/me/vendor")
    @PreAuthorize("hasAuthority('VENDOR_SELF_VIEW')")
    public ApiResponse<VendorResponse> mine() {
        return ApiResponse.success(vendors.mine());
    }

    @PostMapping("/api/v1/me/vendor/update")
    @PreAuthorize("hasAuthority('VENDOR_SELF_UPDATE')")
    @RequestAction("UPDATE MY BUSINESS")
    public ApiResponse<VendorResponse> updateMine(@Valid @RequestBody UpdateVendorRequest request) {
        return ApiResponse.success("Saved", vendors.updateMine(request));
    }

    @PostMapping("/api/v1/me/vendor/logo")
    @PreAuthorize("hasAuthority('VENDOR_SELF_UPDATE')")
    @RequestAction("UPLOAD VENDOR LOGO")
    public ApiResponse<VendorResponse> uploadLogo(@RequestParam("file") MultipartFile file) {
        return ApiResponse.success("Logo replaced", vendors.uploadLogo(file));
    }

    // ── the catalogue ─────────────────────────────────────────────────────────

    @GetMapping("/api/v1/me/catalogue/list")
    @PreAuthorize("hasAuthority('VENDOR_SELF_VIEW')")
    public ApiResponse<PagedResponse<ItemResponse>> myCatalogue(@ModelAttribute ItemListRequest request) {
        return ApiResponse.success(catalogue.mine(request));
    }

    @PostMapping("/api/v1/me/catalogue/create")
    @PreAuthorize("hasAuthority('CATALOGUE_CREATE')")
    @RequestAction("CREATE CATALOGUE ITEM")
    public ApiResponse<ItemResponse> createItem(@Valid @RequestBody SaveItemRequest request) {
        return ApiResponse.success("Saved", catalogue.create(request));
    }

    @PostMapping("/api/v1/me/catalogue/{reference}/update")
    @PreAuthorize("hasAuthority('CATALOGUE_UPDATE')")
    @RequestAction("UPDATE CATALOGUE ITEM")
    public ApiResponse<ItemResponse> updateItem(@PathVariable String reference,
                                                @Valid @RequestBody SaveItemRequest request) {
        return ApiResponse.success("Saved", catalogue.update(reference, request));
    }

    @PostMapping("/api/v1/me/catalogue/{reference}/submit")
    @PreAuthorize("hasAuthority('CATALOGUE_SUBMIT')")
    @RequestAction("SUBMIT CATALOGUE ITEM")
    public ApiResponse<ItemResponse> submitItem(@PathVariable String reference) {
        return ApiResponse.success("Sent for approval", catalogue.submit(reference));
    }

    @PostMapping("/api/v1/me/catalogue/{reference}/withdraw")
    @PreAuthorize("hasAuthority('CATALOGUE_WITHDRAW')")
    @RequestAction("WITHDRAW CATALOGUE ITEM")
    public ApiResponse<ItemResponse> withdrawItem(@PathVariable String reference,
                                                  @Valid @RequestBody WithdrawRequest request) {
        return ApiResponse.success("Taken down", catalogue.withdraw(reference, request));
    }

    @PostMapping("/api/v1/me/catalogue/{reference}/delete")
    @PreAuthorize("hasAuthority('CATALOGUE_DELETE')")
    @RequestAction("ARCHIVE CATALOGUE ITEM")
    public ApiResponse<Void> archiveItem(@PathVariable String reference) {
        catalogue.archive(reference);
        return ApiResponse.success("Archived", null);
    }

    @PostMapping("/api/v1/me/catalogue/{reference}/image")
    @PreAuthorize("hasAuthority('CATALOGUE_UPDATE')")
    @RequestAction("UPLOAD CATALOGUE IMAGE")
    public ApiResponse<ItemResponse> uploadItemImage(@PathVariable String reference,
                                                     @RequestParam("file") MultipartFile file) {
        return ApiResponse.success("Image replaced", catalogue.uploadImage(reference, file));
    }
}
