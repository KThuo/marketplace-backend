package com.hodi.modules.developments;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.modules.developments.DevelopmentDtos.PublicDevelopmentResponse;
import com.hodi.modules.developments.DevelopmentDtos.PublicPhaseResponse;
import com.hodi.modules.developments.DevelopmentDtos.PublicUnitTypeResponse;
import com.hodi.modules.developments.PublicDevelopmentService.PublicDevelopmentSearchRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * The marketplace's view of developments. No authentication.
 *
 * <p>A different controller and different response records from {@link DevelopmentController}, not the same
 * ones with fields blanked. The budget, the facility, the address line and every buyer's name are absent from
 * the shapes this returns, so none of them can escape by somebody forgetting to null a field.
 */
@RestController
@RequestMapping("/api/v1/public/developments")
@RequiredArgsConstructor
public class PublicDevelopmentController {

    private final PublicDevelopmentService service;

    @GetMapping("/search")
    public ApiResponse<PagedResponse<PublicDevelopmentResponse>> search(
            @ModelAttribute PublicDevelopmentSearchRequest request) {
        return ApiResponse.success(service.search(request));
    }

    @GetMapping("/{reference}")
    public ApiResponse<PublicDevelopmentResponse> find(@PathVariable String reference) {
        return ApiResponse.success(service.find(reference));
    }

    /** "Sixty of seventy two-beds available, from KES 9.5M" — one row per typology. */
    @GetMapping("/{reference}/availability")
    public ApiResponse<List<PublicUnitTypeResponse>> availability(@PathVariable String reference) {
        return ApiResponse.success(service.availability(reference));
    }

    @GetMapping("/{reference}/phases")
    public ApiResponse<List<PublicPhaseResponse>> phases(@PathVariable String reference) {
        return ApiResponse.success(service.phases(reference));
    }
}
