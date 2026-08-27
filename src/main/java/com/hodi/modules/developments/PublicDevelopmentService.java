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
import com.hodi.modules.properties.ProgressUpdate;
import com.hodi.modules.properties.ProgressUpdateRepository;
import com.hodi.modules.properties.PropertyRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
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
    private final ProgressUpdateRepository progress;
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
        /*
         * The card does not need the typologies, so the list mapping leaves them out — twenty results would
         * otherwise be twenty extra queries for panels nobody has opened. The bedroom span it does need, and
         * that is one query for the whole page rather than one per card.
         */
        Map<Long, short[]> ranges = bedroomRanges(
                page.getContent().stream().map(Development::getId).toList());
        return PagedResponse.from(page, d -> toCard(d, ranges.get(d.getId())));
    }

    @Transactional(readOnly = true)
    public PublicDevelopmentResponse find(String reference) {
        Development development = developments.findLiveByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Development", reference));
        return toDetail(development);
    }

    /**
     * The cross-project progress feed behind the marketplace's Progress tab.
     *
     * <p>This is the primary channel for progress, not a preview of an email. A buyer following a build reads
     * it here, which is why it ships before any newsletter: it needs no sending domain, no API key and nobody
     * else's lead time, and it is the "view in browser" page a newsletter would have needed anyway.
     *
     * <h3>Why the live ids are materialised</h3>
     *
     * <p>The alternative is an EXISTS subquery over developments inside the progress repository, which would
     * put the definition of "public" in a second place — and the failure mode of the two drifting is a private
     * project's build appearing in a feed. One list, from {@link #live()}, at the cost of one extra query.
     *
     * <p>That cost is bounded by the number of *live developments*, not by the number of updates, and a
     * marketplace with more live projects than fit in a list has bigger problems than this query. If it ever
     * does, the fix is a join and a single owner for the predicate, not a second copy of it.
     */
    @Transactional(readOnly = true)
    public PagedResponse<PublicProgressItem> progressFeed(PagedDataRequest request) {
        List<Long> liveIds = developments.findAll(live()).stream().map(Development::getId).toList();
        if (liveIds.isEmpty()) {
            // `in ()` is not valid SQL, so nothing-to-search is answered here rather than by the database.
            var pageable = request.toPageable(Sort.unsorted());
            return PagedResponse.of(List.of(), pageable.getPageNumber(), pageable.getPageSize(),
                    0L, 0, true);
        }

        // The repository orders by reported_on then id, so the sort is not the caller's to choose. A feed
        // whose order a query parameter could change is a feed whose pages do not line up.
        Page<ProgressUpdate> page = progress.findPublishedFeed(liveIds,
                request.toPageable(Sort.unsorted()));

        // One lookup for the whole page rather than one per row: the card carries the project's name and
        // reference so it can link, and twenty rows from three projects should be three reads, not twenty.
        Map<Long, Development> byId = developments.findAllById(
                        page.getContent().stream().map(ProgressUpdate::getDevelopmentId)
                                .filter(Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(Development::getId, d -> d));

        return PagedResponse.from(page, u -> {
            Development d = byId.get(u.getDevelopmentId());
            return new PublicProgressItem(
                    d == null ? null : d.getReference(),
                    d == null ? null : d.getName(),
                    d == null ? null : d.getTown(),
                    d == null ? null : d.getCounty(),
                    u.getTitle(), u.getBody(), u.getPercentComplete(), u.getMilestone(),
                    u.getReportedOn(), storage.urlFor(u.getImageKey()),
                    u.getImageCount() == null ? 0 : u.getImageCount());
        });
    }

    /**
     * A card on the public progress feed.
     *
     * <p>Its own record rather than {@code PublicUpdate} plus a project field, because a feed card needs the
     * project's identity to link anywhere and a per-project timeline must not repeat it on every row.
     *
     * <p>No budgets, no spend, no buyer. The same separate-public-record discipline as
     * {@code PublicPropertyResponse}: a record with private fields blanked is one refactor away from leaking
     * them.
     */
    public record PublicProgressItem(
            String developmentReference,
            String developmentName,
            String town,
            String county,
            String title,
            String body,
            Short percentComplete,
            String milestone,
            java.time.LocalDate reportedOn,
            String imageUrl,
            int imageCount) {}

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

    /**
     * The bedroom span of each project, keyed by id, from one query.
     *
     * <p>{@code short[]} of exactly two rather than a record, because it is read once three lines later and a
     * named type for it would be a class nobody else ever mentions.
     */
    private Map<Long, short[]> bedroomRanges(List<Long> developmentIds) {
        if (developmentIds.isEmpty()) return Map.of();
        Map<Long, short[]> ranges = new java.util.HashMap<>();
        for (Object[] row : unitTypes.bedroomRanges(developmentIds)) {
            if (row[1] == null || row[2] == null) continue;
            ranges.put(((Number) row[0]).longValue(), new short[] {
                    ((Number) row[1]).shortValue(), ((Number) row[2]).shortValue() });
        }
        return ranges;
    }

    private PublicDevelopmentResponse toCard(Development d, short[] bedrooms) {
        return build(d, List.of(), List.of(), bedrooms);
    }

    private PublicDevelopmentResponse toDetail(Development d) {
        List<String> images = media.findPublicForOwner(
                        AppConstant.MEDIA_OWNER_DEVELOPMENT, d.getId()).stream()
                .map(MediaAsset::getStorageKey)
                .map(storage::urlFor)
                .toList();
        return build(d, images, typologies(d),
                bedroomRanges(List.of(d.getId())).get(d.getId()));
    }

    private PublicDevelopmentResponse build(Development d, List<String> imageUrls,
                                            List<PublicUnitTypeResponse> typologies,
                                            short[] bedrooms) {
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
                bedrooms == null ? null : bedrooms[0],
                bedrooms == null ? null : bedrooms[1],
                typologies);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
