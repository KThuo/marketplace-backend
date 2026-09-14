package com.hodi.modules.developments;

import com.hodi.common.ApiResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.PagedResponse;
import com.hodi.modules.developments.PublicDevelopmentService.PublicPostDetail;
import com.hodi.modules.developments.PublicDevelopmentService.PublicProgressItem;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
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

    /**
     * One post, on its own page.
     *
     * <p>By reference rather than by hash id, like every other public route here: this is the address that
     * gets pasted into a message, and it has to survive being read back over the phone.
     *
     * <p>Under the feed rather than under the project, because the feed is where the link is followed from
     * and a post is one thing whichever project it belongs to. The project's own timeline is still
     * {@code /public/developments/{reference}/posts}, and both now carry the reference this resolves.
     */
    @GetMapping("/{reference}")
    public ApiResponse<PublicPostDetail> post(@PathVariable String reference) {
        return ApiResponse.success(service.post(reference));
    }
}
