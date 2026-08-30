package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.infra.storage.StorageService;
import com.hodi.modules.developments.DevelopmentDtos.PublicDevelopmentResponse;
import com.hodi.modules.developments.DevelopmentDtos.PublicTypeCount;
import com.hodi.modules.developments.DevelopmentDtos.PublicUnitTypeResponse;
import com.hodi.modules.developments.DevelopmentUnitDtos.PublicFeature;
import com.hodi.modules.developments.DevelopmentUnitDtos.PublicUnitAvailability;
import com.hodi.modules.developments.DevelopmentUnitDtos.PublicUnitDetail;
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
    private final DevelopmentUnitRepository units;
    private final UnitFeatureRepository features;
    private final UnitFeatureConfigRepository featureConfigs;
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
        List<Long> ids = page.getContent().stream().map(Development::getId).toList();
        Map<Long, short[]> ranges = bedroomRanges(ids);
        Map<Long, List<PublicTypeCount>> counts = typeCounts(ids);
        return PagedResponse.from(page, d -> toCard(d, ranges.get(d.getId()),
                counts.getOrDefault(d.getId(), List.of())));
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
        Page<ProgressUpdate> page = progress.findPublicFeed(liveIds,
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
                    u.getTitle(), u.getBody(), u.getReportedOn(),
                    storage.urlFor(u.getImageKey()),
                    imagesFor(u));
        });
    }

    /**
     * A post on the public feed: a project, some words, and photographs.
     *
     * <p>Its own record rather than {@code PublicUpdate} plus a project field, because a feed card needs the
     * project's identity to link anywhere and a per-project page must not repeat it on every row.
     *
     * <h3>What is deliberately absent</h3>
     *
     * <p>No percentage and no stage. Those made this a detailed build report, and a detailed build report is
     * not what a stranger browsing a marketplace should be reading — a slipped date and a stalled percentage
     * are for the people running and financing the build. What is left is what a post like this is for:
     * interesting somebody in a project.
     *
     * <p>The fields are gone from the record rather than left null, because a nullable field is one somebody
     * populates later without noticing what it means here.
     *
     * <p>No budgets, no spend, no buyer either — the same separate-public-record discipline as
     * {@code PublicPropertyResponse}.
     */
    public record PublicProgressItem(
            String developmentReference,
            String developmentName,
            String town,
            String county,
            String title,
            String body,
            java.time.LocalDate reportedOn,
            /** The cover, and then the rest. A post with photographs is the point of the post. */
            String imageUrl,
            List<String> imageUrls) {}

    /**
     * The photographs on one post.
     *
     * <p>One query per post, and that is a real cost on a twelve-card feed. It is paid because a post without
     * its pictures is not the thing being published — and the alternative, a batched lookup across every post
     * on the page, is a join this class would have to hold a second map for. Worth revisiting if the feed
     * grows; not worth pre-solving at twelve.
     */
    /**
     * The newest post written for the public, or nothing.
     *
     * <p>Read straight from the repository rather than through {@code DevelopmentProgressService}, which would
     * be a circular dependency — that service reads this one for the live() predicate. The audience condition
     * lives in the query either way, which is the part that matters.
     */
    private DevelopmentDtos.PublicPost latestPost(Development d) {
        return progress.findPublicForDevelopment(d.getId()).stream().findFirst()
                .map(u -> new DevelopmentDtos.PublicPost(
                        u.getTitle(), u.getBody(), u.getReportedOn(),
                        storage.urlFor(u.getImageKey()), imagesFor(u)))
                .orElse(null);
    }

    private List<String> imagesFor(ProgressUpdate update) {
        return media.findPublicForOwner(AppConstant.MEDIA_OWNER_PROGRESS_UPDATE, update.getId()).stream()
                .map(MediaAsset::getStorageKey)
                .map(storage::urlFor)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /** The availability table: what each typology is, what it costs and how many are left. */
    @Transactional(readOnly = true)
    public List<PublicUnitTypeResponse> availability(String reference) {
        Development development = developments.findLiveByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Development", reference));
        return typologies(development);
    }

    /**
     * Every unit of one typology, with what a buyer may know about each.
     *
     * <p>Sold units included. On an off-plan scheme what has gone is half the information — "four of the six
     * third-floor two-beds are taken" is what makes somebody decide this week rather than next month — and a
     * list showing only what is left cannot say that.
     *
     * <p>Buyer identity is absent from the response record entirely, rather than nulled on the way out. A
     * field that exists is one somebody populates later.
     *
     * <p>The typology is resolved through the development in the path, so a reference from another project
     * cannot be read by guessing at this endpoint.
     */
    @Transactional(readOnly = true)
    public List<PublicUnitAvailability> unitsFor(String reference, String unitTypeReference) {
        Development development = developments.findLiveByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Development", reference));

        DevelopmentUnitType type = unitTypes.findForDevelopment(development.getId()).stream()
                .filter(t -> t.getReference().equals(unitTypeReference))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Unit type", unitTypeReference));

        BigDecimal fallbackPrice = type.getListPrice();
        return units.findPublicForUnitType(type.getId()).stream()
                .map(u -> new PublicUnitAvailability(
                        u.getUnitLabel(),
                        u.getBlock(),
                        u.getFloorNo(),
                        u.getListPrice() != null ? u.getListPrice() : fallbackPrice,
                        u.getCurrency() == null ? type.getCurrency() : u.getCurrency(),
                        publicState(u.getSaleState()),
                        u.getReference()))
                .toList();
    }

    /**
     * One specific home.
     *
     * <p>Its own answers where it gave them and its kind's where it did not — resolved by {@link UnitSpec},
     * which is the only place that rule lives. A buyer is choosing a flat, not auditing our data model, so
     * what comes back is final figures rather than two sets to reconcile.
     *
     * <p>Resolved through the development in the path, so a reference from another project cannot be read by
     * guessing at this endpoint. Only a live project answers at all.
     */
    @Transactional(readOnly = true)
    public PublicUnitDetail unitDetail(String reference, String unitReference) {
        Development development = developments.findLiveByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Development", reference));

        DevelopmentUnit unit = units.findByReference(unitReference)
                .filter(u -> development.getId().equals(u.getDevelopmentId()))
                .orElseThrow(() -> new ResourceNotFoundException("Unit", unitReference));

        DevelopmentUnitType type = unit.getUnitTypeId() == null ? null
                : unitTypes.findById(unit.getUnitTypeId()).orElse(null);

        List<String> own = features.findForUnit(unit.getId()).stream()
                .map(UnitFeature::getFeatureCode).toList();
        List<String> fromType = type == null ? List.of()
                : features.findForUnitType(type.getId()).stream()
                        .map(UnitFeature::getFeatureCode).toList();

        UnitSpec spec = UnitSpec.of(unit, type, own, fromType);

        // The catalogue turns codes into the words a buyer reads, in the order a screen shows them.
        Map<String, UnitFeatureConfig> catalogue = featureConfigs.findLive().stream()
                .collect(Collectors.toMap(UnitFeatureConfig::getCode, c -> c, (a, b) -> a,
                        java.util.LinkedHashMap::new));
        List<PublicFeature> resolved = catalogue.values().stream()
                .filter(c -> spec.featureCodes().contains(c.getCode()))
                .map(c -> new PublicFeature(c.getCode(), c.getName(), c.getCategory()))
                .toList();

        /*
         * A unit's own photographs, and its kind's when it has none.
         *
         * The same inheritance the figures follow, and for the same reason: eight units in a block of two
         * hundred are photographed individually and the rest are represented by the show flat.
         */
        List<String> images = media.findPublicForOwner(
                        AppConstant.MEDIA_OWNER_DEVELOPMENT_UNIT, unit.getId()).stream()
                .map(MediaAsset::getStorageKey).map(storage::urlFor)
                .filter(java.util.Objects::nonNull).toList();
        if (images.isEmpty() && type != null) {
            images = media.findPublicForOwner(AppConstant.MEDIA_OWNER_UNIT_TYPE, type.getId()).stream()
                    .map(MediaAsset::getStorageKey).map(storage::urlFor)
                    .filter(java.util.Objects::nonNull).toList();
        }

        return new PublicUnitDetail(
                unit.getReference(), unit.getUnitLabel(), unit.getBlock(), unit.getFloorNo(),
                unit.getDoorNo(), spec.aspect(), spec.description(),
                spec.bedrooms(), spec.bathrooms(), spec.balconies(), spec.parkingSpaces(),
                spec.floorAreaSqm(), spec.balconyAreaSqm(), spec.price(), spec.currency(),
                publicState(unit.getSaleState()), unit.getConstructionStatus(),
                resolved, images, spec.inherited(),
                development.getReference(), development.getName(),
                type == null ? null : type.getReference(),
                type == null ? null : type.getName());
    }

    /**
     * The six internal sale states as the three a buyer can act on.
     *
     * <p>HELD and RESERVED are both "somebody else is buying it", and the difference between them is our
     * paperwork rather than their opportunity. NOT_FOR_SALE and RETAINED are both "not on offer" — publishing
     * which is which would tell a competitor how much stock the developer is keeping back.
     */
    private String publicState(String saleState) {
        if (saleState == null) return "UNAVAILABLE";
        return switch (saleState) {
            case AppConstant.UNIT_AVAILABLE -> "AVAILABLE";
            case AppConstant.UNIT_HELD, AppConstant.UNIT_RESERVED, AppConstant.UNIT_SOLD -> "TAKEN";
            default -> "UNAVAILABLE";
        };
    }

    /*
     * There is no public phases method any more.
     *
     * It returned a phase-by-phase breakdown — names, percentages, planned dates and the revised date beside
     * each — for a panel on the project's page. A slipped completion date is information for the people
     * running and financing the build, and publishing it to whoever is browsing was the thing that made the
     * public page a detailed build report. The headline figures on the detail response are what remains, and
     * the phases live in the workspace where the permission to see them does.
     */

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
        List<DevelopmentUnitType> types = unitTypes.findForDevelopment(development.getId());
        if (types.isEmpty()) return List.of();

        /*
         * Every typology's photographs in one query, grouped by owner.
         *
         * A per-typology lookup would be four queries on this project and twenty on a bigger one, for a page
         * that always shows all of them — the same batching the search cards do for bedroom ranges.
         */
        Map<Long, List<String>> imagesByType = media
                .findPublicForOwners(AppConstant.MEDIA_OWNER_UNIT_TYPE,
                        types.stream().map(DevelopmentUnitType::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(MediaAsset::getOwnerId,
                        java.util.LinkedHashMap::new,
                        Collectors.mapping(a -> storage.urlFor(a.getStorageKey()), Collectors.toList())));

        return types.stream()
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
                            t.getParkingSpaces(),
                            t.getFloorAreaSqm(),
                            t.getBalconyAreaSqm(),
                            t.getFromPrice() != null ? t.getFromPrice() : t.getListPrice(),
                            t.getServiceCharge(),
                            t.getCurrency(),
                            t.getUnitsTotal(),
                            t.getUnitsAvailable(),
                            t.getConstructionStatus(),
                            t.getFloorPlanKey() == null ? null : storage.urlFor(t.getFloorPlanKey()),
                            imagesByType.getOrDefault(t.getId(), List.of()),
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

    private PublicDevelopmentResponse toCard(Development d, short[] bedrooms,
                                             List<PublicTypeCount> typeCounts) {
        // No post on a card: twenty cards would be twenty queries for a paragraph nobody has opened yet.
        return build(d, List.of(), List.of(), bedrooms, null, typeCounts);
    }

    /**
     * What each typology is called and how many are left, for a page of cards.
     *
     * <p>"Studio · 5 left · 2 bed · 12 left" on the card itself, because a project of two hundred units is
     * only interesting to somebody who can see whether the kind they want is still there. One query for the
     * page; the counts are already maintained on the typology row by the inventory service, so this reads
     * them rather than counting units.
     *
     * <p>Sold-out typologies are kept and marked rather than dropped: a card showing three of four kinds,
     * silently, reads as a project with three kinds.
     */
    private Map<Long, List<PublicTypeCount>> typeCounts(List<Long> developmentIds) {
        if (developmentIds.isEmpty()) return Map.of();
        return unitTypes.findForDevelopments(developmentIds).stream()
                .collect(Collectors.groupingBy(DevelopmentUnitType::getDevelopmentId,
                        java.util.LinkedHashMap::new,
                        Collectors.mapping(t -> new PublicTypeCount(
                                t.getReference(), t.getName(), t.getBedrooms(),
                                t.getUnitsAvailable(), t.getUnitsTotal()), Collectors.toList())));
    }

    private PublicDevelopmentResponse toDetail(Development d) {
        List<String> images = media.findPublicForOwner(
                        AppConstant.MEDIA_OWNER_DEVELOPMENT, d.getId()).stream()
                .map(MediaAsset::getStorageKey)
                .map(storage::urlFor)
                .toList();
        return build(d, images, typologies(d),
                bedroomRanges(List.of(d.getId())).get(d.getId()),
                latestPost(d),
                typeCounts(List.of(d.getId())).getOrDefault(d.getId(), List.of()));
    }

    private PublicDevelopmentResponse build(Development d, List<String> imageUrls,
                                            List<PublicUnitTypeResponse> typologies,
                                            short[] bedrooms,
                                            DevelopmentDtos.PublicPost latestPost,
                                            List<PublicTypeCount> typeCounts) {
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
                typologies,
                latestPost,
                typeCounts);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
