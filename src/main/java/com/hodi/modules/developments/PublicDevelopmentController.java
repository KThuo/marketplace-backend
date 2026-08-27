package com.hodi.modules.developments;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.modules.developments.DevelopmentDtos.PublicDevelopmentResponse;
import com.hodi.modules.developments.DevelopmentDtos.PublicPhaseResponse;
import com.hodi.modules.developments.DevelopmentDtos.PublicUnitTypeResponse;
import com.hodi.modules.developments.PublicDevelopmentService.PublicDevelopmentSearchRequest;
import com.hodi.modules.properties.ProgressUpdateService.PublicUpdate;
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
    private final DevelopmentProgressService progress;

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

    /**
     * One project's published progress, newest work first.
     *
     * <p>A tracked project — one a lender is financing and nobody is selling — is not found here at all, and
     * that is the whole rule: it is not LIVE, so it fails the same predicate that keeps it out of search. No
     * second flag anybody has to remember.
     */
    @GetMapping("/{reference}/progress")
    public ApiResponse<List<PublicUpdate>> progress(@PathVariable String reference) {
        return ApiResponse.success(progress.published(reference));
    }
}
