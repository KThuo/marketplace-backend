package com.hodi.modules.finance;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.finance.FinanceDtos.ProductListRequest;
import com.hodi.modules.finance.FinanceDtos.ProductResponse;
import com.hodi.modules.finance.FinanceDtos.SaveProductRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * A bank's product catalogue.
 *
 * <p>Publishing carries its own permission, checked here and meant in the service: putting a rate in front of
 * the public is a different act from drafting one, and an institution may well want them done by different
 * people.
 */
@RestController
@RequestMapping("/api/v1/mortgage-products")
@RequiredArgsConstructor
public class MortgageProductController {

    private final MortgageProductService service;

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('MORTGAGE_PRODUCTS_VIEW')")
    public ApiResponse<PagedResponse<ProductResponse>> list(@ModelAttribute ProductListRequest request) {
        return ApiResponse.success(service.list(request));
    }

    @GetMapping("/find/{hashId}")
    @PreAuthorize("hasAuthority('MORTGAGE_PRODUCTS_VIEW')")
    public ApiResponse<ProductResponse> find(@PathVariable String hashId) {
        return ApiResponse.success(service.find(hashId));
    }

    @PostMapping("/create")
    @PreAuthorize("hasAuthority('MORTGAGE_PRODUCTS_CREATE')")
    @RequestAction("CREATE MORTGAGE PRODUCT")
    public ApiResponse<ProductResponse> create(@Valid @RequestBody SaveProductRequest request) {
        return ApiResponse.success("Product created", service.create(request));
    }

    @PostMapping("/update/{hashId}")
    @PreAuthorize("hasAuthority('MORTGAGE_PRODUCTS_UPDATE')")
    @RequestAction("UPDATE MORTGAGE PRODUCT")
    public ApiResponse<ProductResponse> update(@PathVariable String hashId,
                                               @Valid @RequestBody SaveProductRequest request) {
        return ApiResponse.success("Product updated", service.update(hashId, request));
    }

    @PostMapping("/publish/{hashId}")
    @PreAuthorize("hasAuthority('MORTGAGE_PRODUCTS_PUBLISH')")
    @RequestAction("PUBLISH MORTGAGE PRODUCT")
    public ApiResponse<ProductResponse> publish(@PathVariable String hashId) {
        return ApiResponse.success("Product is on the marketplace", service.setPublished(hashId, true));
    }

    @PostMapping("/withdraw/{hashId}")
    @PreAuthorize("hasAuthority('MORTGAGE_PRODUCTS_PUBLISH')")
    @RequestAction("WITHDRAW MORTGAGE PRODUCT")
    public ApiResponse<ProductResponse> withdraw(@PathVariable String hashId) {
        return ApiResponse.success("Product taken off the marketplace",
                service.setPublished(hashId, false));
    }

    @PostMapping("/deactivate/{hashId}")
    @PreAuthorize("hasAuthority('MORTGAGE_PRODUCTS_DEACTIVATE')")
    @RequestAction("DEACTIVATE MORTGAGE PRODUCT")
    public ApiResponse<ProductResponse> deactivate(@PathVariable String hashId,
                                                   @RequestBody(required = false) ReasonRequest request) {
        return ApiResponse.success("Product deactivated",
                service.setActive(hashId, false, request == null ? null : request.reason()));
    }

    @PostMapping("/activate/{hashId}")
    @PreAuthorize("hasAuthority('MORTGAGE_PRODUCTS_ACTIVATE')")
    @RequestAction("ACTIVATE MORTGAGE PRODUCT")
    public ApiResponse<ProductResponse> activate(@PathVariable String hashId) {
        return ApiResponse.success("Product activated", service.setActive(hashId, true, null));
    }

    @PostMapping("/delete/{hashId}")
    @PreAuthorize("hasAuthority('MORTGAGE_PRODUCTS_DELETE')")
    @RequestAction("ARCHIVE MORTGAGE PRODUCT")
    public ApiResponse<Void> archive(@PathVariable String hashId) {
        service.archive(hashId);
        return ApiResponse.success("Product archived", null);
    }

    public record ReasonRequest(String reason) {}
}
