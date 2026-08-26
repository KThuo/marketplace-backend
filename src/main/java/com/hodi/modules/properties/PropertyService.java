package com.hodi.modules.properties;

import com.hodi.common.AppConstant;
import com.hodi.modules.agents.AgentProfile;
import com.hodi.modules.agents.AgentProfileRepository;
import com.hodi.modules.agents.AgentState;
import com.hodi.common.PagedResponse;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.infra.storage.StorageService;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.properties.PropertyDtos.PropertyListRequest;
import com.hodi.modules.properties.PropertyDtos.PropertyResponse;
import com.hodi.modules.properties.PropertyDtos.SavePropertyRequest;
import com.hodi.modules.properties.PropertyDtos.SubmitRequest;
import com.hodi.modules.tenants.Tenant;
import com.hodi.modules.tenants.TenantRepository;
import com.hodi.security.TenantScope;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * A seller's listings, from draft to live.
 *
 * <h2>Whose listing it is, is derived</h2>
 *
 * <p>A seller's staff create listings for their own organisation and nowhere else — the tenant comes from the
 * caller, never from the request, for the same reason it does when they create staff. Platform staff read
 * every listing and may take one down, but do not draft on somebody's behalf: a listing carries the seller's
 * name and their answer for what is in it.
 *
 * <h2>Publication is a Maker/Checker decision</h2>
 *
 * <p>Submitting moves a draft to {@code PENDING} and raises an approval request in the seller's own queue
 * (§3.2, BRD FR090). Somebody else at that organisation approves it — or the platform does, for a seller too
 * small to have two people — and only then does it become visible to a buyer. The person who submitted it
 * cannot be the one who approves it, which is the database's rule rather than this class's.
 *
 * <h2>Editing a live listing takes it back through the queue</h2>
 *
 * <p>A draft is the seller's own business. A live listing is a public offer somebody may be acting on, so
 * changing its price or its description returns it to {@code PENDING} and off the marketplace until it is
 * approved again. That is stricter than it needs to be for a typo and exactly right for a price, and the
 * alternative — trusting the editor to decide which is which — is the shape that gets a price changed under
 * an enquiry.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropertyService {

    private final PropertyRepository repository;
    private final AgentProfileRepository agents;
    private final com.hodi.modules.sellerops.CommissionService commissions;
    private final PropertyMediaRepository media;
    private final TenantRepository tenants;
    private final ApprovalService approvals;
    private final StorageService storage;
    private final AuditService audit;

    // ── reads ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<PropertyResponse> list(PropertyListRequest request) {
        Specification<Property> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                SearchSpecs.eq("listingState", blankToNull(request.getListingState())),
                SearchSpecs.eq("propertyType", blankToNull(request.getPropertyType())),
                SearchSpecs.eq("county", blankToNull(request.getCounty())),
                // The isolation. A seller sees their own; a lender sees their partnered sellers'; the
                // platform sees everything — all of it decided by TenantScope rather than by this method.
                TenantScope.restrict("tenantId"));
        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public PropertyResponse find(String hashId) {
        return toResponse(requireVisible(hashId));
    }

    // ── writes ────────────────────────────────────────────────────────────────

    @Transactional
    public PropertyResponse create(SavePropertyRequest request) {
        UserPrincipal caller = AuthContext.require();
        Long tenantId = caller.getTenantId();
        if (tenantId == null) {
            // Platform staff included: a listing carries a seller's name and their answer for what is in it,
            // so somebody outside every organisation has nobody to draft it for.
            throw new HodiException(
                    "Only a seller organisation can draft a listing.", HttpStatus.FORBIDDEN);
        }
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Organisation", tenantId));

        Property property = new Property();
        property.setTenantId(tenant.getId());
        property.setTenantName(tenant.getName());
        property.setReference(freshReference());
        property.setCurrency(tenant.getCurrency());
        property.setListingState(AppConstant.LISTING_DRAFT);
        property.setStatus(AppConstant.STATUS_ACTIVE);
        property.setStatusFlag(AppConstant.FLAG_ACTIVE);
        property.setCreatedBy(AuthContext.username());
        apply(property, request);
        applyOwnership(property, request, caller);

        Property saved = repository.save(property);
        audit.record(AppConstant.ACTION_CREATE, "Property", saved.getId(), null, snapshot(saved));
        log.info("Listing {} drafted by {} for {}", saved.getReference(), AuthContext.username(),
                tenant.getSlug());
        return toResponse(saved);
    }

    @Transactional
    public PropertyResponse update(String hashId, SavePropertyRequest request) {
        Property property = requireOwn(hashId);
        if (AppConstant.LISTING_SOLD.equals(property.getListingState())) {
            throw new HodiException("A sold listing cannot be edited.", HttpStatus.CONFLICT);
        }
        String before = snapshot(property);
        apply(property, request);
        applyOwnership(property, request, AuthContext.require());
        property.setStatus(AppConstant.STATUS_EDITED);
        property.setStatusFlag(AppConstant.FLAG_EDITED);
        property.setUpdatedBy(AuthContext.username());

        /*
         * A live listing that is edited comes off the marketplace until it is approved again.
         *
         * Somebody may be acting on what it said a minute ago. Deciding here that a typo is harmless and a
         * price is not would mean trusting the editor's own judgement about their own edit, which is the
         * judgement Maker/Checker exists because nobody should have to make about themselves.
         */
        boolean wasLive = property.isLive();
        if (wasLive) {
            property.setListingState(AppConstant.LISTING_PENDING);
            property.setPublishedAt(null);
        }

        Property saved = repository.save(property);
        if (wasLive) {
            approvals.submit(AppConstant.APPROVAL_ENTITY_PROPERTY, saved.getId(),
                    AppConstant.APPROVAL_ACTION_PUBLISH, saved.getTenantId(), null,
                    saved.getReference() + " — " + saved.getTitle(),
                    "Edited while live; needs re-approval before it goes back on the marketplace.");
        }
        audit.record(AppConstant.ACTION_UPDATE, "Property", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    /** Sends a draft for approval. The Maker half. */
    @Transactional
    public PropertyResponse submit(String hashId, SubmitRequest request) {
        Property property = requireOwn(hashId);
        if (property.isPending()) {
            throw new HodiException("That listing is already waiting for approval.",
                    HttpStatus.CONFLICT);
        }
        if (property.isLive()) {
            throw new HodiException("That listing is already live.", HttpStatus.CONFLICT);
        }
        assertReadyToPublish(property);

        String before = snapshot(property);
        property.setListingState(AppConstant.LISTING_PENDING);
        property.setUpdatedBy(AuthContext.username());
        Property saved = repository.save(property);

        approvals.submit(AppConstant.APPROVAL_ENTITY_PROPERTY, saved.getId(),
                AppConstant.APPROVAL_ACTION_PUBLISH, saved.getTenantId(), null,
                saved.getReference() + " — " + saved.getTitle(),
                request == null ? null : request.note());

        audit.record(AppConstant.ACTION_REQUEST, "Property", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    /**
     * Makes an approved listing live. Called by the approval handler inside the deciding transaction.
     *
     * <p>Not public API and not guarded: everything deciding whether this may happen has already run.
     */
    @Transactional
    public void applyPublication(Long propertyId) {
        Property property = repository.findById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing", propertyId));
        String before = snapshot(property);
        property.setListingState(AppConstant.LISTING_LIVE);
        property.setPublishedAt(OffsetDateTime.now());
        property.setWithdrawnAt(null);
        property.setWithdrawnReason(null);
        property.setUpdatedBy(AuthContext.username());
        Property saved = repository.save(property);
        audit.record(AppConstant.ACTION_APPROVE, "Property", saved.getId(), before, snapshot(saved));
        log.info("Listing {} is live", saved.getReference());
    }

    /** A rejected or sent-back listing returns to draft, where its author can act on the reason. */
    @Transactional
    public void applyRefusal(Long propertyId, String reason) {
        Property property = repository.findById(propertyId)
                .orElseThrow(() -> new ResourceNotFoundException("Listing", propertyId));
        String before = snapshot(property);
        property.setListingState(AppConstant.LISTING_DRAFT);
        property.setUpdatedBy(AuthContext.username());
        Property saved = repository.save(property);
        audit.record(AppConstant.ACTION_REVOKE, "Property", saved.getId(), before,
                "not published: " + (reason == null ? "no reason given" : reason));
    }

    @Transactional
    public PropertyResponse withdraw(String hashId, String reason) {
        Property property = requireManageable(hashId);
        if (!property.isLive() && !property.isPending()) {
            throw new HodiException("That listing is not on the marketplace.", HttpStatus.CONFLICT);
        }
        String before = snapshot(property);
        property.setListingState(AppConstant.LISTING_WITHDRAWN);
        property.setPublishedAt(null);
        property.setWithdrawnAt(OffsetDateTime.now());
        property.setWithdrawnReason(reason);
        property.setUpdatedBy(AuthContext.username());
        Property saved = repository.save(property);
        audit.record(AppConstant.ACTION_DEACTIVATE, "Property", saved.getId(), before,
                snapshot(saved));
        log.info("Listing {} withdrawn: {}", saved.getReference(), reason);
        return toResponse(saved);
    }

    @Transactional
    public PropertyResponse markSold(String hashId) {
        Property property = requireOwn(hashId);
        if (!property.isLive()) {
            throw new HodiException("Only a live listing can be marked sold.", HttpStatus.CONFLICT);
        }
        String before = snapshot(property);
        property.setListingState(AppConstant.LISTING_SOLD);
        property.setSoldAt(OffsetDateTime.now());
        // Off the marketplace, but the row stays: what sold and for how much is the question every report in
        // M15 is built on.
        property.setPublishedAt(null);
        property.setUpdatedBy(AuthContext.username());
        Property saved = repository.save(property);
        audit.record(AppConstant.ACTION_UPDATE, "Property", saved.getId(), before, snapshot(saved));
        // What the platform earned (M13). Raised from the rate in force now and copied onto the row; it
        // never throws back into here, because the sale is the fact and the invoice is a consequence.
        commissions.raiseFor(saved);
        return toResponse(saved);
    }

    @Transactional
    public void archive(String hashId) {
        Property property = requireOwn(hashId);
        if (property.isLive()) {
            throw new HodiException("Take it down from the marketplace first.", HttpStatus.CONFLICT);
        }
        String before = snapshot(property);
        property.setStatus(AppConstant.STATUS_DELETED);
        property.setStatusFlag(AppConstant.FLAG_DELETED);
        property.setUpdatedBy(AuthContext.username());
        repository.save(property);
        audit.record(AppConstant.ACTION_DELETE, "Property", property.getId(), before,
                snapshot(property));
    }

    // ── guards ────────────────────────────────────────────────────────────────

    /** Visible to this caller: their own, a partnered seller's, or anything if they are the platform. */
    private Property requireVisible(String hashId) {
        Property property = repository.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Listing", hashId));
        if (TenantScope.unrestricted()) return property;
        var visible = TenantScope.visibleIds();
        if (visible == null || !visible.contains(property.getTenantId())) {
            throw new ResourceNotFoundException("Listing", hashId);
        }
        return property;
    }

    /**
     * The caller's own organisation's listing.
     *
     * <p>Stricter than {@link #requireVisible}: a partnered lender may <em>read</em> a seller's portfolio, and
     * editing it would be a partnership granting write access to somebody else's business.
     */
    private Property requireOwn(String hashId) {
        Property property = requireVisible(hashId);
        Long tenantId = AuthContext.tenantId();
        if (tenantId == null || !tenantId.equals(property.getTenantId())) {
            throw new HodiException("That listing belongs to another organisation.",
                    HttpStatus.FORBIDDEN);
        }
        return property;
    }

    /**
     * The caller's own, or the platform's oversight.
     *
     * <p>Taking a listing down is the one write the platform does on a seller's behalf: something unlawful or
     * fraudulent has to be removable by whoever operates the marketplace, and waiting for the seller to agree
     * is not a moderation policy.
     */
    private Property requireManageable(String hashId) {
        Property property = requireVisible(hashId);
        if (AuthContext.require().isPlatformStaff()) return property;
        Long tenantId = AuthContext.tenantId();
        if (tenantId == null || !tenantId.equals(property.getTenantId())) {
            throw new HodiException("That listing belongs to another organisation.",
                    HttpStatus.FORBIDDEN);
        }
        return property;
    }

    /**
     * What a listing needs before anybody can be asked to approve it.
     *
     * <p>Checked at submission rather than at save, because a draft is allowed to be half-finished — that is
     * what a draft is. It is at the point of asking somebody else to put the organisation's name behind it
     * that the gaps stop being the author's own business.
     */
    private void assertReadyToPublish(Property property) {
        if (media.countForProperty(property.getId()) == 0) {
            throw new HodiException(
                    "Add at least one photograph — a listing without one is not a listing anybody clicks.",
                    HttpStatus.BAD_REQUEST);
        }
        if (property.getDescription() == null || property.getDescription().isBlank()) {
            throw new HodiException("Describe the property before submitting it.",
                    HttpStatus.BAD_REQUEST);
        }
        if (property.getTown() == null || property.getTown().isBlank()) {
            throw new HodiException("Say which town it is in — buyers search by it.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void apply(Property property, SavePropertyRequest request) {
        property.setTitle(request.title().trim());
        property.setDescription(blankToNull(request.description()));
        property.setPropertyType(request.propertyType().trim().toUpperCase());
        property.setListingType(request.listingType() == null
                ? AppConstant.LISTING_TYPE_SALE
                : request.listingType().trim().toUpperCase());
        property.setTenure(blankToNull(request.tenure()));
        property.setPrice(request.price());
        property.setServiceCharge(request.serviceCharge());
        property.setPriceNegotiable(Boolean.TRUE.equals(request.priceNegotiable()));
        property.setBedrooms(request.bedrooms());
        property.setBathrooms(request.bathrooms());
        property.setParkingSpaces(request.parkingSpaces());
        property.setFloorAreaSqm(request.floorAreaSqm());
        property.setPlotAreaAcres(request.plotAreaAcres());
        property.setYearBuilt(request.yearBuilt());
        property.setCounty(request.county().trim());
        property.setTown(blankToNull(request.town()));
        property.setEstate(blankToNull(request.estate()));
        property.setAddressLine(blankToNull(request.addressLine()));
        property.setLatitude(request.latitude());
        property.setLongitude(request.longitude());
        property.setGreenCertified(Boolean.TRUE.equals(request.greenCertified()));
        property.setGreenCertification(blankToNull(request.greenCertification()));
        property.setEnergyRating(blankToNull(request.energyRating()));
        property.setHasSolar(Boolean.TRUE.equals(request.hasSolar()));
        property.setHasBorehole(Boolean.TRUE.equals(request.hasBorehole()));
        property.setRainwaterHarvesting(Boolean.TRUE.equals(request.rainwaterHarvesting()));
    }

    /**
     * Whose property this is (M9, BRD FR161).
     *
     * <p>Asked of an agent and of nobody else. A seller organisation listing its own stock is not answering
     * this question, and defaulting them to {@code SELF} would put a claim on the row that nobody made.
     *
     * <p>The agent is taken from the caller, never from the request: a listing that named its own agent
     * would be a field somebody could point at somebody else.
     */
    private void applyOwnership(Property property, SavePropertyRequest request, UserPrincipal caller) {
        if (!caller.isAgent()) return;

        AgentProfile agent = agents.findFirstByUserIdOrderByIdDesc(caller.getUserId())
                .orElseThrow(() -> new HodiException(
                        "Your agent registration could not be found.", HttpStatus.FORBIDDEN));

        String ownership = request.listingOwnership() == null
                ? null : request.listingOwnership().trim().toUpperCase();
        if (!AgentState.OWNERSHIP_SELF.equals(ownership)
                && !AgentState.OWNERSHIP_CLIENT.equals(ownership)) {
            throw new HodiException(
                    "Say whether this is your own property or a client's.", HttpStatus.BAD_REQUEST);
        }
        if (AgentState.OWNERSHIP_CLIENT.equals(ownership)
                && (request.clientOwnerName() == null || request.clientOwnerName().isBlank())) {
            throw new HodiException("Name the client whose property this is.", HttpStatus.BAD_REQUEST);
        }

        property.setAgentProfileId(agent.getId());
        property.setListingOwnership(ownership);
        if (AgentState.OWNERSHIP_CLIENT.equals(ownership)) {
            property.setClientOwnerName(request.clientOwnerName().trim());
            property.setClientOwnerPhone(blankToNull(request.clientOwnerPhone()));
        } else {
            // Switching a listing from a client's to their own clears the client. Leaving the old name on it
            // would keep somebody's details against a property that is no longer theirs.
            property.setClientOwnerName(null);
            property.setClientOwnerPhone(null);
        }
    }

    /** A reference nobody holds yet. Retried, because the generator is random rather than sequential. */
    private String freshReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = RrnGenerator.generate("PR");
            if (!repository.existsByReference(candidate)) return candidate;
        }
        throw new HodiException("Could not allocate a listing reference — try again.",
                HttpStatus.CONFLICT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    PropertyResponse toResponse(Property p) {
        return new PropertyResponse(
                HashIdUtil.encodeId(p.getId()),
                p.getReference(),
                HashIdUtil.encodeId(p.getTenantId()),
                p.getTenantName(),
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
                p.getAddressLine(),
                p.getLatitude(),
                p.getLongitude(),
                p.isGreenCertified(),
                p.getGreenCertification(),
                p.getEnergyRating(),
                p.isHasSolar(),
                p.isHasBorehole(),
                p.isRainwaterHarvesting(),
                p.getListingState(),
                p.getPublishedAt(),
                p.getSoldAt(),
                p.getWithdrawnAt(),
                p.getWithdrawnReason(),
                storage.urlFor(p.getPrimaryImageKey()),
                (int) media.countForProperty(p.getId()),
                p.getListingOwnership(),
                agentOf(p).map(AgentProfile::getReference).orElse(null),
                agentOf(p).map(AgentProfile::getFullName).orElse(null),
                p.getClientOwnerName(),
                p.getClientOwnerPhone(),
                p.getStatus(),
                p.getStatusFlag(),
                p.getCreatedAt(),
                p.getCreatedBy());
    }

    private java.util.Optional<AgentProfile> agentOf(Property p) {
        return p.getAgentProfileId() == null
                ? java.util.Optional.empty()
                : agents.findById(p.getAgentProfileId());
    }

    private static String snapshot(Property p) {
        return ("{\"reference\":\"%s\",\"title\":\"%s\",\"price\":%s,\"state\":\"%s\","
                + "\"town\":\"%s\",\"tenantId\":%s}")
                .formatted(p.getReference(), p.getTitle(), String.valueOf(p.getPrice()),
                        p.getListingState(), String.valueOf(p.getTown()),
                        String.valueOf(p.getTenantId()));
    }
}
