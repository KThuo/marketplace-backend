package com.hodi.modules.ratings;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.security.TenantScope;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Ratings, and what happens when somebody objects to one (M7).
 *
 * <h2>Published, then moderated</h2>
 *
 * <p>A rating appears the moment it is written. A review site that holds every review for a day is a review
 * site nobody writes to, and the platform's own listings already go through Maker/Checker — applying it to
 * opinions as well would make the feedback loop useless.
 *
 * <p>What the moderation queue holds is the ones somebody has complained about, plus any whose text trips
 * the configured word list on the way in. The common case is fast; the bad case is caught.
 *
 * <h2>The aggregate has one writer</h2>
 *
 * <p>{@link #recompute} is the only thing that touches {@code rating_summaries}, and it recomputes from the
 * ratings rather than incrementing. An increment missed once is wrong for ever; a recompute over one
 * subject's ratings is cheap, and it is the correction for the defect M10 shipped with — a counter declared
 * as an aggregate and maintained by nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RatingService {

    private final RatingRepository ratings;
    private final RatingReportRepository reports;
    private final RatingSummaryRepository summaries;
    private final RatingSubjectResolver subjects;
    private final RatingVerifier verifier;
    private final ConfigurationService configs;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record RatingResponse(
            String reference,
            String subjectType,
            String subjectRef,
            String subjectLabel,
            String raterName,
            short score,
            String title,
            String body,
            boolean verified,
            String verifiedVia,
            String state,
            /** Why it is waiting rather than showing. The author is told; a stranger is not. */
            String heldReason,
            String replyBody,
            OffsetDateTime repliedAt,
            int reportCount,
            String moderationNote,
            OffsetDateTime createdAt) {}

    /** What a stranger sees. No moderation fields, no report count, no rater identity beyond a name. */
    public record PublicRating(
            String reference,
            String raterName,
            short score,
            String title,
            String body,
            boolean verified,
            String verifiedVia,
            String replyBody,
            OffsetDateTime repliedAt,
            OffsetDateTime createdAt) {}

    public record SummaryResponse(
            String subjectType,
            String subjectRef,
            int ratingCount,
            BigDecimal averageScore,
            Map<String, Integer> histogram) {}

    public record SubjectRatings(SummaryResponse summary, List<PublicRating> ratings) {}

    public record SaveRatingRequest(
            @NotBlank(message = "What are you rating?") String subjectType,
            @NotBlank(message = "Which one?") String subjectRef,
            @NotNull(message = "Give it a score") @Min(1) @Max(5) Short score,
            @Size(max = 180) String title,
            String body) {}

    public record ReportRequest(
            @NotBlank(message = "Say what is wrong with it") String reason,
            String detail) {}

    public record ReplyRequest(
            @NotBlank(message = "Write your reply") String body) {}

    public record ModerateRequest(
            @NotBlank(message = "Say what you are doing") String decision,
            String note) {}

    @Getter
    @Setter
    public static class RatingListRequest extends PagedDataRequest {
        private String subjectType;
        private String state;
        /** When true, only the ones a moderator has to look at. */
        private Boolean needsAttention;
    }

    // ── writing ───────────────────────────────────────────────────────────────

    /**
     * Leave a rating, or change the one already left.
     *
     * <p>One per person per subject, by unique index. A second opinion is an edit of the first rather than
     * another row — otherwise the loudest reviewer is simply the one who came back most often.
     */
    @Transactional
    public RatingResponse save(SaveRatingRequest request) {
        UserPrincipal caller = AuthContext.require();
        String type = request.subjectType().trim().toUpperCase(Locale.ROOT);
        RatingSubjectResolver.Subject subject = subjects.resolve(type, request.subjectRef());

        /*
         * Nobody rates their own organisation.
         *
         * Not a hypothetical: the review panel appears on a vendor's own public page, and the first thing
         * anybody does with a new page is try it on themselves. A five-star review of yourself is the one
         * kind of review that is worthless by construction.
         */
        if (subject.ownerTenantId() != null && subject.ownerTenantId().equals(caller.getTenantId())) {
            throw new HodiException("You cannot review your own organisation.", HttpStatus.FORBIDDEN);
        }

        Rating rating = ratings.findByUserIdAndSubjectTypeAndSubjectIdAndStatusNot(
                        caller.getUserId(), type, subject.id(), AppConstant.STATUS_DELETED)
                .orElseGet(() -> Rating.builder()
                        .reference(freshReference())
                        .subjectType(type)
                        .subjectId(subject.id())
                        .userId(caller.getUserId())
                        .createdBy(caller.getUsername())
                        .build());

        rating.setSubjectRef(subject.reference());
        rating.setSubjectLabel(subject.label());
        rating.setSubjectTenantId(subject.ownerTenantId());
        rating.setRaterName(caller.getFullName());
        rating.setScore(request.score());
        rating.setTitle(blankToNull(request.title()));
        rating.setBody(blankToNull(request.body()));
        rating.setUpdatedBy(caller.getUsername());

        Optional<String> via = verifier.verify(caller.getUserId(), type, subject.id());
        rating.setVerified(via.isPresent());
        rating.setVerifiedVia(via.orElse(null));

        /*
         * The word list, applied on the way in.
         *
         * Not a judgement about the words — a rating held here is not rejected, it is queued. What it buys
         * is that the obvious cases never appear publicly even for the minutes before somebody reports them,
         * which is the window that matters for a name, a phone number or an accusation.
         */
        String tripped = trippedWord(rating);
        if (tripped != null) {
            rating.setState(RatingSubject.HELD);
            rating.setHeldReason("Held for review: the text contains \"" + tripped + "\".");
        } else if (!RatingSubject.HIDDEN.equals(rating.getState())) {
            // A hidden rating stays hidden when its author edits it. Editing is not an appeal.
            rating.setState(RatingSubject.PUBLISHED);
            rating.setHeldReason(null);
        }

        Rating saved = ratings.save(rating);
        recompute(saved.getSubjectType(), saved.getSubjectId());
        audit.record(AppConstant.ACTION_CREATE, "Rating", saved.getId(), null,
                "%d/5 on %s %s".formatted(saved.getScore(), type, saved.getSubjectRef()));
        return toResponse(saved);
    }

    /** The ratings this person has written. */
    @Transactional(readOnly = true)
    public PagedResponse<RatingResponse> mine(RatingListRequest request) {
        Specification<Rating> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.eq("userId", AuthContext.requireUserId()),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("subjectType", blankToNull(request.getSubjectType())));
        var page = ratings.findAll(spec, request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional
    public void withdrawMine(String reference) {
        Rating rating = ratings.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Rating", reference));
        if (!rating.getUserId().equals(AuthContext.requireUserId())) {
            throw new HodiException("That is not yours.", HttpStatus.FORBIDDEN);
        }
        rating.setStatus(AppConstant.STATUS_DELETED);
        rating.setStatusFlag(AppConstant.FLAG_DELETED);
        rating.setUpdatedBy(AuthContext.username());
        ratings.save(rating);
        recompute(rating.getSubjectType(), rating.getSubjectId());
        audit.record(AppConstant.ACTION_DELETE, "Rating", rating.getId(), null, "withdrawn by its author");
    }

    // ── reading ───────────────────────────────────────────────────────────────

    /** One subject's public ratings and its summary. The property and vendor pages read this. */
    @Transactional(readOnly = true)
    public SubjectRatings forSubject(String subjectType, String subjectRef) {
        String type = subjectType.trim().toUpperCase(Locale.ROOT);
        RatingSubjectResolver.Subject subject = subjects.resolve(type, subjectRef);
        List<PublicRating> published = ratings.findCounted(type, subject.id()).stream()
                .map(this::toPublic)
                .toList();
        return new SubjectRatings(summaryFor(type, subject.id(), subject.reference()), published);
    }

    @Transactional(readOnly = true)
    public SummaryResponse summaryFor(String subjectType, Long subjectId, String subjectRef) {
        return summaries.findBySubjectTypeAndSubjectId(subjectType, subjectId)
                .map(s -> new SummaryResponse(subjectType, subjectRef, s.getRatingCount(),
                        s.getAverageScore(),
                        Map.of("1", s.getScore1(), "2", s.getScore2(), "3", s.getScore3(),
                                "4", s.getScore4(), "5", s.getScore5())))
                .orElse(new SummaryResponse(subjectType, subjectRef, 0, null,
                        Map.of("1", 0, "2", 0, "3", 0, "4", 0, "5", 0)));
    }

    /**
     * The ratings about the caller's own organisation.
     *
     * <p>Every state, not only the published ones: a seller should see that something about them is held or
     * hidden, because the alternative is discovering a review only when it is already public.
     */
    @Transactional(readOnly = true)
    public PagedResponse<RatingResponse> aboutUs(RatingListRequest request) {
        /*
         * Platform staff see everything here, rather than an error.
         *
         * The first version demanded an organisation and returned 400 to anybody without one — which is
         * every platform administrator, and the Reviews screen is in their navigation. "Reviews about us"
         * has no meaning for the platform, but "everything people are saying" does, and it is the useful
         * companion to the moderation queue: one screen for what was said, one for what was objected to.
         */
        Long tenantId = TenantScope.unrestricted() ? null : AuthContext.tenantId();
        if (tenantId == null && !TenantScope.unrestricted()) {
            throw new HodiException("Your account is not attached to an organisation.",
                    HttpStatus.BAD_REQUEST);
        }
        Specification<Rating> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                tenantId == null ? null : SearchSpecs.eq("subjectTenantId", tenantId),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("subjectType", blankToNull(request.getSubjectType())));
        var page = ratings.findAll(spec, request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    /**
     * The caller's organisation's headline figure.
     *
     * <p>Across every subject type, not just the organisation itself. A seller is rated on their listings
     * and on themselves; an agent on both plus their own record. Reading only the SELLER summary — which is
     * what the first version did — showed a vendor with a published review a count of zero, because the
     * review was filed against the vendor rather than against the tenant.
     *
     * <p>Computed rather than cached: it is one query over one organisation's ratings, on a page nobody
     * loads in a loop, and a sixth cached aggregate keyed by tenant is a sixth thing to keep in step.
     */
    @Transactional(readOnly = true)
    public SummaryResponse ourSummary() {
        if (TenantScope.unrestricted()) {
            // The platform's own headline is the whole platform's, for the same reason as above.
            return summarise(RatingSubject.SELLER, null, ratings.findAllCounted());
        }
        Long tenantId = AuthContext.tenantId();
        if (tenantId == null) return summarise(RatingSubject.SELLER, null, List.of());
        return summarise(RatingSubject.SELLER, null, ratings.findCountedForTenant(tenantId));
    }

    /** The shape both summary paths produce, from a list of counted ratings. */
    private SummaryResponse summarise(String subjectType, String subjectRef, List<Rating> counted) {
        int[] histogram = new int[6];
        int total = 0;
        for (Rating r : counted) {
            histogram[r.getScore()]++;
            total += r.getScore();
        }
        BigDecimal average = counted.isEmpty() ? null
                : BigDecimal.valueOf(total).divide(BigDecimal.valueOf(counted.size()), 2,
                        RoundingMode.HALF_UP);
        return new SummaryResponse(subjectType, subjectRef, counted.size(), average,
                Map.of("1", histogram[1], "2", histogram[2], "3", histogram[3],
                        "4", histogram[4], "5", histogram[5]));
    }

    // ── reporting and moderation ──────────────────────────────────────────────

    /**
     * Somebody objects.
     *
     * <p>Reporting does not take a rating down. That decision belongs to a moderator, and a platform where
     * one complaint removes a review is a platform where the least happy party controls what everybody
     * reads.
     */
    @Transactional
    public void report(String reference, ReportRequest request) {
        Rating rating = ratings.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Rating", reference));
        Long userId = AuthContext.requireUserId();
        if (reports.existsByRatingIdAndUserId(rating.getId(), userId)) {
            throw new HodiException("You have already reported this one.", HttpStatus.CONFLICT);
        }
        reports.save(RatingReport.builder()
                .ratingId(rating.getId())
                .userId(userId)
                .reason(request.reason().trim().toUpperCase(Locale.ROOT))
                .detail(blankToNull(request.detail()))
                .build());
        rating.setReportCount((int) reports.countByRatingId(rating.getId()));
        ratings.save(rating);
        audit.record(AppConstant.ACTION_REQUEST, "Rating", rating.getId(), null,
                "reported: " + request.reason());
    }

    @Transactional(readOnly = true)
    public PagedResponse<RatingResponse> queue(RatingListRequest request) {
        Specification<Rating> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("subjectType", blankToNull(request.getSubjectType())),
                SearchSpecs.eq("state", blankToNull(request.getState())),
                Boolean.TRUE.equals(request.getNeedsAttention())
                        ? (root, query, cb) -> cb.or(
                                cb.equal(root.get("state"), RatingSubject.HELD),
                                cb.greaterThan(root.get("reportCount"), 0))
                        : null);
        // Oldest first: the one somebody has been waiting on longest, not the newest complaint.
        var page = ratings.findAll(spec, request.toPageable(Sort.by(Sort.Direction.ASC, "createdAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public long pendingModerationCount() {
        return ratings.countNeedingModeration();
    }

    @Transactional(readOnly = true)
    public List<RatingReport> reportsFor(String reference) {
        Rating rating = ratings.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Rating", reference));
        return reports.findByRatingIdOrderByCreatedAtDesc(rating.getId());
    }

    /** Publish it, or take it down. Taking it down says why — see the table's own CHECK. */
    @Transactional
    public RatingResponse moderate(String reference, ModerateRequest request) {
        Rating rating = ratings.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Rating", reference));
        String decision = request.decision().trim().toUpperCase(Locale.ROOT);

        switch (decision) {
            case "PUBLISH" -> {
                rating.setState(RatingSubject.PUBLISHED);
                rating.setHeldReason(null);
            }
            case "HIDE" -> {
                if (request.note() == null || request.note().isBlank()) {
                    throw new HodiException("Say why it is coming down.", HttpStatus.BAD_REQUEST);
                }
                rating.setState(RatingSubject.HIDDEN);
            }
            default -> throw new HodiException("Publish it or hide it.", HttpStatus.BAD_REQUEST);
        }

        rating.setModeratedAt(OffsetDateTime.now());
        rating.setModeratedByUserId(AuthContext.userId());
        rating.setModerationNote(blankToNull(request.note()));
        rating.setUpdatedBy(AuthContext.username());
        Rating saved = ratings.save(rating);
        recompute(saved.getSubjectType(), saved.getSubjectId());
        audit.record(AppConstant.AUDIT_RATING_MODERATED, "Rating", saved.getId(), null,
                decision + (request.note() == null ? "" : ": " + request.note()));
        return toResponse(saved);
    }

    /**
     * The subject's right of reply.
     *
     * <p>One reply, not a conversation. A thread under a review turns a rating into an argument in public,
     * and the person who wrote it has already said what they came to say.
     */
    @Transactional
    public RatingResponse reply(String reference, ReplyRequest request) {
        Rating rating = ratings.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Rating", reference));
        subjects.assertMayReply(rating.getSubjectType(), rating.getSubjectId());
        rating.setReplyBody(request.body().trim());
        rating.setRepliedAt(OffsetDateTime.now());
        rating.setRepliedByUserId(AuthContext.userId());
        rating.setUpdatedBy(AuthContext.username());
        Rating saved = ratings.save(rating);
        audit.record(AppConstant.ACTION_UPDATE, "Rating", saved.getId(), null, "replied to");
        return toResponse(saved);
    }

    // ── the aggregate ─────────────────────────────────────────────────────────

    /** Recomputed, never incremented. The only writer of {@code rating_summaries}. */
    @Transactional
    public void recompute(String subjectType, Long subjectId) {
        List<Rating> counted = ratings.findCounted(subjectType, subjectId);
        int[] histogram = new int[6];
        int total = 0;
        for (Rating r : counted) {
            histogram[r.getScore()]++;
            total += r.getScore();
        }
        RatingSummary summary = summaries.findBySubjectTypeAndSubjectId(subjectType, subjectId)
                .orElseGet(() -> RatingSummary.builder()
                        .subjectType(subjectType).subjectId(subjectId).build());
        summary.setRatingCount(counted.size());
        summary.setAverageScore(counted.isEmpty() ? null
                : BigDecimal.valueOf(total).divide(BigDecimal.valueOf(counted.size()), 2,
                        RoundingMode.HALF_UP));
        summary.setScore1(histogram[1]);
        summary.setScore2(histogram[2]);
        summary.setScore3(histogram[3]);
        summary.setScore4(histogram[4]);
        summary.setScore5(histogram[5]);
        summary.setUpdatedAt(OffsetDateTime.now());
        summaries.save(summary);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** @return the first configured word the text contains, or null */
    private String trippedWord(Rating rating) {
        String raw = configs.getString(ConfigKey.RATING_HELD_WORDS);
        if (raw == null || raw.isBlank()) return null;
        String haystack = ((rating.getTitle() == null ? "" : rating.getTitle()) + " "
                + (rating.getBody() == null ? "" : rating.getBody())).toLowerCase(Locale.ROOT);
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(w -> !w.isEmpty())
                .map(w -> w.toLowerCase(Locale.ROOT))
                .filter(haystack::contains)
                .findFirst()
                .orElse(null);
    }

    private String freshReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = RrnGenerator.generate("RV");
            if (!ratings.existsByReference(candidate)) return candidate;
        }
        throw new HodiException("Could not allocate a reference — try again.", HttpStatus.CONFLICT);
    }

    RatingResponse toResponse(Rating r) {
        return new RatingResponse(r.getReference(), r.getSubjectType(), r.getSubjectRef(),
                r.getSubjectLabel(), r.getRaterName(), r.getScore(), r.getTitle(), r.getBody(),
                r.isVerified(), r.getVerifiedVia(), r.getState(), r.getHeldReason(), r.getReplyBody(),
                r.getRepliedAt(),
                r.getReportCount(), r.getModerationNote(), r.getCreatedAt());
    }

    private PublicRating toPublic(Rating r) {
        return new PublicRating(r.getReference(), r.getRaterName(), r.getScore(), r.getTitle(),
                r.getBody(), r.isVerified(), r.getVerifiedVia(), r.getReplyBody(), r.getRepliedAt(),
                r.getCreatedAt());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
