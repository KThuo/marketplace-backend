package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.infra.storage.StorageService;
import com.hodi.modules.developments.DevelopmentDtos.PublicDevelopmentResponse;
import com.hodi.modules.developments.DevelopmentDtos.PublicPhaseResponse;
import com.hodi.modules.developments.DevelopmentDtos.PublicUnitTypeResponse;
import com.hodi.modules.media.MediaAsset;
import com.hodi.modules.media.MediaAssetRepository;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Developments as a buyer sees them.
 *
 * <p>Every query starts from {@link #live()}, which is {@code LIVE} and not archived — so a DRAFT, a PENDING, a
 * WITHDRAWN and, importantly, a {@code PRIVATE} project cannot appear by somebody forgetting a predicate.
 * That last one is the bank's financed project, and the reason the exclusion is in the query rather than in a
 * flag on the response.
 *
 * <p>No tenant scoping, deliberately and for the same reason {@code PublicPropertyService} has none: a
 * marketplace that showed only one organisation's projects would not be a marketplace.
 *
 * <p>The response records here hold no budget, no facility, no spend, no address line and no buyer. Not
 * blanked — absent. A record without a field cannot leak it.
 */
@Service
@RequiredArgsConstructor
public class PublicDevelopmentService {

    private final DevelopmentRepository developments;
    private final DevelopmentUnitTypeRepository unitTypes;
    private final DevelopmentPhaseRepository phases;
    private final PropertyRepository properties;
    private final MediaAssetRepository media;
    private final StorageService storage;

    @Getter @Setter
    public static class PublicDevelopmentSearchRequest extends PagedDataRequest {
        private String county;
        private String town;
        private String developmentType;
        private String constructionStatus;
        private BigDecimal minPrice;
        private BigDecimal maxPrice;
        /** A bedroom count somewhere in the project, matched against its typologies. */
        private Short bedrooms;
    }

    @Transactional(readOnly = true)
    public PagedResponse<PublicDevelopmentResponse> search(PublicDevelopmentSearchRequest request) {
        Specification<Development> spec = SearchSpecs.allOf(
                live(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("county", blankToNull(request.getCounty())),
                SearchSpecs.eq("town", blankToNull(request.getTown())),
                SearchSpecs.eq("developmentType", blankToNull(request.getDevelopmentType())),
                SearchSpecs.eq("constructionStatus", blankToNull(request.getConstructionStatus())),
                priceWithin(request.getMinPrice(), request.getMaxPrice()),
                hasTypologyWithBedrooms(request.getBedrooms()));

        var page = developments.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "publishedAt")));
        // The card does not need the typologies, so the list mapping leaves them out — twenty results would
        // otherwise be twenty extra queries for panels nobody has opened.
        return PagedResponse.from(page, d -> toCard(d));
    }

    @Transactional(readOnly = true)
    public PublicDevelopmentResponse find(String reference) {
        Development development = developments.findLiveByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Development", reference));
        return toDetail(development);
    }

    /** The availability table: what each typology is, what it costs and how many are left. */
    @Transactional(readOnly = true)
    public List<PublicUnitTypeResponse> availability(String reference) {
        Development development = developments.findLiveByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Development", reference));
        return typologies(development);
    }

    /** Phases as dates and progress. Never a budget, a committed figure or a spend. */
    @Transactional(readOnly = true)
    public List<PublicPhaseResponse> phases(String reference) {
        Development development = developments.findLiveByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Development", reference));
        return phases.findForDevelopment(development.getId()).stream()
                .map(p -> new PublicPhaseResponse(
                        p.getName(),
                        p.getDescription(),
                        p.getSequenceNo(),
                        p.getPercentComplete(),
                        p.getMilestoneCode(),
                        p.getPlannedCompletionOn(),
                        p.getRevisedCompletionOn(),
                        p.getActualCompletionOn()))
                .toList();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * The only starting point for a public query.
     *
     * <p>PRIVATE is excluded by being not LIVE, which is why the tracked project needs no separate rule: there
     * is one state that is public and it is not that one.
     */
    private Specification<Development> live() {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("listingState"), AppConstant.LISTING_LIVE),
                cb.notEqual(root.get("status"), AppConstant.STATUS_DELETED));
    }

    /**
     * Overlap rather than containment: a project spanning 5m to 20m matches a search for 8m to 12m.
     *
     * <p>A development is a range of prices, so asking whether its range *contains* the filter would hide
     * every project with anything cheaper or dearer than the band — which is most of them.
     */
    private Specification<Development> priceWithin(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) return null;
        return (root, query, cb) -> {
            List<Predicate> ands = new ArrayList<>(2);
            if (max != null) ands.add(cb.lessThanOrEqualTo(root.get("fromPrice"), max));
            if (min != null) {
                ands.add(cb.greaterThanOrEqualTo(
                        cb.coalesce(root.get("toPrice"), root.get("fromPrice")), min));
            }
            return cb.and(ands.toArray(new Predicate[0]));
        };
    }

    /**
     * "Two bedrooms somewhere in this project", as an EXISTS over its typologies.
     *
     * <p>This is what makes a development card honest about a bedroom filter. A single column on the
     * development could not hold four different bedroom counts, and picking one would either miss a genuine
     * match or match everything.
     */
    private Specification<Development> hasTypologyWithBedrooms(Short bedrooms) {
        if (bedrooms == null) return null;
        return (root, query, cb) -> {
            var sub = query.subquery(Long.class);
            var type = sub.from(DevelopmentUnitType.class);
            sub.select(cb.literal(1L)).where(
                    cb.equal(type.get("developmentId"), root.get("id")),
                    cb.equal(type.get("bedrooms"), bedrooms),
                    cb.notEqual(type.get("status"), AppConstant.STATUS_DELETED));
            return cb.exists(sub);
        };
    }

    private List<PublicUnitTypeResponse> typologies(Development development) {
        return unitTypes.findForDevelopment(development.getId()).stream()
                .map(t -> {
                    Optional<Property> listing = properties.findByUnitTypeId(t.getId());
                    return new PublicUnitTypeResponse(
                            t.getReference(),
                            t.getCode(),
                            t.getName(),
                            t.getDescription(),
                            t.getPropertyType(),
                            t.getBedrooms(),
                            t.getBathrooms(),
                            t.getFloorAreaSqm(),
                            t.getFromPrice() != null ? t.getFromPrice() : t.getListPrice(),
                            t.getCurrency(),
                            t.getUnitsTotal(),
                            t.getUnitsAvailable(),
                            t.getConstructionStatus(),
                            t.getFloorPlanKey() == null ? null : storage.urlFor(t.getFloorPlanKey()),
                            // Only a live listing is offered as a way in. A draft one would be a link to a
                            // page a buyer cannot see.
                            listing.filter(p -> AppConstant.LISTING_LIVE.equals(p.getListingState()))
                                    .map(Property::getReference).orElse(null));
                })
                .toList();
    }

    private PublicDevelopmentResponse toCard(Development d) {
        return build(d, List.of(), List.of());
    }

    private PublicDevelopmentResponse toDetail(Development d) {
        List<String> images = media.findPublicForOwner(
                        AppConstant.MEDIA_OWNER_DEVELOPMENT, d.getId()).stream()
                .map(MediaAsset::getStorageKey)
                .map(storage::urlFor)
                .toList();
        return build(d, images, typologies(d));
    }

    private PublicDevelopmentResponse build(Development d, List<String> imageUrls,
                                            List<PublicUnitTypeResponse> typologies) {
        return new PublicDevelopmentResponse(
                d.getReference(),
                d.getName(),
                d.getDescription(),
                d.getDevelopmentType(),
                d.getDeveloperName(),
                d.getSellingTenantName(),
                d.getCounty(),
                d.getTown(),
                d.getEstate(),
                d.getLatitude(),
                d.getLongitude(),
                d.getUnitsTotal(),
                d.getUnitsAvailable(),
                d.getFromPrice(),
                d.getToPrice(),
                d.getCurrency(),
                d.getConstructionStatus(),
                d.getPercentComplete(),
                d.getProjectedCompletionOn(),
                d.getPrimaryImageKey() == null ? null : storage.urlFor(d.getPrimaryImageKey()),
                imageUrls,
                typologies);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
