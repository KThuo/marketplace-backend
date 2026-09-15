package com.hodi.modules.properties;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.infra.storage.StorageService;
import com.hodi.modules.properties.PropertyDtos.Facet;
import com.hodi.modules.properties.PropertyDtos.FacetsResponse;
import com.hodi.modules.properties.PropertyDtos.PublicPropertyResponse;
import com.hodi.modules.properties.PropertyDtos.PublicSearchRequest;
import com.hodi.security.hashid.HashIdUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The marketplace: what a stranger can search and read.
 *
 * <h2>Live only, and by construction</h2>
 *
 * <p>Every query here starts from {@code listing_state = 'LIVE' AND status <> 5}, which is also the partial
 * index that serves it. There is no code path that could return a draft — not because each method remembers
 * to filter, but because the only index that answers these queries carries nothing else.
 *
 * <p><strong>No TenantScope.</strong> Deliberately: this is the one surface where organisation isolation does
 * not apply, because a marketplace that showed each visitor one seller's listings would not be a marketplace.
 * What replaces it is the state filter — being live *is* the permission to be seen.
 *
 * <h2>Facets are columns</h2>
 *
 * <p>The BRD's facets are indexed columns with counts read off them, not a search service. A trigram index
 * answers "kilimani", a btree answers "under fifteen million", and Postgres does both — which is the whole
 * reason M2's plan note about the caching layer says Redis rather than Elasticsearch.
 */
@Service
@RequiredArgsConstructor
public class PublicPropertyService {

    private final PropertyRepository repository;
    private final PropertyMediaRepository media;
    private final StorageService storage;
    private final EntityManager entityManager;
    private final com.hodi.modules.developments.PublicDevelopmentService publicDevelopments;
    private final com.hodi.modules.developments.UnitFeatureRepository features;
    private final com.hodi.modules.developments.UnitFeatureConfigRepository featureConfigs;

    @Transactional(readOnly = true)
    public PagedResponse<PublicPropertyResponse> search(PublicSearchRequest request) {
        var page = repository.findAll(criteria(request), request.toPageable(sortOf(request.getSort())));
        return PagedResponse.from(page, this::toCard);
    }

    /**
     * What has become live since a moment in time, matching the same criteria.
     *
     * <p>The saved-search dispatcher's query, and deliberately this class's — a buyer's standing search has
     * to mean exactly what the same filters mean on the marketplace today, including whatever a later facet
     * adds. Building it beside the alert table instead would be a second implementation of the same search,
     * and the two would agree only until somebody changed one.
     *
     * <p>{@code publishedAt} strictly after {@code since}, so a listing is reported once: the caller stores
     * the moment it ran and passes it back next time.
     */
    @Transactional(readOnly = true)
    public List<Property> newMatches(PublicSearchRequest criteria, OffsetDateTime since, int limit) {
        Specification<Property> spec = SearchSpecs.allOf(
                criteria(criteria),
                (root, query, cb) -> cb.greaterThan(root.get("publishedAt"), since));
        return repository.findAll(spec,
                        SearchSpecs.page(0, limit, Sort.by(Sort.Direction.DESC, "publishedAt")))
                .getContent();
    }

