package com.hodi.modules.auctions;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.RefGenerator;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.infra.storage.StorageService;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.auctions.AuctionDtos.*;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Auction lots, from the inside (M6, BRD UC006).
 *
 * <h2>The isolation is structural</h2>
 *
 * <p>Nothing in this class touches {@code properties}, and nothing in the marketplace touches
 * {@code auction_lots}. That is the whole of UC006's requirement: a buyer searching for a house cannot be
 * shown a repossession, not because every query remembers to exclude one but because the two surfaces read
 * different tables.
 *
 * <h2>Who sees a lot before it is published</h2>
 *
 * <p>The principal who brought it — a lender realising security, or a seller consigning stock — and the
 * platform. The same shape as the valuation scope, and for the same reason: the column that decides is on
 * this table, so the rule lives beside it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuctionService {

    private static final String REFERENCE_PREFIX = "AU";

    private final AuctionLotRepository lots;
    private final AuctioneerRepository auctioneers;
    private final AuctionRegistrationRepository registrations;
    private final StorageService storage;
    private final AuditService audit;

    // ── reads ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<LotResponse> list(LotListRequest request) {
        Specification<AuctionLot> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("state", blankToNull(request.getState())),
                SearchSpecs.eq("county", blankToNull(request.getCounty())),
                SearchSpecs.eq("propertyType", blankToNull(request.getPropertyType())),
                mine());

        var page = lots.findAll(spec, request.toPageable(
                Sort.by(Sort.Direction.ASC, "auctionDate")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public LotResponse find(String reference) {
        return toResponse(load(reference));
    }

    @Transactional(readOnly = true)
    public AuctionCounts counts() {
        return new AuctionCounts(lots.countScheduled(), 0);
    }

    // ── writes ────────────────────────────────────────────────────────────────

    /**
     * Creates a lot.
     *
     * <p>The principal is whichever organisation the caller belongs to — never a parameter, and never the
     * platform: somebody has to be selling, and the platform is not. That is the same rule valuations
     * follow, and it means a lot always has an owner who can be asked about it.
     */
    @Transactional
    public LotResponse create(SaveLotRequest request) {
        UserPrincipal caller = AuthContext.require();
        if (caller.getTenantId() == null && caller.getInstitutionId() == null) {
            throw new HodiException(
                    "A lot is brought by the lender or the seller selling it.", HttpStatus.FORBIDDEN);
        }

        AuctionLot lot = AuctionLot.builder()
                .reference(nextReference())
                .institutionId(caller.getInstitutionId())
                .institutionName(caller.getInstitutionName())
                .tenantId(caller.getTenantId())
                .tenantName(caller.getTenantName())
                .createdBy(caller.getUsername())
                .updatedBy(caller.getUsername())
                .build();
        apply(lot, request);
        return toResponse(lots.save(lot));
    }

    @Transactional
    public LotResponse update(String reference, SaveLotRequest request) {
        AuctionLot lot = loadOwn(reference);
        if (AppConstant.LOT_SOLD.equals(lot.getState())) {
            throw new HodiException("A sold lot cannot be edited.", HttpStatus.CONFLICT);
        }
        apply(lot, request);
        lot.setUpdatedBy(AuthContext.username());
        return toResponse(lots.save(lot));
    }

    /**
     * Publishes a lot into the catalogue, or takes it back out.
     *
     * <p>Publication is a public notice of a sale, so it checks what a notice needs: a date in the future, a
     * venue, and an auctioneer whose licence has not lapsed. The licence check happens here rather than only
     * when the auctioneer was added, because a licence expires on its own — and a sale conducted under a
     * lapsed one can be set aside.
     */
    @Transactional
    public LotResponse setPublished(String reference, boolean publish) {
        AuctionLot lot = loadOwn(reference);

        if (!publish) {
            lot.setState(AppConstant.LOT_WITHDRAWN);
            lot.setUpdatedBy(AuthContext.username());
            AuctionLot saved = lots.save(lot);
            audit.record(AppConstant.AUDIT_LOT_PUBLISHED, "AuctionLot", saved.getId(), null,
                    saved.getReference() + " withdrawn from the catalogue");
            return toResponse(saved);
        }

        if (lot.getAuctionDate() == null || lot.getAuctionDate().isBefore(OffsetDateTime.now())) {
            throw new HodiException("Set an auction date in the future before publishing.",
                    HttpStatus.BAD_REQUEST);
        }
        if (lot.getVenue() == null || lot.getVenue().isBlank()) {
            throw new HodiException("A published lot needs a venue — bidders have to know where to go.",
                    HttpStatus.BAD_REQUEST);
        }
        if (lot.getAuctioneerId() == null) {
            throw new HodiException("Name the auctioneer before publishing.", HttpStatus.BAD_REQUEST);
        }
        Auctioneer auctioneer = auctioneers.findById(lot.getAuctioneerId())
                .orElseThrow(() -> new ResourceNotFoundException("Auctioneer", lot.getAuctioneerId()));
        if (!auctioneer.isLicensed()) {
            throw new HodiException(
                    auctioneer.getName() + "'s licence"
                            + (auctioneer.getLicenceExpiresOn() == null
                                    ? " is not on file" : " expired on " + auctioneer.getLicenceExpiresOn())
                            + ". A sale conducted under a lapsed licence can be set aside.",
                    HttpStatus.CONFLICT);
        }

        lot.setState(AppConstant.LOT_SCHEDULED);
        lot.setPublishedAt(OffsetDateTime.now());
        lot.setUpdatedBy(AuthContext.username());
        AuctionLot saved = lots.save(lot);

        audit.record(AppConstant.AUDIT_LOT_PUBLISHED, "AuctionLot", saved.getId(), null,
                saved.getReference() + " scheduled for " + saved.getAuctionDate()
                        + " under " + auctioneer.getName());
        return toResponse(saved);
    }

    /** What the lot fetched, or that it did not sell. */
    @Transactional
    public LotResponse recordResult(String reference, ResultRequest request) {
        AuctionLot lot = loadOwn(reference);
        if (!AppConstant.LOT_SCHEDULED.equals(lot.getState())
                && !AppConstant.LOT_POSTPONED.equals(lot.getState())) {
            throw new HodiException("That lot is not at auction.", HttpStatus.CONFLICT);
        }

        String outcome = trim(request.outcome()).toUpperCase();
        switch (outcome) {
            case "SOLD" -> {
                if (request.soldPrice() == null || request.soldPrice().signum() <= 0) {
                    throw new HodiException("What did it fetch?", HttpStatus.BAD_REQUEST);
                }
                lot.setState(AppConstant.LOT_SOLD);
                lot.setSoldPrice(request.soldPrice());
                lot.setSoldAt(OffsetDateTime.now());
            }
            case "UNSOLD" -> lot.setState(AppConstant.LOT_UNSOLD);
            case "POSTPONED" -> lot.setState(AppConstant.LOT_POSTPONED);
            case "WITHDRAWN" -> lot.setState(AppConstant.LOT_WITHDRAWN);
            default -> throw new HodiException(
                    "Say whether it sold, did not sell, was postponed or was withdrawn.",
                    HttpStatus.BAD_REQUEST);
        }

        lot.setOutcomeNote(blankToNull(request.note()));
        lot.setUpdatedBy(AuthContext.username());
        AuctionLot saved = lots.save(lot);

        audit.record(AppConstant.AUDIT_LOT_RESULT, "AuctionLot", saved.getId(), null,
                saved.getReference() + " " + saved.getState()
                        + (saved.getSoldPrice() == null ? "" : " at " + saved.getSoldPrice()));
        return toResponse(saved);
    }

    @Transactional
    public LotResponse setPhoto(String reference, MultipartFile file) {
        AuctionLot lot = loadOwn(reference);
        StorageService.Stored stored = storage.store(file, "auctions");
        lot.setPrimaryImageKey(stored.key());
        lot.setUpdatedBy(AuthContext.username());
        return toResponse(lots.save(lot));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /** Readable: the principal who brought it, or the platform. */
    private AuctionLot load(String reference) {
        AuctionLot lot = lots.findByReference(trim(reference))
                .orElseThrow(() -> new ResourceNotFoundException("Lot", reference));
        UserPrincipal caller = AuthContext.require();
        if (caller.isPlatformStaff()) return lot;
        if (!isMine(lot, caller)) {
            throw new HodiException("That lot is not yours to see.", HttpStatus.FORBIDDEN);
        }
        return lot;
    }

    /**
     * Writable: the principal only.
     *
     * <p>The platform can read every lot and edit none, the same arrangement mortgage products have. A
     * support administrator changing a reserve or a date on somebody else's sale is not oversight.
     */
    private AuctionLot loadOwn(String reference) {
        AuctionLot lot = lots.findByReference(trim(reference))
                .orElseThrow(() -> new ResourceNotFoundException("Lot", reference));
        if (!isMine(lot, AuthContext.require())) {
            throw new HodiException("That lot belongs to another organisation.", HttpStatus.FORBIDDEN);
        }
        return lot;
    }

    private static boolean isMine(AuctionLot lot, UserPrincipal caller) {
        return (caller.getInstitutionId() != null
                        && caller.getInstitutionId().equals(lot.getInstitutionId()))
                || (caller.getTenantId() != null && caller.getTenantId().equals(lot.getTenantId()));
    }

    /** Rows this caller may see. Platform sees all; everybody else sees what they brought. */
    private Specification<AuctionLot> mine() {
        UserPrincipal caller = AuthContext.require();
        if (caller.isPlatformStaff()) return null;
        return (root, query, cb) -> {
            List<Predicate> ors = new ArrayList<>(2);
            if (caller.getInstitutionId() != null) {
                ors.add(cb.equal(root.get("institutionId"), caller.getInstitutionId()));
            }
            if (caller.getTenantId() != null) {
                ors.add(cb.equal(root.get("tenantId"), caller.getTenantId()));
            }
            if (ors.isEmpty()) return cb.disjunction();
            return cb.or(ors.toArray(new Predicate[0]));
        };
    }

    private void apply(AuctionLot lot, SaveLotRequest request) {
        lot.setLotNumber(blankToNull(request.lotNumber()));
        lot.setTitle(request.title().trim());
        lot.setDescription(blankToNull(request.description()));
        lot.setPropertyType(request.propertyType().trim().toUpperCase());
        lot.setCounty(blankToNull(request.county()));
        lot.setTown(blankToNull(request.town()));
        lot.setEstate(blankToNull(request.estate()));
        lot.setAddressLine(blankToNull(request.addressLine()));
        lot.setLatitude(request.latitude());
        lot.setLongitude(request.longitude());
        lot.setTitleNumber(blankToNull(request.titleNumber()));
        lot.setPlotAreaAcres(request.plotAreaAcres());
        lot.setBedrooms(request.bedrooms());
        lot.setGuidePrice(request.guidePrice());
        lot.setReservePrice(request.reservePrice());
        lot.setDepositRequired(request.depositRequired());
        lot.setAuctionDate(request.auctionDate());
        lot.setVenue(blankToNull(request.venue()));
        lot.setViewingNotes(blankToNull(request.viewingNotes()));
        lot.setTerms(blankToNull(request.terms()));

        if (request.auctioneerReference() != null && !request.auctioneerReference().isBlank()) {
            Auctioneer auctioneer = auctioneers.findByReference(request.auctioneerReference().trim())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Auctioneer", request.auctioneerReference()));
            lot.setAuctioneerId(auctioneer.getId());
            lot.setAuctioneerName(auctioneer.getName());
        }

        // The CHECK says the same. This is what makes the refusal readable.
        if (lot.getReservePrice() != null && lot.getGuidePrice() != null
                && lot.getReservePrice().compareTo(lot.getGuidePrice()) < 0) {
            throw new HodiException(
                    "A reserve below the guide price would publish a figure you would accept less than.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    LotResponse toResponse(AuctionLot lot) {
        String auctioneerReference = lot.getAuctioneerId() == null ? null
                : auctioneers.findById(lot.getAuctioneerId()).map(Auctioneer::getReference).orElse(null);

        return new LotResponse(
                lot.getReference(), lot.getLotNumber(), lot.getTitle(), lot.getDescription(),
                lot.getPropertyType(), lot.getCounty(), lot.getTown(), lot.getEstate(),
                lot.getAddressLine(), lot.getLatitude(), lot.getLongitude(), lot.getTitleNumber(),
                lot.getPlotAreaAcres(), lot.getBedrooms(), lot.getGuidePrice(), lot.getReservePrice(),
                lot.getCurrency(), lot.getDepositRequired(), lot.getAuctionDate(), lot.getVenue(),
                lot.getViewingNotes(), lot.getTerms(), auctioneerReference, lot.getAuctioneerName(),
                lot.getInstitutionName() != null ? lot.getInstitutionName() : lot.getTenantName(),
                lot.getState(), lot.getPublishedAt(), lot.getSoldPrice(), lot.getSoldAt(),
                lot.getOutcomeNote(), storage.urlFor(lot.getPrimaryImageKey()),
                registrations.countApprovedForLot(lot.getId()), lot.getCreatedAt());
    }

    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = RefGenerator.getInstance().generate(REFERENCE_PREFIX);
            if (!lots.existsByReference(reference)) return reference;
        }
        throw new HodiException("Could not allocate a reference. Try again.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
