package com.hodi.modules.properties;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.infra.storage.StorageService;
import com.hodi.modules.audit.AuditService;
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
    private final StorageService storage;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<MediaResponse> list(String propertyHashId) {
        Property property = requireOwn(propertyHashId);
        return repository.findForProperty(property.getId()).stream().map(this::toResponse).toList();
    }

    @Transactional
    public MediaResponse add(String propertyHashId, MultipartFile file, String caption) {
        Property property = requireOwn(propertyHashId);
        long held = repository.countForProperty(property.getId());
        if (held >= MAX_PHOTOS) {
            throw new HodiException(
                    "That is the most photographs a listing can carry (%d). Remove one first."
                            .formatted(MAX_PHOTOS),
                    HttpStatus.CONFLICT);
        }

        var stored = storage.store(file, "properties");
        boolean first = held == 0;

        PropertyMedia row = repository.save(PropertyMedia.builder()
                .propertyId(property.getId())
                .tenantId(property.getTenantId())
                .storageKey(stored.key())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .caption(caption == null || caption.isBlank() ? null : caption.trim())
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
                m.getContentType(),
                m.getSizeBytes());
    }
}