    /** The filters, in one place, so the marketplace and a saved search cannot mean different things. */
    private Specification<Property> criteria(PublicSearchRequest request) {
        return SearchSpecs.allOf(
                live(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("propertyType", blankToNull(request.getPropertyType())),
                SearchSpecs.eq("county", blankToNull(request.getCounty())),
                SearchSpecs.eq("town", blankToNull(request.getTown())),
                priceBetween(request.getMinPrice(), request.getMaxPrice()),
                bedroomsBetween(request.getMinBedrooms(), request.getMaxBedrooms()),
                greenOnly(request.getGreenOnly()));
    }

    /*
     * Typology listings are in the results, and this is the second time that decision has moved.
     *
     * They were excluded so that a two-hundred-unit project appeared once as a development card rather than
     * four times. The trouble is that a development card answers the wrong question: "Highrise Apartments,
     * 60 of 200 available" does not tell somebody whether the studio they can afford is still there, and it
     * takes two clicks to find out.
     *
     * One row per *kind* is the shape that works for both — four rows for a project of two hundred, each
     * saying what it is, what it starts at and how many are left, with the project's name as a link. A
     * hundred and twenty-five identical bungalows are still four rows, not a hundred and twenty-five, which
     * is the duplication worth avoiding. The individual homes are a drill-down.
     *
     * A saved search now matches these too, which is the right answer and was previously called out as a gap:
     * `criteria` is shared with the alert dispatcher on purpose, so a standing search means exactly what the
     * same filters mean on the marketplace today.
     */

    /**
     * One listing, by its reference rather than by an id.
     *
     * <p>A reference is what a buyer is given, quotes down the phone and pastes into a message. It is also
     * stable across the id obfuscation, which the public salt makes uniform but which still reads as noise —
     * "PR2608240K4M" is a thing somebody can write down.
     */
    @Transactional(readOnly = true)
    public PublicPropertyResponse findByReference(String reference) {
        Property property = repository.findLiveByReference(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Listing", reference));
        return toDetail(property);
    }

    /**
     * What there is to filter by, counted from what is live right now.
     *
     * <p>Counted rather than listed from an enum: a filter offering "Industrial" when no industrial property
     * is listed is a filter that returns an empty page, and a buyer reads that as the site being broken
     * rather than as the market being quiet.
     */
    @Transactional(readOnly = true)
    public FacetsResponse facets() {
        List<Facet> types = countBy("property_type");
        List<Facet> counties = countBy("county");
        List<String> towns = repository.liveTowns();

        // Every facet counts what the list shows, and the list now shows typology listings too — so these
        // count every live row again. A filter promising a count the page cannot produce is the bug either
        // way; the fix is that both sides agree, not which side they agree on.
        Object[] range = (Object[]) entityManager.createNativeQuery(
                        "select min(price), max(price) from properties "
                                + "where listing_state = 'LIVE' and listing_kind <> 'UNIT' and status <> 5")
                .getSingleResult();

        return new FacetsResponse(types, counties, towns,
                range[0] == null ? null : new BigDecimal(range[0].toString()),
                range[1] == null ? null : new BigDecimal(range[1].toString()),
                repository.countLive());
    }

    // ── specifications ────────────────────────────────────────────────────────

    /** The only thing a stranger may see. Every public query starts here. */
    private Specification<Property> live() {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("listingState"), AppConstant.LISTING_LIVE),
                // One row per kind of home, not one per home: an estate of 125 bungalows is four results. The
                // units are properties too, reached through their typology's card, not listed beside it.
                cb.notEqual(root.get("listingKind"), AppConstant.LISTING_KIND_UNIT),
                cb.notEqual(root.get("status"), AppConstant.STATUS_DELETED));
    }

    private Specification<Property> priceBetween(BigDecimal min, BigDecimal max) {
        if (min == null && max == null) return null;
        return (root, query, cb) -> {
            List<Predicate> parts = new ArrayList<>(2);
            if (min != null) parts.add(cb.greaterThanOrEqualTo(root.get("price"), min));
            if (max != null) parts.add(cb.lessThanOrEqualTo(root.get("price"), max));
            return cb.and(parts.toArray(new Predicate[0]));
        };
    }

    private Specification<Property> bedroomsBetween(Short min, Short max) {
        if (min == null && max == null) return null;
        return (root, query, cb) -> {
            List<Predicate> parts = new ArrayList<>(2);
            if (min != null) parts.add(cb.greaterThanOrEqualTo(root.get("bedrooms"), min));
            if (max != null) parts.add(cb.lessThanOrEqualTo(root.get("bedrooms"), max));
            return cb.and(parts.toArray(new Predicate[0]));
        };
    }

    private Specification<Property> greenOnly(Boolean greenOnly) {
        if (!Boolean.TRUE.equals(greenOnly)) return null;
        return (root, query, cb) -> cb.isTrue(root.get("greenCertified"));
    }

    /**
     * Newest first unless asked otherwise.
     *
     * <p>An allowlist rather than a field name from the request: a sort parameter passed to the ORM is a way
     * to order by a column the caller was never shown, and to learn things from the ordering.
     */
    private Sort sortOf(String sort) {
        String key = sort == null ? "" : sort.trim().toLowerCase();
        /*
         * Paid placement lifts a listing within whatever order was asked for (M13) — it never replaces it.
         *
         * Somebody who sorted by price ascending asked for the cheapest first and should get it; a promoted
         * listing appearing above a cheaper one under that heading would make the sort a lie. So the boost
         * is the first key only on the default ordering, where "relevance" is the platform's to define, and
         * a secondary key elsewhere, where it breaks ties and nothing more.
         *
         * The boost is a column on `properties`, written by PromotionService — search is the hottest read
         * path here and a join per result to find out whether somebody paid is a join per result.
         */
        Sort boost = Sort.by(Sort.Direction.DESC, "promotionBoost");
        return switch (key) {
            case "price-asc" -> Sort.by(Sort.Direction.ASC, "price").and(boost);
            case "price-desc" -> Sort.by(Sort.Direction.DESC, "price").and(boost);
            default -> boost.and(Sort.by(Sort.Direction.DESC, "publishedAt"));
        };
    }

