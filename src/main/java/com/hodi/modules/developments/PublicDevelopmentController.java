package com.hodi.modules.developments;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.modules.developments.DevelopmentDtos.PublicDevelopmentResponse;
import com.hodi.modules.developments.DevelopmentDtos.PublicUnitTypeResponse;
import com.hodi.modules.developments.PublicDevelopmentService.PublicDevelopmentSearchRequest;
import com.hodi.modules.developments.DevelopmentDtos.PublicPost;
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

    /*
     * There is no public phases endpoint.
     *
     * There was one, and it fed a phase-by-phase panel on the project's page: names, percentages, planned
     * dates and the revised date next to each. That is detailed build reporting — a slipped completion date is
     * information for the people running and financing the build, not for whoever is browsing. The panel is
     * now a headline on the detail response instead, and the phases themselves are in the workspace where the
     * permission to see them lives.
     */

    /**
     * A project's public posts — the blog and newsletter kind, newest first.
     *
     * <p>Only posts written for the public. A detailed update somebody published is still a detailed update,
     * and the query behind this is where that is decided rather than a filter here.
     *
     * <p>A tracked project — one a lender is financing and nobody is selling — is not found here at all, and
     * that is the whole rule: it is not LIVE, so it fails the same predicate that keeps it out of search. No
     * second flag anybody has to remember.
     */
    @GetMapping("/{reference}/posts")
    public ApiResponse<List<PublicPost>> posts(@PathVariable String reference) {
        return ApiResponse.success(progress.publicPosts(reference));
    }
}
