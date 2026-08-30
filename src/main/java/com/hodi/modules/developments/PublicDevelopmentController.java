package com.hodi.modules.developments;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.modules.developments.DevelopmentDtos.PublicDevelopmentResponse;
import com.hodi.modules.developments.DevelopmentDtos.PublicUnitTypeResponse;
import com.hodi.modules.developments.DevelopmentUnitDtos.PublicUnitAvailability;
import com.hodi.modules.developments.DevelopmentUnitDtos.PublicUnitDetail;
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
     * Every unit of one kind, with its floor, its price and whether it is still going.
     *
     * <p>The drill-down from the availability list. Sold units are included and their buyers are not — what
     * has gone is half of what an off-plan buyer is reading for, and who took it is none of their business.
     *
     * <p>Under the development in the path, so a typology reference from another project cannot be read by
     * guessing at this endpoint.
     */
    @GetMapping("/{reference}/unit-types/{unitTypeReference}/units")
    public ApiResponse<List<PublicUnitAvailability>> units(@PathVariable String reference,
                                                            @PathVariable String unitTypeReference) {
        return ApiResponse.success(service.unitsFor(reference, unitTypeReference));
    }

    /**
     * One specific home.
     *
     * <p>Two flats of the same kind are not the same home — one has two balconies, one an open-plan kitchen,
     * one a third bath — so each has a page of its own. What it does not say for itself comes from its kind.
     */
    @GetMapping("/{reference}/units/{unitReference}")
    public ApiResponse<PublicUnitDetail> unit(@PathVariable String reference,
                                               @PathVariable String unitReference) {
        return ApiResponse.success(service.unitDetail(reference, unitReference));
    }

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
