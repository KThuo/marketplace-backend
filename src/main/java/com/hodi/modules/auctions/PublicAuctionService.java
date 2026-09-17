package com.hodi.modules.auctions;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.infra.storage.StorageService;
import com.hodi.modules.auctions.AuctionDtos.AuctionFacets;
import com.hodi.modules.auctions.AuctionDtos.PropertyTypeCount;
import com.hodi.modules.auctions.AuctionDtos.PublicLot;
import com.hodi.modules.auctions.AuctionDtos.PublicLotSearchRequest;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The auction catalogue a member of the public reads (M6, BRD UC006).
 *
 * <h2>A separate surface, not a filtered one</h2>
 *
 * <p>This class never touches {@code properties} and {@code PublicPropertyService} never touches
 * {@code auction_lots}. UC006 asks that auction stock be absent from buyer search; here it is absent because
 * the marketplace reads a different table, which is a guarantee rather than a habit.
 *
 * <p>Every query starts from scheduled-and-live, the same construction the marketplace uses for listings —
 * and the reserve price is not on {@link PublicLot} at all, so no code path can leak the one number an
 * auction depends on nobody knowing.
 */
@Service
@RequiredArgsConstructor
public class PublicAuctionService {

    private final AuctionLotRepository repository;
    private final StorageService storage;
    private final EntityManager entityManager;

    @Transactional(readOnly = true)
    public PagedResponse<PublicLot> search(PublicLotSearchRequest request) {
        Specification<AuctionLot> spec = SearchSpecs.allOf(
                scheduled(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("county", blankToNull(request.getCounty())),
                SearchSpecs.eq("propertyType", blankToNull(request.getPropertyType())),
                guideUnder(request.getMaxGuide()));

        // Soonest first. An auction catalogue is a diary — what is coming up is the ordering everybody wants.
        var page = repository.findAll(spec, request.toPageable(Sort.by(Sort.Direction.ASC, "auctionDate")));
        return PagedResponse.from(page, this::toPublic);
    }

    @Transactional(readOnly = true)
    public PublicLot findByReference(String reference) {
        return toPublic(repository.findPublicByReference(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Lot", reference)));
    }

    @Transactional(readOnly = true)
    public AuctionFacets facets() {
        return new AuctionFacets(repository.publicCounties(), countByType(), repository.countScheduled());
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** The only thing a stranger may see. Every public query starts here. */
    private Specification<AuctionLot> scheduled() {
        return (root, query, cb) -> cb.and(
                cb.equal(root.get("state"), AppConstant.LOT_SCHEDULED),
                cb.notEqual(root.get("status"), AppConstant.STATUS_DELETED));
    }

    private Specification<AuctionLot> guideUnder(BigDecimal max) {
        if (max == null) return null;
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("guidePrice"), max);
    }

    /**
     * Counts per property type.
     *
     * <p>Native for the same reason the marketplace's facets are: JPA cannot group by a column named at
     * runtime. The column name here is a literal in this file and never request input.
     */
    @SuppressWarnings("unchecked")
    private List<PropertyTypeCount> countByType() {
        List<Object[]> rows = entityManager.createNativeQuery(
                        "select property_type, count(*) from auction_lots "
                                + "where state = 'SCHEDULED' and status <> 5 "
                                + "group by 1 order by 2 desc, 1 asc")
                .getResultList();
        List<PropertyTypeCount> out = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            out.add(new PropertyTypeCount(String.valueOf(row[0]), ((Number) row[1]).longValue()));
        }
        return out;
    }

    private PublicLot toPublic(AuctionLot lot) {
        return new PublicLot(
                lot.getReference(), lot.getLotNumber(), lot.getTitle(), lot.getDescription(),
                lot.getPropertyType(), lot.getCounty(), lot.getTown(), lot.getEstate(),
                lot.getAddressLine(), lot.getLatitude(), lot.getLongitude(), lot.getTitleNumber(),
                lot.getPlotAreaAcres(), lot.getBedrooms(), lot.getGuidePrice(), lot.getCurrency(),
                lot.getDepositRequired(), lot.getAuctionDate(), lot.getVenue(),
                lot.getVenueLatitude(), lot.getVenueLongitude(), lot.getViewingNotes(),
                lot.getTerms(), lot.getAuctioneerName(), storage.urlFor(lot.getPrimaryImageKey()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
