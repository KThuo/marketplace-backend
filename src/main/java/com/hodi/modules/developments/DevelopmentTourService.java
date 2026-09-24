package com.hodi.modules.developments;

import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.modules.tours.TourDtos.EditTourRequest;
import com.hodi.modules.tours.TourDtos.SaveTourRequest;
import com.hodi.modules.tours.TourDtos.TourResponse;
import com.hodi.modules.tours.VirtualTourService;
import com.hodi.modules.tours.VirtualTourService.Owner;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Walkthrough videos for a project and for its kinds of home.
 *
 * <p>The authorising layer over {@link VirtualTourService}, as {@link DevelopmentMediaService} is over the
 * media store: the development is resolved and checked, a typology is confirmed to be this development's,
 * and only then is the owner handed down.
 *
 * <p>Two owners, not five. A phase or a single unit could in principle have a video, but nothing a buyer
 * opens is about a phase, and a unit already shows its typology's show-unit tour — a second walkthrough of an
 * identical flat is the same video filmed twice.
 *
 * <p>Writing is gated like the photographs: a collaborator granted progress may add them, because a drone
 * flight over the site is progress.
 */
@Service
@RequiredArgsConstructor
public class DevelopmentTourService {

    private final VirtualTourService tours;
    private final DevelopmentRepository developments;
    private final DevelopmentUnitTypeRepository unitTypes;
    private final DevelopmentVisibility visibility;

    @Transactional(readOnly = true)
    public List<TourResponse> list(String developmentHashId, String ownerType, String childHashId) {
        Development development = requireVisible(developmentHashId);
        Owner owner = ownerFor(development, ownerType, childHashId);
        return tours.list(owner.type(), owner.id(), sourceOf(owner));
    }

    /** Not transactional, for the reason {@link VirtualTourService#prepare} gives. */
    public TourResponse add(String developmentHashId, String ownerType, String childHashId,
                            SaveTourRequest request) {
        Development development = requireWritable(developmentHashId);
        Owner owner = ownerFor(development, ownerType, childHashId);
        var prepared = tours.prepare(request);
        return tours.add(owner, prepared, sourceOf(owner));
    }

    @Transactional
    public TourResponse edit(String developmentHashId, String ownerType, String childHashId,
                             String tourHashId, EditTourRequest request) {
        Development development = requireWritable(developmentHashId);
        Owner owner = ownerFor(development, ownerType, childHashId);
        return tours.edit(owner, tourHashId, request, sourceOf(owner));
    }

    @Transactional
    public List<TourResponse> move(String developmentHashId, String ownerType, String childHashId,
                                   String tourHashId, int toIndex) {
        Development development = requireWritable(developmentHashId);
        Owner owner = ownerFor(development, ownerType, childHashId);
        return tours.move(owner, tourHashId, toIndex, sourceOf(owner));
    }

    @Transactional
    public void remove(String developmentHashId, String ownerType, String childHashId, String tourHashId) {
        Development development = requireWritable(developmentHashId);
        tours.remove(ownerFor(development, ownerType, childHashId), tourHashId);
    }

    /**
     * A live project's tours for its public page: the project's own first, then each kind of home's, named.
     *
     * <p>The typologies' are included because the project page is where somebody compares them — "what does
     * the two-bed look like inside" is asked there, before anybody has opened a typology.
     */
    @Transactional(readOnly = true)
    public List<TourResponse> publicFor(String reference) {
        Development development = developments.findLiveByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Development", reference));
        List<TourResponse> out = new ArrayList<>(tours.list(VirtualTourService.OWNER_DEVELOPMENT,
                development.getId(), VirtualTourService.SOURCE_DEVELOPMENT));
        for (DevelopmentUnitType type : unitTypes.findForDevelopment(development.getId())) {
            tours.list(VirtualTourService.OWNER_UNIT_TYPE, type.getId(), VirtualTourService.SOURCE_TYPOLOGY)
                    .forEach(t -> out.add(t.labelled(type.getName())));
        }
        return out;
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private Owner ownerFor(Development development, String ownerType, String childHashId) {
        String type = ownerType == null ? VirtualTourService.OWNER_DEVELOPMENT : ownerType.trim().toUpperCase();
        if (VirtualTourService.OWNER_DEVELOPMENT.equals(type)) {
            return new Owner(type, development.getId(), development.getTenantId(), development.getInstitutionId());
        }
        if (!VirtualTourService.OWNER_UNIT_TYPE.equals(type)) {
            throw new HodiException("Tours can be attached to the project or to a kind of home, not to a "
                    + ownerType + ".", HttpStatus.BAD_REQUEST);
        }
        if (childHashId == null || childHashId.isBlank()) {
            throw new ResourceNotFoundException("Unit type", childHashId);
        }
        DevelopmentUnitType unitType = unitTypes.findById(HashIdUtil.decodeId(childHashId))
                // Another development's typology is not found here, whatever its id: the path names the
                // development, and the schema's keys stop a bad row, not a bad request.
                .filter(t -> t.getDevelopmentId().equals(development.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Unit type", childHashId));
        return new Owner(type, unitType.getId(), development.getTenantId(), development.getInstitutionId());
    }

    private static String sourceOf(Owner owner) {
        return VirtualTourService.OWNER_UNIT_TYPE.equals(owner.type())
                ? VirtualTourService.SOURCE_TYPOLOGY : VirtualTourService.SOURCE_DEVELOPMENT;
    }

    private Development requireVisible(String developmentHashId) {
        Development development = developments.findById(HashIdUtil.decodeId(developmentHashId))
                .orElseThrow(() -> new ResourceNotFoundException("Development", developmentHashId));
        if (!visibility.mayRead(development, AuthContext.require())) {
            throw new ResourceNotFoundException("Development", developmentHashId);
        }
        return development;
    }

    private Development requireWritable(String developmentHashId) {
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteProgress(development, AuthContext.require());
        return development;
    }
}
