package com.hodi.modules.properties;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.modules.properties.PropertyDtos.FacetsResponse;
import com.hodi.modules.properties.PropertyDtos.PublicPropertyResponse;
import com.hodi.modules.properties.PropertyDtos.PublicSearchRequest;
import com.hodi.logging.SkipRequestLog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * The marketplace, for anybody.
 *
 * <p>Unauthenticated by design: a house-hunter browses before they sign up, and requiring an account to look
 * is how a marketplace ends up with nothing to look at. Everything reachable here is a live listing, and the
 * response shape carries the town rather than the address — see {@code PublicPropertyResponse}.
 *
 * <p>Ids under {@code /api/v1/public/**} are salted with the fixed marketplace identity rather than per user,
 * so a link one person sends another still opens. That is {@code PublicMarketplaceFilter}'s doing, not this
 * controller's — but it is the reason a shared listing link works at all.
 */
@RestController
@RequestMapping("/api/v1/public/properties")
@RequiredArgsConstructor
public class PublicPropertyController {

    private final PublicPropertyService service;

    @GetMapping("/search")
    public ApiResponse<PagedResponse<PublicPropertyResponse>> search(
            @ModelAttribute PublicSearchRequest request) {
        return ApiResponse.success(service.search(request));
    }

    /**
     * What there is to filter by. Separate from the search so the filters do not have to be recomputed on
     * every page of results — and so a client can render them before the first search returns.
     */
    @GetMapping("/facets")
    @SkipRequestLog
    public ApiResponse<FacetsResponse> facets() {
        return ApiResponse.success(service.facets());
    }

    /** By reference, because that is what a buyer is given and what they quote back. */
    @GetMapping("/{reference}")
    public ApiResponse<PublicPropertyResponse> detail(@PathVariable String reference) {
        return ApiResponse.success(service.findByReference(reference));
    }
}
