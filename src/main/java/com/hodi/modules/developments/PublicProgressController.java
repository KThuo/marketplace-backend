package com.hodi.modules.developments;

import com.hodi.common.ApiResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.PagedResponse;
import com.hodi.modules.developments.PublicDevelopmentService.PublicProgressItem;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public progress feed: every live project's published updates, newest first. No authentication.
 *
 * <p>Its own controller rather than another method on {@link PublicDevelopmentController}, because the path
 * has no development in it. {@code /public/progress} is a feed across projects; nesting it under
 * {@code /public/developments} would have read as a development's own timeline, which is the endpoint next to
 * it and a different answer.
 *
 * <p>Paged rather than a fixed list, and consumed by {@code InfiniteMore} on the site — the same treatment the
 * marketplace's own card grids got, so a reader scrolling a feed is never asked to pick a page number.
 */
@RestController
@RequestMapping("/api/v1/public/progress")
@RequiredArgsConstructor
public class PublicProgressController {

    private final PublicDevelopmentService service;

    @GetMapping
    public ApiResponse<PagedResponse<PublicProgressItem>> feed(@ModelAttribute PagedDataRequest request) {
        return ApiResponse.success(service.progressFeed(request));
    }
}
