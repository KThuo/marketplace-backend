package com.hodi.modules.properties;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.infra.storage.StorageService;
import com.hodi.security.TenantScope;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Progress updates on a listing (M8, BRD FR087–FR089).
 *
 * <h2>Drafted, then shown</h2>
 *
 * <p>A developer writing three updates on a Friday should not publish the first two half-finished, so an
 * update is a draft until somebody says otherwise. Publishing is a separate call and its own moment, the
 * same shape as a mortgage product going on offer.
 *
 * <h2>Two readers</h2>
 *
 * <p>The seller sees everything through {@link #forSeller}, scoped by {@code TenantScope}. A buyer sees
 * {@link #published} — a different query, not the same one filtered afterwards, so there is no path that
 * could return a draft by forgetting a condition.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProgressUpdateService {

    private final ProgressUpdateRepository repository;
    private final PropertyRepository properties;
    private final StorageService storage;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record UpdateResponse(
            String id,
            String title,
            String body,
            Short percentComplete,
            String milestone,
            LocalDate reportedOn,
            String imageUrl,
            boolean published,
            OffsetDateTime publishedAt,
            OffsetDateTime createdAt,
            String createdBy) {}

    /** What a buyer sees. No draft state and no author — this is a bulletin, not a record. */
    public record PublicUpdate(
            String title,
            String body,
            Short percentComplete,
            String milestone,
            LocalDate reportedOn,
            String imageUrl) {}

    public record SaveUpdateRequest(
            @NotBlank(message = "Give the update a title")
            @Size(max = 180, message = "That title is too long") String title,
            String body,
            Short percentComplete,
            @Size(max = 64) String milestone,
            LocalDate reportedOn) {}

    // ── the seller's side ─────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<UpdateResponse> forSeller(String propertyHashId) {
        Property property = ownProperty(propertyHashId);
        return repository.findForProperty(property.getId()).stream().map(this::toResponse).toList();
    }

    @Transactional
    public UpdateResponse create(String propertyHashId, SaveUpdateRequest request) {
        Property property = ownProperty(propertyHashId);
        ProgressUpdate update = ProgressUpdate.builder()
                // The subject, and only one of the two may be set — ck_progress_subject.
                .propertyId(property.getId())
                .tenantId(property.getTenantId())
                .createdBy(AuthContext.username())
                .updatedBy(AuthContext.username())
                .build();
        apply(update, request);
        return toResponse(repository.save(update));
    }

    @Transactional
    public UpdateResponse update(String propertyHashId, String updateHashId, SaveUpdateRequest request) {
        Property property = ownProperty(propertyHashId);
        ProgressUpdate update = load(updateHashId, property);
        apply(update, request);
        update.setUpdatedBy(AuthContext.username());
        return toResponse(repository.save(update));
    }

    /**
     * Puts an update in front of buyers, or takes it back off.
     *
     * <p>{@code publishedAt} is stamped on the way up and left alone on the way down: the CHECK only needs
     * it while published, and keeping it answers "when did this go out" without a second column — the same
     * arrangement mortgage products use.
     */
    @Transactional
    public UpdateResponse setPublished(String propertyHashId, String updateHashId, boolean publish) {
        Property property = ownProperty(propertyHashId);
        ProgressUpdate update = load(updateHashId, property);

        update.setPublished(publish);
        if (publish && update.getPublishedAt() == null) update.setPublishedAt(OffsetDateTime.now());
        update.setUpdatedBy(AuthContext.username());
        return toResponse(repository.save(update));
    }

    @Transactional
    public UpdateResponse attachPhoto(String propertyHashId, String updateHashId, MultipartFile file) {
        Property property = ownProperty(propertyHashId);
        ProgressUpdate update = load(updateHashId, property);

        StorageService.Stored stored = storage.store(file, "progress");
        // The previous photograph is left in the store. An update that has been out to buyers with one
        // picture and now has another is a change worth being able to see, and deleting the old object would
        // make the old post unrenderable in anything that cached it.
        update.setImageKey(stored.key());
        update.setUpdatedBy(AuthContext.username());
        return toResponse(repository.save(update));
    }

    @Transactional
    public void archive(String propertyHashId, String updateHashId) {
        Property property = ownProperty(propertyHashId);
        ProgressUpdate update = load(updateHashId, property);
        update.setStatus(AppConstant.STATUS_DELETED);
        update.setStatusFlag(AppConstant.FLAG_DELETED);
        update.setPublished(false);
        update.setUpdatedBy(AuthContext.username());
        repository.save(update);
    }

    // ── the buyer's side ──────────────────────────────────────────────────────

    /**
     * The published timeline for a live listing.
     *
     * <p>Its own query rather than {@link #forSeller} filtered, so no code path here can return a draft by
     * forgetting a condition — the same construction the public marketplace uses.
     *
     * <p>Named for what it resolves. It used to be {@code published(reference)}, and once developments had
     * references too, that name invited a development's reference to be looked up against listings — where it
     * would not be found, and the caller would be told the project does not exist. A development's timeline is
     * {@code DevelopmentProgressService.published}; there is no one method taking either.
     */
    @Transactional(readOnly = true)
    public List<PublicUpdate> publishedForListing(String propertyReference) {
        Property property = properties.findLiveByReference(
                        propertyReference == null ? "" : propertyReference.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Listing", propertyReference));

        return repository.findPublishedForProperty(property.getId()).stream()
                .map(u -> new PublicUpdate(u.getTitle(), u.getBody(), u.getPercentComplete(),
                        u.getMilestone(), u.getReportedOn(), storage.urlFor(u.getImageKey())))
                .toList();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** The listing, if it belongs to an organisation this caller may act for. */
    private Property ownProperty(String hashId) {
        Property property = properties.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Listing", hashId));
        TenantScope.assertAllowed(property.getTenantId());
        return property;
    }

    /**
     * The update, if it belongs to that listing.
     *
     * <p>Checked rather than assumed: without it, an id from another seller's listing would be loaded and
     * edited after the property's own scope check had already passed.
     */
    private ProgressUpdate load(String hashId, Property property) {
        ProgressUpdate update = repository.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Update", hashId));
        /*
         * The property's id leads the comparison, and that ordering is the fix rather than a style choice.
         *
         * `update.getPropertyId().equals(...)` threw a NullPointerException the moment property_id became
         * nullable — which is to say for every development-scoped update, on a path reached by an id a caller
         * supplies. Reversed, a development update simply fails the check and is reported as not found on
         * this listing, which is exactly what it is.
         */
        if (!property.getId().equals(update.getPropertyId())) {
            throw new ResourceNotFoundException("Update", hashId);
        }
        return update;
    }

    private void apply(ProgressUpdate update, SaveUpdateRequest request) {
        update.setTitle(request.title().trim());
        update.setBody(request.body() == null || request.body().isBlank() ? null : request.body().trim());
        update.setMilestone(request.milestone() == null || request.milestone().isBlank()
                ? null : request.milestone().trim());
        if (request.reportedOn() != null) update.setReportedOn(request.reportedOn());

        Short percent = request.percentComplete();
        if (percent != null && (percent < 0 || percent > 100)) {
            throw new HodiException("Progress is a figure between 0 and 100.", HttpStatus.BAD_REQUEST);
        }
        update.setPercentComplete(percent);
    }

    private UpdateResponse toResponse(ProgressUpdate u) {
        return new UpdateResponse(
                HashIdUtil.encodeId(u.getId()), u.getTitle(), u.getBody(), u.getPercentComplete(),
                u.getMilestone(), u.getReportedOn(), storage.urlFor(u.getImageKey()), u.isPublished(),
                u.getPublishedAt(), u.getCreatedAt(), u.getCreatedBy());
    }
}
