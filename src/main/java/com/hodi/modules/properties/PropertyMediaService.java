package com.hodi.modules.properties;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.infra.storage.StorageService;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.media.MediaAssetService;
import com.hodi.modules.properties.PropertyDtos.MediaResponse;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * A listing's photographs.
 *
 * <p>Separate from {@link PropertyService} because the lifecycles differ: a listing has states and approvals,
 * a photograph is added or removed. Keeping them apart also keeps the single writer of
 * {@code properties.primary_image_key} in one place — the label cache that lets a marketplace card render a
 * photograph without a second query per row.
 *
 * <h2>A listing made from a development does not own its pictures</h2>
 *
 * <p>This is the fix for "a unit type and a listing cannot share images — they should, and editing either
 * should update both". It was worse than it looked: the publish gate counted the unit type's photographs
 * when deciding whether a typology card could go live, and the public read path for that same card read
 * {@code property_media}, which nothing ever wrote for it. A card therefore passed the "at least one
 * photograph" check on borrowed pictures and then rendered empty.
 *
 * <p>Copying at generation time would have satisfied "don't upload twice" once and drifted the moment
 * either side was edited. So the card and the units <em>are</em> the typology's gallery: every call here
 * for a property carrying a {@code unitTypeId} is served from {@code media_assets} under {@code UNIT_TYPE}.
 * One store, two doors — an upload from the unit-type editor and an upload from the listing form land in
 * the same rows, and a deletion from either removes the same row.
 *
 * <p>A plain house is unaffected: it owns its photographs, in {@code property_media}, as it always did.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropertyMediaService {

    /**
     * Enough for a house; short of a bill nobody expected.
     *
     * <p>A product judgement rather than a database constraint, because the number will change and "somebody
     * uploaded four hundred" is a storage bill and a gallery nobody can scroll.
     */
    private static final int MAX_PHOTOS = 12;

    private final PropertyRepository properties;
    private final PropertyMediaRepository repository;
    private final MediaAssetService assets;
    private final StorageService storage;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<MediaResponse> list(String propertyHashId) {
        Property property = requireOwn(propertyHashId);
        if (sharesTypologyMedia(property)) return sharedList(property);
        return repository.findForProperty(property.getId()).stream().map(this::toResponse).toList();
    }

    @Transactional
    public MediaResponse add(String propertyHashId, MultipartFile file, String mediaKind, String caption) {
        Property property = requireOwn(propertyHashId);
        if (sharesTypologyMedia(property)) {
            /*
             * Straight into the typology's gallery, and then the card's cover cache is refreshed from it —
             * otherwise the listing would hold pictures it could not show on a card, which is the bug this
             * whole arrangement exists to close.
             */
            var added = assets.add(AppConstant.MEDIA_OWNER_UNIT_TYPE, property.getUnitTypeId(),
                    property.getTenantId(), null, file, mediaKind, caption, true);
            refreshSharedCover(property);
            return fromShared(added);
        }
        long held = repository.countForProperty(property.getId());
        if (held >= MAX_PHOTOS) {
            throw new HodiException(
                    "That is the most photographs a listing can carry (%d). Remove one first."
                            .formatted(MAX_PHOTOS),
                    HttpStatus.CONFLICT);
        }

        var stored = storage.store(file, "properties");
        /*
         * The cover is the first *photograph*, not the first file.
         *
         * Before kinds existed every row was a photograph and "the first one" was unambiguous. Now a seller
         * who uploads the floor plan first would have had a floor plan on the card, which is a worse first
         * impression than the blank one this rule was written to avoid.
         */
        boolean first = AppConstant.MEDIA_KIND_PHOTO.equals(normaliseKind(mediaKind))
                && repository.countOfKind(property.getId(), AppConstant.MEDIA_KIND_PHOTO) == 0;

        PropertyMedia row = repository.save(PropertyMedia.builder()
                .propertyId(property.getId())
                .tenantId(property.getTenantId())
                .storageKey(stored.key())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .caption(caption == null || caption.isBlank() ? null : caption.trim())
                .mediaKind(normaliseKind(mediaKind))
                // The first photograph is the card's, without anybody choosing: a listing whose card stays
                // blank until somebody finds "make primary" is a listing that ships blank.
                .primary(first)
                .sortOrder((int) held)
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy(AuthContext.username())
                .build());

        if (first) {
            property.setPrimaryImageKey(stored.key());
            properties.save(property);
        }
        audit.record(AppConstant.ACTION_CREATE, "PropertyMedia", row.getId(), null,
                property.getReference() + " gained a photograph");
        return toResponse(row);
    }

    @Transactional
    public void makePrimary(String propertyHashId, String mediaHashId) {
        Property property = requireOwn(propertyHashId);
        if (sharesTypologyMedia(property)) {
            assets.makePrimary(AppConstant.MEDIA_OWNER_UNIT_TYPE, property.getUnitTypeId(), mediaHashId);
            refreshSharedCover(property);
            return;
        }
        PropertyMedia row = requireOf(property, mediaHashId);

        // Cleared first: the partial unique index refuses two, and refusing is right — the card reads
        // whichever row claims to be primary, and two would make its contents a matter of query order.
        repository.clearPrimary(property.getId());
        row.setPrimary(true);
        row.setUpdatedBy(AuthContext.username());
        repository.save(row);

        property.setPrimaryImageKey(row.getStorageKey());
        properties.save(property);
        audit.record(AppConstant.ACTION_UPDATE, "PropertyMedia", row.getId(), null,
                property.getReference() + ": primary photograph changed");
    }

    @Transactional
    public void remove(String propertyHashId, String mediaHashId) {
        Property property = requireOwn(propertyHashId);
        if (sharesTypologyMedia(property)) {
            assets.remove(AppConstant.MEDIA_OWNER_UNIT_TYPE, property.getUnitTypeId(), mediaHashId);
            refreshSharedCover(property);
            return;
        }
        PropertyMedia row = requireOf(property, mediaHashId);

        row.setStatus(AppConstant.STATUS_DELETED);
        row.setStatusFlag(AppConstant.FLAG_DELETED);
        row.setPrimary(false);
        row.setUpdatedBy(AuthContext.username());
        repository.save(row);

        /*
         * The card cannot be left pointing at a photograph that is gone.
         *
         * The next one is promoted rather than the key cleared: a listing that loses its cover because
         * somebody deleted one of twelve photographs looks broken, for a reason nobody would connect to what
         * they just did.
         */
        if (property.getPrimaryImageKey() != null
                && property.getPrimaryImageKey().equals(row.getStorageKey())) {
            var next = repository.findForProperty(property.getId()).stream().findFirst().orElse(null);
            if (next != null) {
                next.setPrimary(true);
                repository.save(next);
            }
            property.setPrimaryImageKey(next == null ? null : next.getStorageKey());
            properties.save(property);
        }
        audit.record(AppConstant.ACTION_DELETE, "PropertyMedia", row.getId(), null,
                property.getReference() + " lost a photograph");
    }

    // ── the shared gallery ────────────────────────────────────────────────────

    /**
     * Whether this listing reads its typology's pictures rather than owning any.
     *
     * <p>True for a typology card and for every generated unit — everything with a {@code unitTypeId}. A
     * house has none and is untouched.
     */
    static boolean sharesTypologyMedia(Property property) {
        return property.getUnitTypeId() != null;
    }

    private List<MediaResponse> sharedList(Property property) {
        return assets.list(AppConstant.MEDIA_OWNER_UNIT_TYPE, property.getUnitTypeId()).stream()
                .map(PropertyMediaService::fromShared)
                .toList();
    }

    /**
     * The typology's shape, in the listing's.
     *
     * <p>Two records rather than one, because {@code media_assets} carries an owner type and a visibility
     * flag that mean nothing on a listing. The listing's callers should not have to know which store their
     * pictures came out of, which is the whole point of the delegation.
     */
    private static MediaResponse fromShared(com.hodi.modules.media.MediaDtos.MediaResponse m) {
        return new MediaResponse(m.id(), m.url(), m.caption(), m.sortOrder(), m.primary(),
                m.mediaKind(), m.contentType(), m.sizeBytes());
    }

    /**
     * Point the card at whatever the typology's cover now is.
     *
     * <p>Every listing sharing that typology — the card and each of its units — is repointed, not only the
     * one being edited, because they all show the same picture and one of them silently keeping a deleted
     * key is exactly the kind of drift sharing was chosen to avoid.
     */
    private void refreshSharedCover(Property property) {
        String cover = assets.coverKeyFor(AppConstant.MEDIA_OWNER_UNIT_TYPE, property.getUnitTypeId());
        properties.repointCover(property.getUnitTypeId(), cover);
    }

    /** Unknown or absent means a photograph, which is what every row held before kinds existed. */
    private static String normaliseKind(String kind) {
        if (kind == null || kind.isBlank()) return AppConstant.MEDIA_KIND_PHOTO;
        String value = kind.trim().toUpperCase();
        return AppConstant.MEDIA_KINDS.contains(value) ? value : AppConstant.MEDIA_KIND_PHOTO;
    }

    // ── guards ────────────────────────────────────────────────────────────────

    private Property requireOwn(String hashId) {
        Property property = properties.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Listing", hashId));
        Long tenantId = AuthContext.tenantId();
        if (tenantId == null || !tenantId.equals(property.getTenantId())) {
            throw new HodiException("That listing belongs to another organisation.",
                    HttpStatus.FORBIDDEN);
        }
        return property;
    }

    /** The photograph, and it has to be this listing's — an id from another one is not found here. */
    private PropertyMedia requireOf(Property property, String mediaHashId) {
        PropertyMedia row = repository.findById(HashIdUtil.decodeId(mediaHashId))
                .orElseThrow(() -> new ResourceNotFoundException("Photograph", mediaHashId));
        if (!row.getPropertyId().equals(property.getId())) {
            throw new ResourceNotFoundException("Photograph", mediaHashId);
        }
        return row;
    }

    private MediaResponse toResponse(PropertyMedia m) {
        return new MediaResponse(
                HashIdUtil.encodeId(m.getId()),
                storage.urlFor(m.getStorageKey()),
                m.getCaption(),
                m.getSortOrder(),
                m.isPrimary(),
                m.getMediaKind(),
                m.getContentType(),
                m.getSizeBytes());
    }
}
