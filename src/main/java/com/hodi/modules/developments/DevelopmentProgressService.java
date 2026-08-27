package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.infra.storage.StorageService;
import com.hodi.modules.progress.ProgressMilestoneRepository;
import com.hodi.modules.properties.ProgressUpdate;
import com.hodi.modules.properties.ProgressUpdateRepository;
import com.hodi.modules.developments.DevelopmentDtos.PublicPost;
import com.hodi.modules.properties.ProgressUpdateService.SaveUpdateRequest;
import com.hodi.modules.properties.ProgressUpdateService.UpdateResponse;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Progress updates on a development, its phases and its units.
 *
 * <p>The same table as a listing's updates and deliberately a different service. The two share a row shape and
 * almost nothing else: who may write is a collaborator grant here and {@code TenantScope} there, who may read
 * is a development's visibility rather than a listing's, and publishing is refused outright for a project that
 * is only being tracked. Folding both into one service would mean every method opening with a branch on which
 * kind of subject it had, which is the shape that lets a listing's rule be applied to a development by
 * accident.
 *
 * <h2>Why a bank's tracked project cannot publish</h2>
 *
 * <p>A lender financing a developer's rental block has no reason to put its progress on the public site, and
 * the cost of getting that wrong is disclosing a client's project. So publishing asks
 * {@link DevelopmentVisibility#mayPublishProgress} first, and a PRIVATE development is refused — with the
 * reason, because "forbidden" on a button somebody can see is not an answer.
 *
 * <h2>The public feed</h2>
 *
 * <p>{@link #publicFeed} is the cross-project timeline behind the marketplace's Progress tab. It resolves
 * which developments are public through {@link PublicDevelopmentService} rather than repeating the predicate,
 * so there is one definition of what "live" means and the feed cannot drift from the search that lists the
 * same projects.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevelopmentProgressService {

    private final ProgressUpdateRepository repository;
    private final DevelopmentRepository developments;
    private final DevelopmentPhaseRepository phases;
    private final DevelopmentUnitRepository units;
    private final ProgressMilestoneRepository milestones;
    private final com.hodi.modules.media.MediaAssetRepository media;
    private final DevelopmentVisibility visibility;
    private final StorageService storage;

    // ── the owner's side ──────────────────────────────────────────────────────

    /** Everything on this development's timeline, drafts included, for anyone entitled to read it. */
    @Transactional(readOnly = true)
    public List<UpdateResponse> forOwner(String developmentHashId) {
        Development development = requireVisible(developmentHashId);
        return repository.findForDevelopment(development.getId()).stream().map(this::toResponse).toList();
    }

    @Transactional
    public UpdateResponse create(String developmentHashId, SaveDevelopmentUpdateRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = load(developmentHashId);
        visibility.assertMayWriteProgress(development, caller);

        ProgressUpdate update = ProgressUpdate.builder()
                .developmentId(development.getId())
                // Cached from the development, not from the caller: a collaborator posting on a bank's project
                // must not make the row theirs, or their own organisation would inherit sight of it.
                .tenantId(development.getTenantId())
                .institutionId(development.getInstitutionId())
                .createdBy(AuthContext.username())
                .updatedBy(AuthContext.username())
                .build();
        apply(update, development, request);
        return toResponse(repository.save(update));
    }

    @Transactional
    public UpdateResponse update(String developmentHashId, String updateHashId,
                                 SaveDevelopmentUpdateRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = load(developmentHashId);
        visibility.assertMayWriteProgress(development, caller);

        ProgressUpdate update = loadUpdate(updateHashId, development);
        apply(update, development, request);
        update.setUpdatedBy(AuthContext.username());
        return toResponse(repository.save(update));
    }

    /**
     * Puts an update in front of the public, or takes it back off.
     *
     * <p>Taking it down is never refused, only putting it up. A project that turned private after publishing
     * has to be able to withdraw what is already out, and a rule that blocked both would trap it.
     */
    @Transactional
    public UpdateResponse setPublished(String developmentHashId, String updateHashId, boolean publish) {
        UserPrincipal caller = AuthContext.require();
        Development development = load(developmentHashId);
        visibility.assertMayWriteProgress(development, caller);

        if (publish && !visibility.mayPublishProgress(development)) {
            throw new HodiException(development.isPrivate()
                    ? "That project is private, so its progress stays with you and the developer. "
                            + "Publish the development first if buyers should see this."
                    : "Publish the development before publishing its progress.",
                    HttpStatus.CONFLICT);
        }

        ProgressUpdate update = loadUpdate(updateHashId, development);
        update.setPublished(publish);
        if (publish && update.getPublishedAt() == null) update.setPublishedAt(OffsetDateTime.now());
        update.setUpdatedBy(AuthContext.username());
        log.info("Progress update {} on development {} {} by {}", update.getId(), development.getReference(),
                publish ? "published" : "withdrawn", AuthContext.username());
        return toResponse(repository.save(update));
    }

    @Transactional
    public void archive(String developmentHashId, String updateHashId) {
        UserPrincipal caller = AuthContext.require();
        Development development = load(developmentHashId);
        visibility.assertMayWriteProgress(development, caller);

        ProgressUpdate update = loadUpdate(updateHashId, development);
        update.setStatus(AppConstant.STATUS_DELETED);
        update.setStatusFlag(AppConstant.FLAG_DELETED);
        // Unpublished on the way out, or an archived post keeps serving from the public feed.
        update.setPublished(false);
        update.setUpdatedBy(AuthContext.username());
        repository.save(update);
    }

    // ── the public's side ─────────────────────────────────────────────────────

    /**
     * A development's public posts — the blog and newsletter kind, newest first.
     *
     * <p>Only {@code audience = PUBLIC}, and the repository query is where that is decided rather than a
     * filter applied here: a detailed update somebody published is still a detailed update, and a condition
     * in a stream is one somebody can drop without the tests noticing.
     *
     * <p>Resolved through {@code findLiveByReference}, so a PRIVATE or draft project is not found at all
     * rather than found and returned empty — the difference matters, because an empty list invites a retry and
     * a 404 does not.
     */
    @Transactional(readOnly = true)
    public List<PublicPost> publicPosts(String developmentReference) {
        Development development = developments
                .findLiveByReference(developmentReference == null ? "" : developmentReference.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Development", developmentReference));
        return repository.findPublicForDevelopment(development.getId()).stream()
                .map(this::toPublicPost).toList();
    }

    /**
     * The most recent public post, for the project's own page.
     *
     * <p>One post rather than the timeline. A stranger deciding whether to enquire wants to see that something
     * is happening and what it looked like; the history of it is for the people with a stake, and the page
     * that shows a whole timeline to everybody is the page this change exists to undo.
     */
    @Transactional(readOnly = true)
    public Optional<PublicPost> latestPublicPost(String developmentReference) {
        return publicPosts(developmentReference).stream().findFirst();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private Development load(String hashId) {
        return developments.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Development", hashId));
    }

    private Development requireVisible(String hashId) {
        Development development = load(hashId);
        if (!visibility.mayRead(development, AuthContext.require())) {
            // Not found rather than forbidden: whether a project exists is itself something a stranger should
            // not learn from the difference between two error codes.
            throw new ResourceNotFoundException("Development", hashId);
        }
        return development;
    }

    /**
     * The update, if it belongs to this development.
     *
     * <p>The development's id leads the comparison so a listing-scoped update — whose {@code developmentId} is
     * null — fails the check instead of throwing.
     */
    private ProgressUpdate loadUpdate(String hashId, Development development) {
        ProgressUpdate update = repository.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Update", hashId));
        if (!development.getId().equals(update.getDevelopmentId())) {
            throw new ResourceNotFoundException("Update", hashId);
        }
        return update;
    }

    private void apply(ProgressUpdate update, Development development, SaveDevelopmentUpdateRequest request) {
        SaveUpdateRequest base = request.update();
        update.setTitle(base.title().trim());
        update.setBody(base.body() == null || base.body().isBlank() ? null : base.body().trim());
        if (base.reportedOn() != null) update.setReportedOn(base.reportedOn());

        Short percent = base.percentComplete();
        if (percent != null && (percent < 0 || percent > 100)) {
            throw new HodiException("Progress is a figure between 0 and 100.", HttpStatus.BAD_REQUEST);
        }
        update.setPercentComplete(percent);

        applyMilestone(update, base.milestone(), request.milestoneCode());
        applyPhase(update, development, request.phaseId());
        applyUnit(update, development, request.unitId());
        update.setAudience(audience(request.audience()));
    }

    /**
     * The stage, as a code where one was picked and as free text either way.
     *
     * <p>An unrecognised code is refused rather than stored: the column has a foreign key, so storing it would
     * fail at the database with a message nobody can act on. An unlisted stage still goes in as free text,
     * which is what the text column is for.
     */
    private void applyMilestone(ProgressUpdate update, String freeText, String code) {
        String trimmedCode = code == null || code.isBlank() ? null : code.trim().toUpperCase();
        if (trimmedCode != null && milestones.findLiveByCode(trimmedCode).isEmpty()) {
            throw new HodiException("That is not one of the build stages.", HttpStatus.BAD_REQUEST);
        }
        update.setMilestoneCode(trimmedCode);

        // The picked stage's name fills the text when the caller sent none, so a timeline built entirely from
        // the picker still reads as words rather than as codes.
        String text = freeText == null || freeText.isBlank() ? null : freeText.trim();
        if (text == null && trimmedCode != null) {
            text = milestones.findLiveByCode(trimmedCode).map(m -> m.getName()).orElse(null);
        }
        update.setMilestone(text);
    }

    /** Silence means STAKEHOLDERS: a caller who has not said is not a caller asking to publish. */
    private String audience(String requested) {
        if (requested == null || requested.isBlank()) return AppConstant.AUDIENCE_STAKEHOLDERS;
        String value = requested.trim().toUpperCase();
        if (!AppConstant.AUDIENCE_PUBLIC.equals(value)
                && !AppConstant.AUDIENCE_STAKEHOLDERS.equals(value)) {
            throw new HodiException("A post is either for the public or for the people building it.",
                    HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    private void applyPhase(ProgressUpdate update, Development development, String phaseHashId) {
        if (phaseHashId == null || phaseHashId.isBlank()) {
            update.setPhaseId(null);
            return;
        }
        DevelopmentPhase phase = phases.findById(HashIdUtil.decodeId(phaseHashId))
                .orElseThrow(() -> new ResourceNotFoundException("Phase", phaseHashId));
        // The phase must be this development's. The schema's composite keys stop a *row* pointing at another
        // project's phase and say nothing about a *request* naming one.
        if (!development.getId().equals(phase.getDevelopmentId())) {
            throw new ResourceNotFoundException("Phase", phaseHashId);
        }
        update.setPhaseId(phase.getId());
    }

    private void applyUnit(ProgressUpdate update, Development development, String unitHashId) {
        if (unitHashId == null || unitHashId.isBlank()) {
            update.setUnitId(null);
            return;
        }
        DevelopmentUnit unit = units.findById(HashIdUtil.decodeId(unitHashId))
                .orElseThrow(() -> new ResourceNotFoundException("Unit", unitHashId));
        if (!development.getId().equals(unit.getDevelopmentId())) {
            throw new ResourceNotFoundException("Unit", unitHashId);
        }
        update.setUnitId(unit.getId());
    }

    private UpdateResponse toResponse(ProgressUpdate u) {
        return new UpdateResponse(
                HashIdUtil.encodeId(u.getId()), u.getTitle(), u.getBody(), u.getPercentComplete(),
                u.getMilestone(), u.getReportedOn(), storage.urlFor(u.getImageKey()), u.getAudience(),
                u.isPublished(), u.getPublishedAt(), u.getCreatedAt(), u.getCreatedBy());
    }

    private PublicPost toPublicPost(ProgressUpdate u) {
        List<String> images = media
                .findPublicForOwner(AppConstant.MEDIA_OWNER_PROGRESS_UPDATE, u.getId()).stream()
                .map(com.hodi.modules.media.MediaAsset::getStorageKey)
                .map(storage::urlFor)
                .filter(java.util.Objects::nonNull)
                .toList();
        return new PublicPost(u.getTitle(), u.getBody(), u.getReportedOn(),
                storage.urlFor(u.getImageKey()), images);
    }

    // ── request ───────────────────────────────────────────────────────────────

    /**
     * A development update: the fields a listing update has, plus where in the build it happened.
     *
     * <p>Composed rather than a second flat record, so a change to the shared fields cannot be made in one
     * place and forgotten in the other.
     */
    public record SaveDevelopmentUpdateRequest(
            @jakarta.validation.Valid @jakarta.validation.constraints.NotNull SaveUpdateRequest update,
            String milestoneCode,
            String phaseId,
            String unitId,
            /**
             * PUBLIC for a blog or newsletter post, STAKEHOLDERS for a detailed update.
             *
             * <p>Defaults to STAKEHOLDERS when unset, which is the opposite of the column's own default and
             * deliberately so. The column defaults to PUBLIC to leave existing listing timelines alone; a
             * development post arriving with no audience is a caller who has not said, and the safe reading of
             * silence is "not for strangers".
             */
            String audience) {}
}
