package com.hodi.modules.developments;

import com.hodi.modules.properties.Property;
import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.modules.media.MediaAssetService;
import com.hodi.modules.media.MediaDtos.MediaResponse;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Photographs for a development and the things under it.
 *
 * <p>A thin layer over {@link MediaAssetService}, and the reason it exists is authorisation. That service takes
 * an owner type and an id it trusts, deliberately, so it does not have to know about five modules. This is
 * where the trust is earned: the development is resolved and checked, the child is confirmed to belong to it,
 * and only then is the owner handed down.
 *
 * <p>Which also puts the cover caches in one place. A development, a typology and a progress update each keep
 * their cover's key on their own row so a card renders without a second query, and this is the only writer of
 * all three.
 */
@Service
@RequiredArgsConstructor
public class DevelopmentMediaService {

    /** The things under a development that may hold files. DEVELOPMENT itself is handled before this. */
    private static final java.util.Set<String> CHILD_OWNER_TYPES = java.util.Set.of(
            AppConstant.MEDIA_OWNER_DEVELOPMENT_PHASE,
            AppConstant.MEDIA_OWNER_UNIT_TYPE,
            AppConstant.MEDIA_OWNER_DEVELOPMENT_UNIT,
            AppConstant.MEDIA_OWNER_PROGRESS_UPDATE);

    private final MediaAssetService media;
    private final DevelopmentRepository developments;
    private final DevelopmentPhaseRepository phases;
    private final DevelopmentUnitTypeRepository unitTypes;
    private final DevelopmentUnitRepository units;
    private final DevelopmentVisibility visibility;

    @Transactional(readOnly = true)
    public List<MediaResponse> list(String developmentHashId, String ownerType, String childHashId) {
        Development development = requireVisible(developmentHashId);
        return media.list(ownerType, ownerIdFor(development, ownerType, childHashId));
    }

    @Transactional
    public MediaResponse add(String developmentHashId, String ownerType, String childHashId,
                             MultipartFile file, String mediaKind, String caption,
                             Boolean publicVisible) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        // Photographs of a project are progress, so a granted collaborator may add them. Renaming the project
        // is a different act and assertMayManage is what guards that.
        visibility.assertMayWriteProgress(development, caller);

        Long ownerId = ownerIdFor(development, ownerType, childHashId);
        MediaResponse stored = media.add(ownerType, ownerId,
                development.getTenantId(), development.getInstitutionId(),
                file, mediaKind, caption, publicVisible == null || publicVisible);

        refreshCover(development, ownerType, ownerId);
        return stored;
    }

    @Transactional
    public MediaResponse makePrimary(String developmentHashId, String ownerType, String childHashId,
                                     String mediaHashId) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteProgress(development, caller);

        Long ownerId = ownerIdFor(development, ownerType, childHashId);
        MediaResponse updated = media.makePrimary(ownerType, ownerId, mediaHashId);
        refreshCover(development, ownerType, ownerId);
        return updated;
    }

    @Transactional
    public void remove(String developmentHashId, String ownerType, String childHashId,
                       String mediaHashId) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteProgress(development, caller);

        Long ownerId = ownerIdFor(development, ownerType, childHashId);
        media.remove(ownerType, ownerId, mediaHashId);
        refreshCover(development, ownerType, ownerId);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Re-stamps the owner's cover key from what the album now holds.
     *
     * <p>Called after every change rather than only when the cover moved: the cheap correct thing. A key
     * pointing at a deleted object renders as a broken image on a card, and working out whether this
     * particular change could have caused that is more code than reading the answer.
     */
    private void refreshCover(Development development, String ownerType, Long ownerId) {
        String key = media.coverKeyFor(ownerType, ownerId);
        switch (ownerType.trim().toUpperCase()) {
            case AppConstant.MEDIA_OWNER_DEVELOPMENT -> {
                development.setPrimaryImageKey(key);
                developments.save(development);
            }
            case AppConstant.MEDIA_OWNER_UNIT_TYPE -> unitTypes.findById(ownerId).ifPresent(type -> {
                type.setPrimaryImageKey(key);
                unitTypes.save(type);
            });
            default -> {
                // Phases, units and progress posts carry no cover cache of their own: nothing renders a card
                // for them, so a key on the row would be a column nothing reads.
            }
        }
    }

    /**
     * The id the owner type refers to, confirmed to belong to this development.
     *
     * <p>The composite foreign keys in the schema stop a *unit* pointing at another development's phase; they
     * say nothing about a request naming one. This is where that is checked, and it is why every media path
     * goes through the development in its URL.
     */
    private Long ownerIdFor(Development development, String ownerType, String childHashId) {
        String owner = ownerType == null ? "" : ownerType.trim().toUpperCase();
        if (AppConstant.MEDIA_OWNER_DEVELOPMENT.equals(owner)) return development.getId();

        /*
         * The owner type is checked before the child id, and the order is the whole point.
         *
         * Reversed, an unknown type with no child id came back as "PROPERTY not found: null" — a missing-child
         * error for a request whose actual mistake was the type. The caller is told which of the two they got
         * wrong.
         */
        if (!CHILD_OWNER_TYPES.contains(owner)) {
            throw new HodiException("Files cannot be attached to a " + ownerType + ".",
                    HttpStatus.BAD_REQUEST);
        }
        if (childHashId == null || childHashId.isBlank()) {
            throw new ResourceNotFoundException(owner, childHashId);
        }
        Long id = HashIdUtil.decodeId(childHashId);

        return switch (owner) {
            case AppConstant.MEDIA_OWNER_DEVELOPMENT_PHASE -> phases.findById(id)
                    .filter(p -> p.getDevelopmentId().equals(development.getId()))
                    .map(DevelopmentPhase::getId)
                    .orElseThrow(() -> new ResourceNotFoundException("Phase", childHashId));
            case AppConstant.MEDIA_OWNER_UNIT_TYPE -> unitTypes.findById(id)
                    .filter(t -> t.getDevelopmentId().equals(development.getId()))
                    .map(DevelopmentUnitType::getId)
                    .orElseThrow(() -> new ResourceNotFoundException("Unit type", childHashId));
            case AppConstant.MEDIA_OWNER_DEVELOPMENT_UNIT -> units.findById(id)
                    .filter(u -> u.getDevelopmentId().equals(development.getId()))
                    .map(Property::getId)
                    .orElseThrow(() -> new ResourceNotFoundException("Unit", childHashId));
            // Unreachable: the set above has already refused anything not listed here. Kept because the
            // switch must be exhaustive, and a default that throws is better than one that returns null.
            default -> throw new HodiException("Files cannot be attached to a " + ownerType + ".",
                    HttpStatus.BAD_REQUEST);
        };
    }

    private Development requireVisible(String developmentHashId) {
        Development development = developments.findById(HashIdUtil.decodeId(developmentHashId))
                .orElseThrow(() -> new ResourceNotFoundException("Development", developmentHashId));
        if (!visibility.mayRead(development, AuthContext.require())) {
            throw new ResourceNotFoundException("Development", developmentHashId);
        }
        return development;
    }
}
