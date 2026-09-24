package com.hodi.modules.properties;

import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.modules.tours.TourDtos.EditTourRequest;
import com.hodi.modules.tours.TourDtos.SaveTourRequest;
import com.hodi.modules.tours.TourDtos.TourResponse;
import com.hodi.modules.tours.VirtualTourService;
import com.hodi.modules.tours.VirtualTourService.Owner;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A listing's walkthrough videos: who may see and change them, and which owner they really belong to.
 *
 * <h2>The same sharing rule as the photographs</h2>
 *
 * <p>A listing generated from a development — a typology card or one of its units — does not own a tour. It
 * shows the typology's, and a tour pasted from its form is written to the typology, so the card and every
 * unit under it show the same show-unit walkthrough and cannot drift apart. The project's own tours (the
 * flythrough, the drive in from the gate) are on every listing in it, shown as inherited and changed only on
 * the project. A plain house owns its tours outright.
 *
 * <p>{@link PropertyMediaService} explains at length why this is inheritance and not "the nearest rung": a
 * typology with one tour must not hide the project's.
 */
@Service
@RequiredArgsConstructor
public class PropertyTourService {

    private final ListingAccess access;
    private final PropertyRepository properties;
    private final VirtualTourService tours;

    @Transactional(readOnly = true)
    public List<TourResponse> list(String propertyHashId) {
        return everythingFor(access.requireVisible(propertyHashId));
    }

    /**
     * Not transactional: YouTube is asked about the video between the permission check and the write, and a
     * connection held open across that call is one nobody else can use. See {@link VirtualTourService}.
     */
    public TourResponse add(String propertyHashId, SaveTourRequest request) {
        Property property = access.requireOwn(propertyHashId);
        var prepared = tours.prepare(request);
        return tours.add(ownerOf(property), prepared, sourceOf(property));
    }

    @Transactional
    public TourResponse edit(String propertyHashId, String tourHashId, EditTourRequest request) {
        Property property = access.requireOwn(propertyHashId);
        assertNotTheProjects(property, tourHashId, "change it");
        return tours.edit(ownerOf(property), tourHashId, request, sourceOf(property));
    }

    @Transactional
    public List<TourResponse> move(String propertyHashId, String tourHashId, int toIndex) {
        Property property = access.requireOwn(propertyHashId);
        assertNotTheProjects(property, tourHashId, "reorder it");
        tours.move(ownerOf(property), tourHashId, toIndex, sourceOf(property));
        return everythingFor(property);
    }

    @Transactional
    public void remove(String propertyHashId, String tourHashId) {
        Property property = access.requireOwn(propertyHashId);
        assertNotTheProjects(property, tourHashId, "remove it");
        tours.remove(ownerOf(property), tourHashId);
    }

    // ── the marketplace ───────────────────────────────────────────────────────

    /**
     * A live listing's tours, for anybody.
     *
     * <p>Live by the same query the detail page uses, so a draft's tours are exactly as invisible as the
     * draft. Separate from the listing's payload for the reason its progress timeline is: most listings have
     * none, and the page asks only when the card said there was one.
     */
    @Transactional(readOnly = true)
    public List<TourResponse> publicFor(String reference) {
        Property property = properties.findLiveByReference(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Listing", reference));
        return everythingFor(property);
    }

    /**
     * Which of a page of listings have a tour to show, from any of the three places one can come from.
     *
     * <p>Three queries for the page, whatever its size — one per owner type — rather than three per card.
     */
    @Transactional(readOnly = true)
    public Set<Long> withTours(Collection<Property> page) {
        if (page == null || page.isEmpty()) return Set.of();
        Set<Long> own = tours.ownersWithTours(VirtualTourService.OWNER_PROPERTY,
                page.stream().filter(p -> p.getUnitTypeId() == null).map(Property::getId).toList());
        Set<Long> typologies = tours.ownersWithTours(VirtualTourService.OWNER_UNIT_TYPE,
                page.stream().map(Property::getUnitTypeId).filter(Objects::nonNull).distinct().toList());
        Set<Long> projects = tours.ownersWithTours(VirtualTourService.OWNER_DEVELOPMENT,
                page.stream().filter(p -> p.getUnitTypeId() != null)
                        .map(Property::getDevelopmentId).filter(Objects::nonNull).distinct().toList());

        Set<Long> out = new HashSet<>();
        for (Property p : page) {
            boolean has = p.getUnitTypeId() == null
                    ? own.contains(p.getId())
                    : typologies.contains(p.getUnitTypeId())
                            || (p.getDevelopmentId() != null && projects.contains(p.getDevelopmentId()));
            if (has) out.add(p.getId());
        }
        return out;
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** Its own (or its typology's) first, then the project's: the specific home before the estate. */
    private List<TourResponse> everythingFor(Property property) {
        Owner owner = ownerOf(property);
        List<TourResponse> out = new ArrayList<>(tours.list(owner.type(), owner.id(), sourceOf(property)));
        if (PropertyMediaService.sharesTypologyMedia(property) && property.getDevelopmentId() != null) {
            out.addAll(tours.list(VirtualTourService.OWNER_DEVELOPMENT, property.getDevelopmentId(),
                    VirtualTourService.SOURCE_DEVELOPMENT));
        }
        return out;
    }

    private static Owner ownerOf(Property property) {
        return PropertyMediaService.sharesTypologyMedia(property)
                ? new Owner(VirtualTourService.OWNER_UNIT_TYPE, property.getUnitTypeId(), property.getTenantId(), null)
                : new Owner(VirtualTourService.OWNER_PROPERTY, property.getId(), property.getTenantId(), null);
    }

    private static String sourceOf(Property property) {
        return PropertyMediaService.sharesTypologyMedia(property)
                ? VirtualTourService.SOURCE_TYPOLOGY : VirtualTourService.SOURCE_OWN;
    }

    /**
     * The project's tour is on every listing in it; changing it from one listing's form would change it on all
     * of them. Said in words, as the photographs do, rather than left to fail as "not found" for a tour the
     * screen is showing.
     */
    private void assertNotTheProjects(Property property, String tourHashId, String verb) {
        if (!PropertyMediaService.sharesTypologyMedia(property) || property.getDevelopmentId() == null) return;
        boolean theProjects = tours.list(VirtualTourService.OWNER_DEVELOPMENT, property.getDevelopmentId(),
                        VirtualTourService.SOURCE_DEVELOPMENT).stream()
                .anyMatch(t -> t.id().equals(tourHashId));
        if (theProjects) {
            throw new HodiException("That tour belongs to the development, and every listing in it shows the same "
                    + "one. Open the project to " + verb + ".", HttpStatus.CONFLICT);
        }
    }
}