    /**
     * Counts per distinct value of one column.
     *
     * <p>Native, because JPA cannot group by a column named at runtime — and the column name is never request
     * input: the two callers pass literals from this file. A parameter here would be an injection point in the
     * one query that cannot be parameterised.
     */
    @SuppressWarnings("unchecked")
    private List<Facet> countBy(String column) {
        List<Object[]> rows = entityManager.createNativeQuery(
                        // The column name is spliced because a GROUP BY target cannot be a bind parameter.
                        // It is a literal from this class and never request input, and the two callers above
                        // are the only ones.
                        "select " + column + ", count(*) from properties "
                                + "where listing_state = 'LIVE' and listing_kind <> 'UNIT' and status <> 5 and " + column
                                + " is not null group by 1 order by 2 desc, 1 asc")
                .getResultList();
        List<Facet> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            out.add(new Facet(String.valueOf(row[0]), ((Number) row[1]).longValue()));
        }
        return out;
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    /** The card: enough to decide whether to open it, and nothing more. */
    private PublicPropertyResponse toCard(Property p) {
        return response(p, List.of());
    }

    /** The detail page: the card plus every photograph. */
    private PublicPropertyResponse toDetail(Property p) {
        List<String> images = media.findForProperty(p.getId()).stream()
                .map(m -> storage.urlFor(m.getStorageKey()))
                .filter(java.util.Objects::nonNull)
                .toList();
        return response(p, images);
    }

    private PublicPropertyResponse response(Property p, List<String> images) {
        return new PublicPropertyResponse(
                HashIdUtil.encodeId(p.getId()),
                p.getReference(),
                p.getTitle(),
                p.getDescription(),
                p.getPropertyType(),
                p.getListingType(),
                p.getTenure(),
                p.getPrice(),
                p.getCurrency(),
                p.getServiceCharge(),
                p.isPriceNegotiable(),
                p.getBedrooms(),
                p.getBathrooms(),
                p.getParkingSpaces(),
                p.getFloorAreaSqm(),
                p.getPlotAreaAcres(),
                p.getYearBuilt(),
                p.getCounty(),
                p.getTown(),
                p.getEstate(),
                p.getLatitude(),
                p.getLongitude(),
                p.isGreenCertified(),
                p.getGreenCertification(),
                p.getEnergyRating(),
                p.isHasSolar(),
                p.isHasBorehole(),
                p.isRainwaterHarvesting(),
                p.getTenantName(),
                storage.urlFor(p.getPrimaryImageKey()),
                images,
                p.getPromotionBoost() != null && p.getPromotionBoost() > 0,
                p.getPublishedAt(),
                /*
                 * Only on a listing that stands for a group. An ordinary house has one of itself, and a card
                 * showing "1 remaining" beside a bungalow would read as though it were nearly gone.
                 */
                p.isUnitTypeListing() ? p.getDevelopmentName() : null,
                p.isUnitTypeListing() ? p.getDevelopmentReference() : null,
                p.isUnitTypeListing() ? p.getUnitTypeReference() : null,
                p.isUnitTypeListing() ? p.getUnitsAvailable() : null,
                p.isUnitTypeListing() ? p.getUnitsTotal() : null,
                p.isUnitTypeListing() ? p.getConstructionStatus() : null,
                p.isUnit() ? publicDevelopments.unitDetail(p) : null,
                amenitiesFor(p));
    }

    /**
     * What the place comes with — the listing's own, and its typology's when it is a unit.
     *
     * <p>Both levels, because a buyer does not distinguish them: a flat's balcony is recorded on the flat
     * and the block's borehole on the kind of home, and a list showing one and not the other describes half
     * a home. Deduplicated by code, because a feature recorded in both places is still one amenity.
     *
     * <p>Ordered by the catalogue's own sort order, which puts water and power first — in this market they
     * are asked about before the bedrooms.
     */
    private List<PropertyDtos.PublicAmenity> amenitiesFor(Property p) {
        java.util.List<Long> unitTypeIds = p.getUnitTypeId() == null
                ? java.util.List.of() : java.util.List.of(p.getUnitTypeId());
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        features.findForUnit(p.getId()).forEach(f -> codes.add(f.getFeatureCode()));
        for (Long typeId : unitTypeIds) {
            features.findForUnitType(typeId).forEach(f -> codes.add(f.getFeatureCode()));
        }
        if (codes.isEmpty()) return java.util.List.of();

        return featureConfigs.findLive().stream()
                .filter(c -> codes.contains(c.getCode()))
                .map(c -> new PropertyDtos.PublicAmenity(
                        c.getCode(), c.getName(), c.getCategory(), c.getIcon()))
                .toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
