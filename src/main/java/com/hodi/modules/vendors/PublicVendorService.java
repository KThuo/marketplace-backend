package com.hodi.modules.vendors;

import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.infra.storage.StorageService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * The vendor directory, as a stranger sees it (M10).
 *
 * <p>Its own response records, for the reason the listing and the auction lot have their own: the internal
 * ones carry a KRA PIN, a business registration number, a decision note and a state, and a public record
 * that merely leaves them null is one somebody will eventually forget to leave null.
 *
 * <p>Only approved vendors and only live items. Enforced in the queries rather than filtered afterwards.
 */
@Service
@RequiredArgsConstructor
public class PublicVendorService {

    private final VendorProfileRepository vendors;
    private final CatalogueItemRepository items;
    private final VendorCategoryRepository categories;
    private final StorageService storage;

    /** What a buyer sees about a business. No registration number, no PIN, no lifecycle. */
    public record PublicVendor(
            String reference,
            String businessName,
            String categoryName,
            String counties,
            String about,
            String website,
            String logoUrl,
            String phone,
            String email,
            long liveItems) {}

    /** What a buyer sees about one thing on offer. */
    public record PublicItem(
            String reference,
            String vendorReference,
            String vendorName,
            String categoryCode,
            String categoryName,
            String title,
            String description,
            BigDecimal price,
            boolean priceFrom,
            String currency,
            String unit,
            String priceNote,
            Short leadTimeDays,
            String counties,
            String imageUrl) {}

    public record VendorDetail(PublicVendor vendor, List<PublicItem> catalogue) {}

    public record DirectoryFacets(List<CategoryFacet> categories, long vendorCount, long itemCount) {}

    public record CategoryFacet(String code, String name, String icon, long vendorCount) {}

    @Getter
    @Setter
    public static class PublicSearchRequest extends PagedDataRequest {
        private String categoryCode;
        private String county;
    }

    @Transactional(readOnly = true)
    public PagedResponse<PublicVendor> searchVendors(PublicSearchRequest request) {
        Long categoryId = categoryIdFor(request.getCategoryCode());
        Specification<VendorProfile> spec = SearchSpecs.allOf(
                (root, query, cb) -> cb.and(
                        cb.equal(root.get("state"), VendorState.APPROVED),
                        cb.notEqual(root.get("status"), 5)),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("categoryId", categoryId),
                countyLike(request.getCounty()));
        var page = vendors.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.ASC, "businessName")));
        return PagedResponse.from(page, this::toPublic);
    }

    @Transactional(readOnly = true)
    public PagedResponse<PublicItem> searchItems(PublicSearchRequest request) {
        Long categoryId = categoryIdFor(request.getCategoryCode());
        Specification<CatalogueItem> spec = SearchSpecs.allOf(
                (root, query, cb) -> cb.and(
                        cb.equal(root.get("state"), VendorState.ITEM_LIVE),
                        cb.notEqual(root.get("status"), 5)),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("categoryId", categoryId),
                countyLike(request.getCounty()));
        var page = items.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "publishedAt")));
        return PagedResponse.from(page, this::toPublic);
    }

    @Transactional(readOnly = true)
    public VendorDetail find(String reference) {
        VendorProfile vendor = vendors.findPublicByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor", reference));
        List<PublicItem> catalogue = items.findLiveForVendor(vendor.getId()).stream()
                .map(this::toPublic)
                .toList();
        return new VendorDetail(toPublic(vendor), catalogue);
    }

    /**
     * The category tiles, with a count against each.
     *
     * <p>Counted rather than assumed, and categories with nobody in them are still shown: an empty category
     * is information — it tells the platform where the directory has gaps and tells a business what nobody
     * else is offering.
     */
    @Transactional(readOnly = true)
    public DirectoryFacets facets() {
        List<CategoryFacet> tiles = categories.findAllActive().stream()
                .map(c -> new CategoryFacet(c.getCode(), c.getName(), c.getIcon(),
                        vendors.count((root, query, cb) -> cb.and(
                                cb.equal(root.get("categoryId"), c.getId()),
                                cb.equal(root.get("state"), VendorState.APPROVED),
                                cb.notEqual(root.get("status"), 5)))))
                .toList();
        long vendorCount = tiles.stream().mapToLong(CategoryFacet::vendorCount).sum();
        long itemCount = items.count((root, query, cb) -> cb.and(
                cb.equal(root.get("state"), VendorState.ITEM_LIVE),
                cb.notEqual(root.get("status"), 5)));
        return new DirectoryFacets(tiles, vendorCount, itemCount);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private Long categoryIdFor(String code) {
        if (code == null || code.isBlank()) return null;
        return categories.findByCodeIgnoreCase(code.trim().toUpperCase()).map(VendorCategory::getId)
                .orElse(-1L);   // a code nobody holds matches nothing, rather than matching everything
    }

    /**
     * "Works in Kiambu" against a comma-separated list.
     *
     * <p>A LIKE rather than a join table: counties are a short free-text list a vendor maintains themselves,
     * and normalising them would mean a lookup table of forty-seven rows and a screen to maintain it, to
     * answer a filter that a trigram index already serves.
     */
    private <T> Specification<T> countyLike(String county) {
        if (county == null || county.isBlank()) return null;
        String needle = "%" + county.trim().toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("counties")), needle);
    }

    private PublicVendor toPublic(VendorProfile v) {
        return new PublicVendor(
                v.getReference(), v.getBusinessName(), v.getCategoryName(), v.getCounties(),
                v.getAbout(), v.getWebsite(), storage.urlFor(v.getLogoKey()),
                // Published on purpose: a directory whose entries cannot be telephoned is a list of names.
                v.getPhone(), v.getEmail(),
                items.countByVendorIdAndStateAndStatusNot(v.getId(), VendorState.ITEM_LIVE, 5));
    }

    private PublicItem toPublic(CatalogueItem i) {
        return new PublicItem(
                i.getReference(),
                vendors.findById(i.getVendorId()).map(VendorProfile::getReference).orElse(null),
                i.getVendorName(),
                i.getCategoryCode(),
                i.getCategoryName(), i.getTitle(), i.getDescription(),
                i.getPrice(), i.isPriceFrom(), i.getCurrency(), i.getUnit(), i.getPriceNote(),
                i.getLeadTimeDays(), i.getCounties(), storage.urlFor(i.getImageKey()));
    }
}
