package com.hodi.modules.sellerops;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.sellerops.CommissionService.*;
import com.hodi.modules.sellerops.PromotionService.*;
import com.hodi.modules.sellerops.PropertyTypeService.SaveTypeRequest;
import com.hodi.modules.sellerops.PropertyTypeService.TypeResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The seller-side back office: what a listing can be, what a placement costs, and what is owed (M13).
 *
 * <p>The property types are also served publicly — the listing form and the marketplace's own filters both
 * need to know what kinds exist, and one of those is used by people with no account.
 */
@RestController
@RequiredArgsConstructor
public class SellerOpsController {

    private final PropertyTypeService types;
    private final PromotionService promotions;
    private final CommissionService commissions;

    // ── property types ────────────────────────────────────────────────────────

    /** Public: the marketplace's filters and the listing form both need it, and one of them has no session. */
    @GetMapping("/api/v1/public/property-types")
    public ApiResponse<List<TypeResponse>> publicTypes() {
        return ApiResponse.success(types.active());
    }

    @GetMapping("/api/v1/property-types")
    @PreAuthorize("hasAuthority('PROPERTY_TYPES_VIEW')")
    public ApiResponse<List<TypeResponse>> allTypes() {
        return ApiResponse.success(types.all());
    }

    @PostMapping("/api/v1/property-types/create")
    @PreAuthorize("hasAuthority('PROPERTY_TYPES_MANAGE')")
    @RequestAction("CREATE PROPERTY TYPE")
    public ApiResponse<TypeResponse> createType(@Valid @RequestBody SaveTypeRequest request) {
        return ApiResponse.success("Added", types.create(request));
    }

    @PostMapping("/api/v1/property-types/{code}/update")
    @PreAuthorize("hasAuthority('PROPERTY_TYPES_MANAGE')")
    @RequestAction("UPDATE PROPERTY TYPE")
    public ApiResponse<TypeResponse> updateType(@PathVariable String code,
                                                @Valid @RequestBody SaveTypeRequest request) {
        return ApiResponse.success("Saved", types.update(code, request));
    }

    @PostMapping("/api/v1/property-types/{code}/set-active")
    @PreAuthorize("hasAuthority('PROPERTY_TYPES_MANAGE')")
    @RequestAction("SET PROPERTY TYPE ACTIVE")
    public ApiResponse<TypeResponse> setTypeActive(@PathVariable String code,
                                                   @RequestBody Map<String, Boolean> body) {
        return ApiResponse.success("Saved",
                types.setActive(code, Boolean.TRUE.equals(body.get("active"))));
    }

    // ── promotion packages ────────────────────────────────────────────────────

    @GetMapping("/api/v1/promotion-packages")
    @PreAuthorize("hasAuthority('PROMOTIONS_VIEW')")
    public ApiResponse<List<PackageResponse>> packages() {
        return ApiResponse.success(promotions.allPackages());
    }

    /** What a seller may actually buy, which is the active ones. */
    @GetMapping("/api/v1/promotion-packages/available")
    @PreAuthorize("hasAuthority('PROMOTIONS_REQUEST')")
    public ApiResponse<List<PackageResponse>> availablePackages() {
        return ApiResponse.success(promotions.activePackages());
    }

    @PostMapping("/api/v1/promotion-packages/create")
    @PreAuthorize("hasAuthority('PROMOTIONS_MANAGE')")
    @RequestAction("CREATE PROMOTION PACKAGE")
    public ApiResponse<PackageResponse> createPackage(@Valid @RequestBody SavePackageRequest request) {
        return ApiResponse.success("Added", promotions.createPackage(request));
    }

    @PostMapping("/api/v1/promotion-packages/{reference}/update")
    @PreAuthorize("hasAuthority('PROMOTIONS_MANAGE')")
    @RequestAction("UPDATE PROMOTION PACKAGE")
    public ApiResponse<PackageResponse> updatePackage(@PathVariable String reference,
                                                      @Valid @RequestBody SavePackageRequest request) {
        return ApiResponse.success("Saved", promotions.updatePackage(reference, request));
    }

    @PostMapping("/api/v1/promotion-packages/{reference}/set-active")
    @PreAuthorize("hasAuthority('PROMOTIONS_MANAGE')")
    @RequestAction("SET PROMOTION PACKAGE ACTIVE")
    public ApiResponse<PackageResponse> setPackageActive(@PathVariable String reference,
                                                         @RequestBody Map<String, Boolean> body) {
        return ApiResponse.success("Saved",
                promotions.setPackageActive(reference, Boolean.TRUE.equals(body.get("active"))));
    }

    // ── placements ────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/promotions/list")
    @PreAuthorize("hasAuthority('PROMOTIONS_VIEW')")
    public ApiResponse<PagedResponse<PromotionResponse>> listPromotions(
            @ModelAttribute PromotionListRequest request) {
        return ApiResponse.success(promotions.list(request));
    }

    @PostMapping("/api/v1/promotions/request")
    @PreAuthorize("hasAuthority('PROMOTIONS_REQUEST')")
    @RequestAction("REQUEST A PLACEMENT")
    public ApiResponse<PromotionResponse> requestPromotion(
            @Valid @RequestBody RequestPromotionRequest request) {
        return ApiResponse.success("Asked for", promotions.request(request));
    }

    @PostMapping("/api/v1/promotions/{reference}/activate")
    @PreAuthorize("hasAuthority('PROMOTIONS_MANAGE')")
    @RequestAction("START A PLACEMENT")
    public ApiResponse<PromotionResponse> activatePromotion(@PathVariable String reference) {
        return ApiResponse.success("Running", promotions.activate(reference));
    }

    @PostMapping("/api/v1/promotions/{reference}/cancel")
    @PreAuthorize("hasAuthority('PROMOTIONS_MANAGE')")
    @RequestAction("STOP A PLACEMENT")
    public ApiResponse<PromotionResponse> cancelPromotion(@PathVariable String reference,
                                                          @Valid @RequestBody CancelRequest request) {
        return ApiResponse.success("Stopped", promotions.cancel(reference, request));
    }

    // ── commission ────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/commissions/list")
    @PreAuthorize("hasAuthority('COMMISSIONS_VIEW')")
    public ApiResponse<PagedResponse<CommissionResponse>> listCommissions(
            @ModelAttribute CommissionListRequest request) {
        return ApiResponse.success(commissions.list(request));
    }

    @GetMapping("/api/v1/commissions/totals")
    @PreAuthorize("hasAuthority('COMMISSIONS_VIEW')")
    public ApiResponse<CommissionTotals> commissionTotals() {
        return ApiResponse.success(commissions.totals());
    }

    @PostMapping("/api/v1/commissions/{reference}/settle")
    @PreAuthorize("hasAuthority('COMMISSIONS_SETTLE')")
    @RequestAction("SETTLE A COMMISSION")
    public ApiResponse<CommissionResponse> settle(@PathVariable String reference,
                                                  @Valid @RequestBody SettleRequest request) {
        return ApiResponse.success("Recorded", commissions.settle(reference, request));
    }
}
