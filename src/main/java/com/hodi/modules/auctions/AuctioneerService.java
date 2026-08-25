package com.hodi.modules.auctions;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.RefGenerator;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.auctions.AuctionDtos.AuctioneerResponse;
import com.hodi.modules.auctions.AuctionDtos.SaveAuctioneerRequest;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The auctioneers the platform will publish a sale under (M6).
 *
 * <p>Platform-only, for the reason the valuation panel is: a lapsed licence makes a sale voidable, and
 * whoever benefits from the sale should not be the one confirming the licence.
 */
@Service
@RequiredArgsConstructor
public class AuctioneerService {

    private static final String REFERENCE_PREFIX = "AC";

    private final AuctioneerRepository repository;
    private final AuctionLotRepository lots;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public PagedResponse<AuctioneerResponse> list(PagedDataRequest request) {
        Specification<Auctioneer> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()));
        return PagedResponse.from(
                repository.findAll(spec, request.toPageable(Sort.by(Sort.Direction.ASC, "name"))),
                this::toResponse);
    }

    @Transactional
    public AuctioneerResponse create(SaveAuctioneerRequest request) {
        Auctioneer auctioneer = Auctioneer.builder()
                .reference(nextReference())
                .createdBy(AuthContext.username())
                .updatedBy(AuthContext.username())
                .build();
        apply(auctioneer, request);
        Auctioneer saved = repository.save(auctioneer);
        audit.record(AppConstant.ACTION_CREATE, "Auctioneer", saved.getId(), null, saved.getName());
        return toResponse(saved);
    }

    @Transactional
    public AuctioneerResponse update(String reference, SaveAuctioneerRequest request) {
        Auctioneer auctioneer = load(reference);
        boolean renamed = !auctioneer.getName().equals(request.name().trim());
        apply(auctioneer, request);
        auctioneer.setUpdatedBy(AuthContext.username());
        auctioneer.setStatus(AppConstant.STATUS_EDITED);
        auctioneer.setStatusFlag(AppConstant.FLAG_EDITED);

        Auctioneer saved = repository.save(auctioneer);
        if (renamed) {
            // The label cache on every lot they are conducting. One writer, in the owning service.
            lots.renameAuctioneerLabel(saved.getId(), saved.getName());
        }
        return toResponse(saved);
    }

    @Transactional
    public AuctioneerResponse setActive(String reference, boolean active, String reason) {
        Auctioneer auctioneer = load(reference);
        auctioneer.setStatus(active ? AppConstant.STATUS_ACTIVE : AppConstant.STATUS_INACTIVE);
        auctioneer.setStatusFlag(active ? AppConstant.FLAG_ACTIVE : AppConstant.FLAG_INACTIVE);
        auctioneer.setUpdatedBy(AuthContext.username());
        // Lots already scheduled under them are left alone. Pulling a published auction notice because
        // somebody deactivated a record would be the platform cancelling a sale it is not party to.
        audit.record(active ? AppConstant.ACTION_ACTIVATE : AppConstant.ACTION_DEACTIVATE,
                "Auctioneer", auctioneer.getId(), null, reason);
        return toResponse(repository.save(auctioneer));
    }

    private Auctioneer load(String reference) {
        return repository.findByReference(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Auctioneer", reference));
    }

    private void apply(Auctioneer auctioneer, SaveAuctioneerRequest request) {
        auctioneer.setName(request.name().trim());
        auctioneer.setFirmName(blankToNull(request.firmName()));
        auctioneer.setLicenceNumber(blankToNull(request.licenceNumber()));
        auctioneer.setLicenceExpiresOn(request.licenceExpiresOn());
        auctioneer.setContactName(blankToNull(request.contactName()));
        auctioneer.setContactEmail(blankToNull(request.contactEmail()));
        auctioneer.setContactPhone(blankToNull(request.contactPhone()));
        auctioneer.setCounties(blankToNull(request.counties()));
    }

    private AuctioneerResponse toResponse(Auctioneer a) {
        return new AuctioneerResponse(
                a.getReference(), a.getName(), a.getFirmName(), a.getLicenceNumber(),
                a.getLicenceExpiresOn(), a.isLicensed(), a.getContactName(), a.getContactEmail(),
                a.getContactPhone(), a.getCounties(), a.getStatus(), a.getStatusFlag(), a.getCreatedAt());
    }

    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = RefGenerator.getInstance().generate(REFERENCE_PREFIX);
            if (!repository.existsByReference(reference)) return reference;
        }
        throw new HodiException("Could not allocate a reference. Try again.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
