package com.hodi.modules.ratings;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.ratings.RatingService.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Ratings from three sides: the person who writes one, the party it is about, and the moderator (M7).
 *
 * <p>Writing is under {@code /me} and needs only a session — a buyer's own area, identity-scoped, with no
 * permission to grant. Reading somebody's ratings is public. Everything else is a permission.
 */
@RestController
@RequiredArgsConstructor
public class RatingController {

    private final RatingService ratings;

    // ── what a stranger reads ─────────────────────────────────────────────────

    @GetMapping("/api/v1/public/ratings/{subjectType}/{subjectRef}")
    public ApiResponse<SubjectRatings> forSubject(@PathVariable String subjectType,
                                                  @PathVariable String subjectRef) {
        return ApiResponse.success(ratings.forSubject(subjectType, subjectRef));
    }

    // ── what a buyer writes ───────────────────────────────────────────────────

    @PostMapping("/api/v1/me/ratings")
    @RequestAction("LEAVE A RATING")
    public ApiResponse<RatingResponse> save(@Valid @RequestBody SaveRatingRequest request) {
        return ApiResponse.success("Thank you", ratings.save(request));
    }

    @GetMapping("/api/v1/me/ratings")
    public ApiResponse<PagedResponse<RatingResponse>> mine(@ModelAttribute RatingListRequest request) {
        return ApiResponse.success(ratings.mine(request));
    }

    @PostMapping("/api/v1/me/ratings/{reference}/withdraw")
    @RequestAction("WITHDRAW MY RATING")
    public ApiResponse<Void> withdrawMine(@PathVariable String reference) {
        ratings.withdrawMine(reference);
        return ApiResponse.success("Withdrawn", null);
    }

    /**
     * Objecting to somebody else's rating.
     *
     * <p>Under {@code /me} and needing only a session, because anybody who can read a rating should be able
     * to say it is wrong. It does not take the rating down — see {@code RatingService.report}.
     */
    @PostMapping("/api/v1/me/ratings/{reference}/report")
    @RequestAction("REPORT A RATING")
    public ApiResponse<Void> report(@PathVariable String reference,
                                    @Valid @RequestBody ReportRequest request) {
        ratings.report(reference, request);
        return ApiResponse.success("Reported — a moderator will look at it", null);
    }

    // ── what the rated party does ─────────────────────────────────────────────

    @GetMapping("/api/v1/ratings/about-us")
    @PreAuthorize("hasAuthority('RATINGS_VIEW')")
    public ApiResponse<PagedResponse<RatingResponse>> aboutUs(
            @ModelAttribute RatingListRequest request) {
        return ApiResponse.success(ratings.aboutUs(request));
    }

    @GetMapping("/api/v1/ratings/our-summary")
    @PreAuthorize("hasAuthority('RATINGS_VIEW')")
    public ApiResponse<SummaryResponse> ourSummary() {
        return ApiResponse.success(ratings.ourSummary());
    }

    @PostMapping("/api/v1/ratings/{reference}/reply")
    @PreAuthorize("hasAuthority('RATINGS_REPLY')")
    @RequestAction("REPLY TO A RATING")
    public ApiResponse<RatingResponse> reply(@PathVariable String reference,
                                             @Valid @RequestBody ReplyRequest request) {
        return ApiResponse.success("Reply posted", ratings.reply(reference, request));
    }

    // ── what a moderator does ─────────────────────────────────────────────────

    @GetMapping("/api/v1/moderation/list")
    @PreAuthorize("hasAuthority('MODERATION_VIEW')")
    public ApiResponse<PagedResponse<RatingResponse>> queue(@ModelAttribute RatingListRequest request) {
        return ApiResponse.success(ratings.queue(request));
    }

    @GetMapping("/api/v1/moderation/pending-count")
    @PreAuthorize("hasAuthority('MODERATION_VIEW')")
    public ApiResponse<Long> pendingCount() {
        return ApiResponse.success(ratings.pendingModerationCount());
    }

    @GetMapping("/api/v1/moderation/{reference}/reports")
    @PreAuthorize("hasAuthority('MODERATION_VIEW')")
    public ApiResponse<List<RatingReport>> reports(@PathVariable String reference) {
        return ApiResponse.success(ratings.reportsFor(reference));
    }

    @PostMapping("/api/v1/moderation/{reference}/decide")
    @PreAuthorize("hasAuthority('MODERATION_DECIDE')")
    @RequestAction("MODERATE A RATING")
    public ApiResponse<RatingResponse> moderate(@PathVariable String reference,
                                                @Valid @RequestBody ModerateRequest request) {
        return ApiResponse.success("Decision recorded", ratings.moderate(reference, request));
    }
}
