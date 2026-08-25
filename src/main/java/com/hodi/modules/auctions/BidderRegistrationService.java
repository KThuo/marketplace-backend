package com.hodi.modules.auctions;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.RefGenerator;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.auctions.AuctionDtos.*;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
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

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Asking to be allowed to bid (M6).
 *
 * <h2>Registration, not bidding</h2>
 *
 * <p>The bidding happens in a room with a licensed auctioneer. What this records is that somebody asked to
 * be allowed in, what deposit they say they lodged, and the answer. Nothing here takes money and nothing
 * here places a bid — a platform that implied either would be making a promise about a legal process it does
 * not conduct.
 *
 * <h2>Keyed on the person</h2>
 *
 * <p>Like the shortlist, the consent store and affordability: registering to bid is something a natural
 * person does. Their own registrations are read through {@code /me} and nothing takes a user id.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BidderRegistrationService {

    private static final String REFERENCE_PREFIX = "BR";

    private final AuctionRegistrationRepository repository;
    private final AuctionLotRepository lots;
    private final UserRepository users;
    private final AuditService audit;

    // ── the bidder's side ─────────────────────────────────────────────────────

    @Transactional
    public RegistrationResponse register(RegisterRequest request) {
        Long userId = AuthContext.requireUserId();
        User bidder = users.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));

        // Public-and-upcoming only. Registering for a lot that has already gone under the hammer is not a
        // thing anybody means to do, and registering for a draft would be reaching a lot nobody published.
        AuctionLot lot = lots.findPublicByReference(AuctionService.trim(request.lotReference()))
                .orElseThrow(() -> new ResourceNotFoundException("Lot", request.lotReference()));
        if (!lot.isUpcoming()) {
            throw new HodiException("That auction has already taken place.", HttpStatus.CONFLICT);
        }

        repository.findLiveFor(userId, lot.getId()).ifPresent(existing -> {
            throw new HodiException(
                    "You have already registered for this lot — reference " + existing.getReference() + ".",
                    HttpStatus.CONFLICT);
        });

        AuctionRegistration registration = repository.save(AuctionRegistration.builder()
                .reference(nextReference())
                .lotId(lot.getId())
                .lotReference(lot.getReference())
                .lotTitle(lot.getTitle())
                .userId(userId)
                .bidderName(bidder.fullName())
                .bidderEmail(bidder.getEmail())
                .bidderPhone(AuctionService.blankToNull(request.contactPhone()) == null
                        ? bidder.getPhone() : request.contactPhone().trim())
                .idNumber(AuctionService.blankToNull(request.idNumber()))
                .depositReference(AuctionService.blankToNull(request.depositReference()))
                .createdBy(bidder.getUsername())
                .updatedBy(bidder.getUsername())
                .build());

        return toResponse(registration, lot);
    }

    @Transactional(readOnly = true)
    public PagedResponse<RegistrationResponse> mine(RegistrationListRequest request) {
        var page = repository.findMine(AuthContext.requireUserId(),
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(page, r -> toResponse(r, null));
    }

    @Transactional
    public RegistrationResponse withdraw(String reference) {
        AuctionRegistration registration = repository
                .findMineByReference(AuctionService.trim(reference), AuthContext.requireUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Registration", reference));
        if (!registration.isLive()) {
            throw new HodiException("That registration is already settled.", HttpStatus.CONFLICT);
        }
        registration.setState(AppConstant.BIDDER_WITHDRAWN);
        registration.setUpdatedBy(AuthContext.username());
        return toResponse(repository.save(registration), null);
    }

    // ── the seller's or lender's side ─────────────────────────────────────────

    /**
     * Who has asked to bid on this organisation's lots.
     *
     * <p>Scoped through the lot: a registration is visible to whoever brought the lot it is for, and to the
     * platform. Expressed as a subquery on the lot's principal columns rather than denormalised onto the
     * registration — the row already carries a lot id, and copying the principal onto it would be a second
     * copy of a fact that can change.
     */
    @Transactional(readOnly = true)
    public PagedResponse<RegistrationResponse> list(RegistrationListRequest request) {
        Specification<AuctionRegistration> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("state", AuctionService.blankToNull(request.getState())),
                SearchSpecs.eq("lotReference", AuctionService.blankToNull(request.getLotReference())),
                forMyLots());

        return PagedResponse.from(
                repository.findAll(spec, request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt"))),
                r -> toResponse(r, null));
    }

    /** Approve or refuse somebody, and record whether their deposit was confirmed. */
    @Transactional
    public RegistrationResponse decide(String reference, BidderDecisionRequest request) {
        AuctionRegistration registration = repository.findByReference(AuctionService.trim(reference))
                .orElseThrow(() -> new ResourceNotFoundException("Registration", reference));
        assertMyLot(registration);

        if (!registration.isLive()) {
            throw new HodiException("That registration is already settled.", HttpStatus.CONFLICT);
        }

        String decision = AuctionService.trim(request.decision()).toUpperCase();
        switch (decision) {
            case "APPROVE" -> registration.setState(AppConstant.BIDDER_APPROVED);
            case "REJECT" -> registration.setState(AppConstant.BIDDER_REJECTED);
            default -> throw new HodiException("Say whether you are approving or refusing.",
                    HttpStatus.BAD_REQUEST);
        }

        if (request.depositConfirmed() != null) {
            registration.setDepositConfirmed(request.depositConfirmed());
        }
        registration.setDecisionNote(AuctionService.blankToNull(request.note()));
        registration.setDecidedAt(OffsetDateTime.now());
        registration.setDecidedByUserId(AuthContext.userId());
        registration.setUpdatedBy(AuthContext.username());
        AuctionRegistration saved = repository.save(registration);

        audit.record(AppConstant.AUDIT_BIDDER_DECIDED, "AuctionRegistration", saved.getId(), null,
                saved.getReference() + " " + saved.getState() + " on " + saved.getLotReference());
        return toResponse(saved, null);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private Specification<AuctionRegistration> forMyLots() {
        UserPrincipal caller = AuthContext.require();
        if (caller.isPlatformStaff()) return null;
        return (root, query, cb) -> {
            var lot = query.subquery(Long.class);
            var lotRoot = lot.from(AuctionLot.class);
            List<Predicate> ors = new ArrayList<>(2);
            if (caller.getInstitutionId() != null) {
                ors.add(cb.equal(lotRoot.get("institutionId"), caller.getInstitutionId()));
            }
            if (caller.getTenantId() != null) {
                ors.add(cb.equal(lotRoot.get("tenantId"), caller.getTenantId()));
            }
            if (ors.isEmpty()) return cb.disjunction();
            lot.select(lotRoot.get("id")).where(cb.or(ors.toArray(new Predicate[0])));
            return root.get("lotId").in(lot);
        };
    }

    private void assertMyLot(AuctionRegistration registration) {
        UserPrincipal caller = AuthContext.require();
        if (caller.isPlatformStaff()) return;
        AuctionLot lot = lots.findById(registration.getLotId())
                .orElseThrow(() -> new ResourceNotFoundException("Lot", registration.getLotId()));
        boolean mine = (caller.getInstitutionId() != null
                        && caller.getInstitutionId().equals(lot.getInstitutionId()))
                || (caller.getTenantId() != null && caller.getTenantId().equals(lot.getTenantId()));
        if (!mine) {
            throw new HodiException("That registration is for another organisation's lot.",
                    HttpStatus.FORBIDDEN);
        }
    }

    private RegistrationResponse toResponse(AuctionRegistration r, AuctionLot known) {
        OffsetDateTime auctionDate = known != null
                ? known.getAuctionDate()
                : lots.findById(r.getLotId()).map(AuctionLot::getAuctionDate).orElse(null);

        return new RegistrationResponse(
                r.getReference(), r.getLotReference(), r.getLotTitle(), auctionDate,
                r.getBidderName(), r.getBidderEmail(), r.getBidderPhone(), r.getIdNumber(),
                r.getDepositReference(), r.isDepositConfirmed(), r.getState(), r.getDecisionNote(),
                r.getDecidedAt(), r.getCreatedAt());
    }

    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String reference = RefGenerator.getInstance().generate(REFERENCE_PREFIX);
            if (!repository.existsByReference(reference)) return reference;
        }
        throw new HodiException("Could not allocate a reference. Try again.",
                HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
