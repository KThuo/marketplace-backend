package com.hodi.modules.media;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.enums.ConfigKey;
import com.hodi.infra.storage.StorageService;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.media.MediaDtos.MediaResponse;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

/**
 * Photographs, floor plans and brochures for things that are not listings.
 *
 * <p>The twin of {@code PropertyMediaService}, and deliberately not a merge of it. That class owns a listing's
 * gallery, a twelve-photograph cap chosen for a house, and the cover cache on {@code properties}. A
 * development legitimately has two hundred photographs across four blocks and ten phases, so the two have
 * different limits, different owners and different callers — one class each is cheaper to read than one class
 * with a branch in every method.
 *
 * <h2>Where the caller's authority comes from</h2>
 *
 * <p>Nowhere in here. This service takes an owner type and an id that the calling module has already resolved
 * and authorised — {@code DevelopmentController} checks the development, then hands the phase id down. A media
 * service that decided access for five owner types would have to know about five modules, and would be the
 * place a sixth one forgets to check.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaAssetService {

    /**
     * Per-owner limits, and why they differ.
     *
     * <p>A development is a site with blocks, phases and show units: two hundred is a real album. A typology is
     * one floor plan and a handful of interiors. A single progress post is what somebody photographed that
     * morning. The numbers are a product judgement rather than a constraint, so they live here where they can
     * be read together rather than as a constant in five places.
     */
    private static final int MAX_DEVELOPMENT = 200;
    private static final int MAX_PHASE = 60;
    private static final int MAX_UNIT_TYPE = 30;
    private static final int MAX_UNIT = 20;
    private static final int MAX_PROGRESS_UPDATE = 12;

    /** Everything this service will accept an owner type for. A type absent here cannot hold media. */
    private static final Set<String> OWNER_TYPES = Set.of(
            AppConstant.MEDIA_OWNER_DEVELOPMENT,
            AppConstant.MEDIA_OWNER_DEVELOPMENT_PHASE,
            AppConstant.MEDIA_OWNER_UNIT_TYPE,
            AppConstant.MEDIA_OWNER_DEVELOPMENT_UNIT,
            AppConstant.MEDIA_OWNER_PROGRESS_UPDATE);

    private static final Set<String> MEDIA_KINDS = Set.of(
            AppConstant.MEDIA_KIND_PHOTO,
            AppConstant.MEDIA_KIND_FLOOR_PLAN,
            AppConstant.MEDIA_KIND_SITE_PLAN,
            AppConstant.MEDIA_KIND_BROCHURE,
            AppConstant.MEDIA_KIND_DRONE);

    private final MediaAssetRepository repository;
    private final StorageService storage;
    private final ConfigurationService configs;
    private final AuditService audit;

    /** Everything an owner holds, cover first. */
    @Transactional(readOnly = true)
    public List<MediaResponse> list(String ownerType, Long ownerId) {
        return repository.findForOwner(normaliseOwnerType(ownerType), ownerId).stream()
                .map(this::toResponse).toList();
    }

    /** What a buyer may see. Scoped in the query, not filtered after loading. */
    @Transactional(readOnly = true)
    public List<MediaResponse> listPublic(String ownerType, Long ownerId) {
        return repository.findPublicForOwner(normaliseOwnerType(ownerType), ownerId).stream()
                .map(this::toResponse).toList();
    }

    /**
     * Stores a file against an owner.
     *
     * <p>The first one becomes the cover without anybody choosing it, for the reason a listing's first
     * photograph does: an album whose cover stays blank until somebody finds "make primary" ships blank.
     *
     * @param tenantId      the owning organisation, or null when an institution owns it
     * @param institutionId the owning lender, or null when a tenant owns it
     */
    @Transactional
    public MediaResponse add(String ownerType, Long ownerId, Long tenantId, Long institutionId,
                             MultipartFile file, String mediaKind, String caption,
                             boolean publicVisible) {
        String owner = normaliseOwnerType(ownerType);
        String kind = normaliseKind(mediaKind);

        long held = repository.countForOwner(owner, ownerId);
        int cap = capFor(owner);
        if (held >= cap) {
            throw new HodiException(
                    "That is the most files this can carry (%d). Remove one first.".formatted(cap),
                    HttpStatus.CONFLICT);
        }

        /*
         * A folder per owner type, so the object store is browsable by somebody answering a support question.
         *
         * storeFor rather than store: the owning organisation is an argument here, and store() would read it
         * from the request's TenantContext — which is empty for platform staff by design. An administrator
         * adding a photograph on a seller's behalf would have been refused, which is what a test found.
         * An institution-owned development has no tenant prefix to live under, so its files are shared.
         */
        var stored = tenantId == null
                ? storage.storeShared(file, folderFor(owner))
                : storage.storeFor(file, folderFor(owner), tenantId);

        MediaAsset row = repository.save(MediaAsset.builder()
                .ownerType(owner)
                .ownerId(ownerId)
                .tenantId(tenantId)
                .institutionId(institutionId)
                .mediaKind(kind)
                .storageKey(stored.key())
                .contentType(stored.contentType())
                .sizeBytes(stored.sizeBytes())
                .caption(caption == null || caption.isBlank() ? null : caption.trim())
                .sortOrder((int) held)
                .primary(held == 0)
                .publicVisible(publicVisible)
                .createdBy(AuthContext.username())
                .build());

        audit.record(AppConstant.ACTION_CREATE, "MediaAsset", row.getId(), null,
                kind + " added to " + owner + " " + ownerId);
        return toResponse(row);
    }

    /**
     * Makes one file the cover.
     *
     * <p>Clears the flag across the owner first, because the partial unique index permits exactly one — the
     * index is what makes "one cover" true, so the service has to make room rather than hope.
     */
    @Transactional
    public MediaResponse makePrimary(String ownerType, Long ownerId, String mediaHashId) {
        String owner = normaliseOwnerType(ownerType);
        MediaAsset row = requireOf(owner, ownerId, mediaHashId);

        repository.clearPrimary(owner, ownerId);
        row.setPrimary(true);
        row.setUpdatedBy(AuthContext.username());
        MediaAsset saved = repository.save(row);

        audit.record(AppConstant.ACTION_UPDATE, "MediaAsset", saved.getId(), null,
                "made the cover of " + owner + " " + ownerId);
        return toResponse(saved);
    }

    /**
     * Archives a file and, when it was the cover, promotes the next one.
     *
     * <p>Promoted rather than left blank: an album that loses its cover because somebody deleted one of thirty
     * photographs looks broken, for a reason nobody would connect to what they just did. The object itself is
     * left in the store — {@code StorageService.delete} is best-effort and a row that is gone from every query
     * is already gone from the product, while an orphaned object is a storage bill rather than a bug.
     */
    @Transactional
    public void remove(String ownerType, Long ownerId, String mediaHashId) {
        String owner = normaliseOwnerType(ownerType);
        MediaAsset row = requireOf(owner, ownerId, mediaHashId);
        boolean wasCover = row.isPrimary();

        row.setStatus(AppConstant.STATUS_DELETED);
        row.setStatusFlag(AppConstant.FLAG_DELETED);
        row.setPrimary(false);
        row.setUpdatedBy(AuthContext.username());
        repository.save(row);

        if (wasCover) {
            repository.findForOwner(owner, ownerId).stream().findFirst().ifPresent(next -> {
                next.setPrimary(true);
                next.setUpdatedBy(AuthContext.username());
                repository.save(next);
            });
        }

        audit.record(AppConstant.ACTION_DELETE, "MediaAsset", row.getId(), null,
                "removed from " + owner + " " + ownerId);
    }

    /** The cover's storage key, for a parent that caches one. Null when there are no files. */
    @Transactional(readOnly = true)
    public String coverKeyFor(String ownerType, Long ownerId) {
        return repository.findPrimary(normaliseOwnerType(ownerType), ownerId)
                .map(MediaAsset::getStorageKey)
                .orElseGet(() -> repository.findForOwner(normaliseOwnerType(ownerType), ownerId).stream()
                        .findFirst().map(MediaAsset::getStorageKey).orElse(null));
    }

    @Transactional(readOnly = true)
    public long count(String ownerType, Long ownerId) {
        return repository.countForOwner(normaliseOwnerType(ownerType), ownerId);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Refuses an owner type this service does not know.
     *
     * <p>The database has the same CHECK. Both, deliberately: the constraint stops a bad row existing and this
     * gives the caller a sentence rather than a constraint-violation stack trace.
     */
    private String normaliseOwnerType(String ownerType) {
        String owner = ownerType == null ? "" : ownerType.trim().toUpperCase();
        if (!OWNER_TYPES.contains(owner)) {
            throw new HodiException("Files cannot be attached to a " + ownerType + ".",
                    HttpStatus.BAD_REQUEST);
        }
        return owner;
    }

    private String normaliseKind(String mediaKind) {
        if (mediaKind == null || mediaKind.isBlank()) return AppConstant.MEDIA_KIND_PHOTO;
        String kind = mediaKind.trim().toUpperCase();
        if (!MEDIA_KINDS.contains(kind)) {
            throw new HodiException("Unknown kind of file: " + mediaKind, HttpStatus.BAD_REQUEST);
        }
        return kind;
    }

    /*
     * There is no size check here any more, and that is the change rather than an omission.
     *
     * This module used to refuse anything over a configured ceiling with "compress it and try again", which
     * asked somebody holding a twelve-megabyte phone photograph to go and find a tool. StorageService now
     * resizes and re-encodes on the way in, so the storage bill this check was protecting is handled by making
     * the file smaller instead of by turning the person away.
     *
     * What remains is a ceiling in StorageService far above any real photograph, and it exists to stop a
     * decode large enough to exhaust the heap — not to enforce a policy.
     *
     * The per-owner count caps are untouched: two hundred photographs on a development is a different question
     * from how big each one is, and it is still worth answering.
     */

    private int capFor(String ownerType) {
        return switch (ownerType) {
            case AppConstant.MEDIA_OWNER_DEVELOPMENT -> MAX_DEVELOPMENT;
            case AppConstant.MEDIA_OWNER_DEVELOPMENT_PHASE -> MAX_PHASE;
            case AppConstant.MEDIA_OWNER_UNIT_TYPE -> MAX_UNIT_TYPE;
            case AppConstant.MEDIA_OWNER_DEVELOPMENT_UNIT -> MAX_UNIT;
            case AppConstant.MEDIA_OWNER_PROGRESS_UPDATE -> MAX_PROGRESS_UPDATE;
            default -> MAX_UNIT;
        };
    }

    private String folderFor(String ownerType) {
        return switch (ownerType) {
            case AppConstant.MEDIA_OWNER_DEVELOPMENT -> "developments";
            case AppConstant.MEDIA_OWNER_DEVELOPMENT_PHASE -> "phases";
            case AppConstant.MEDIA_OWNER_UNIT_TYPE -> "unit-types";
            case AppConstant.MEDIA_OWNER_DEVELOPMENT_UNIT -> "units";
            case AppConstant.MEDIA_OWNER_PROGRESS_UPDATE -> "progress";
            default -> "media";
        };
    }

    /** A file, checked to belong to the owner in the path rather than merely to exist. */
    private MediaAsset requireOf(String ownerType, Long ownerId, String mediaHashId) {
        MediaAsset row = repository.findById(HashIdUtil.decodeId(mediaHashId))
                .orElseThrow(() -> new ResourceNotFoundException("File", mediaHashId));
        if (!row.getOwnerType().equals(ownerType) || !row.getOwnerId().equals(ownerId)) {
            // Not-found rather than forbidden: the caller has no business learning it belongs elsewhere.
            throw new ResourceNotFoundException("File", mediaHashId);
        }
        return row;
    }

    private MediaResponse toResponse(MediaAsset m) {
        return new MediaResponse(
                HashIdUtil.encodeId(m.getId()),
                m.getOwnerType(),
                m.getMediaKind(),
                storage.urlFor(m.getStorageKey()),
                m.getContentType(),
                m.getSizeBytes(),
                m.getCaption(),
                m.getSortOrder(),
                m.isPrimary(),
                m.isPublicVisible(),
                m.getCreatedAt());
    }
}
