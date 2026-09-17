package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.infra.storage.StorageService;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.developments.DevelopmentDtos.DevelopmentListRequest;
import com.hodi.modules.developments.DevelopmentDtos.DevelopmentResponse;
import com.hodi.modules.developments.DevelopmentDtos.SaveDevelopmentRequest;
import com.hodi.modules.developments.DevelopmentDtos.SubmitRequest;
import com.hodi.modules.developments.DevelopmentDtos.WithdrawRequest;
import com.hodi.modules.banks.Bank;
import com.hodi.modules.banks.BankRepository;
import com.hodi.modules.tenants.Tenant;
import com.hodi.modules.tenants.TenantRepository;
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
import java.util.List;

/**
 * Developments, from draft to live — or to tracked-and-never-marketed.
 *
 * <h2>Two shapes of project, one table</h2>
 *
 * <p>A seller drafting a development for sale goes DRAFT → PENDING → LIVE through the same approvals queue a
 * listing uses. A bank recording a project it financed goes straight to PRIVATE and never appears anywhere
 * public. The difference is a state and a selling tenant, not a second table and not a boolean, so nothing has
 * to stay in step.
 *
 * <h2>Who may do what is not in here</h2>
 *
 * <p>{@link DevelopmentVisibility} holds it. Every read goes through its specification and every write through
 * one of its assertions, because a development cannot use {@code TenantScope} — a lending institution may own
 * one, and the bank has no visible-tenant set. That makes the scoping a rule to apply rather than a choke
 * point to pass through, which is exactly the kind of protection worth keeping in one tested place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevelopmentService {

    private final DevelopmentRepository repository;
    private final DevelopmentPhaseRepository phases;
    private final DevelopmentUnitTypeRepository unitTypes;
    private final DevelopmentUnitTypeService unitTypeService;
    private final DevelopmentCollaboratorRepository collaborators;
    private final DevelopmentVisibility visibility;
    private final DevelopmentInventoryService inventory;
    private final TenantRepository tenants;
    private final DevelopmentPublication publication;
    private final BankRepository institutions;
    private final ApprovalService approvals;
    private final AmenityService amenities;
    private final AuditService audit;
    private final StorageService storage;

    // ── reads ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<DevelopmentResponse> list(DevelopmentListRequest request) {
        UserPrincipal caller = AuthContext.require();
        /*
         * SearchSpecs.allOf, not Specification.allOf.
         *
         * The project's own helper skips nulls; Spring's rejects them. Every filter here is null when it was
         * not supplied and visibility.mine returns null for platform staff, so Spring's would have thrown for
         * an administrator opening the list — which is exactly what it did until a test called this method.
         */
        Specification<Development> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("listingState", blankToNull(request.getListingState())),
                SearchSpecs.eq("purpose", blankToNull(request.getPurpose())),
                SearchSpecs.eq("constructionStatus", blankToNull(request.getConstructionStatus())),
                SearchSpecs.eq("county", blankToNull(request.getCounty())),
                visibility.mine(caller));

        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional(readOnly = true)
    public DevelopmentResponse find(String hashId) {
        return toResponse(requireVisible(hashId));
    }

    // ── writes ────────────────────────────────────────────────────────────────

    /**
     * Drafts a development.
     *
     * <p>The owner is derived for anybody who has an organisation and named for anybody who has not — see
     * {@link #applyOwner}. It used to be derived always, and platform staff were refused outright on the
     * grounds that "somebody has to be building, and the platform is not". Both halves of that stopped
     * being true: the bank runs this platform and also builds, and its staff are platform staff carrying
     * no organisation of their own, so the people who would draft a bank-owned project were exactly the
     * ones being turned away.
     */
    @Transactional
    public DevelopmentResponse create(SaveDevelopmentRequest request) {
        UserPrincipal caller = AuthContext.require();

        Development development = Development.builder()
                .reference(nextReference())
                .listingState(AppConstant.LISTING_DRAFT)
                .build();

        applyOwner(development, request, caller);
        apply(development, request, caller);

        /*
         * A tenant-owned project markets itself unless told otherwise.
         *
         * applySellingTenant already defaults it from the caller, which covers a seller drafting their own.
         * It did not cover platform staff drafting on a seller's behalf — and the gap was expensive, because
         * a null selling organisation blocks BOTH submitting the project and putting a unit type on the
         * marketplace, with two different messages and no hint that they are the same missing field.
         *
         * A bank-owned project still has to say, because there the answer genuinely is somebody else.
         */
        if (development.getSellingTenantId() == null && development.getTenantId() != null) {
            development.setSellingTenantId(development.getTenantId());
            development.setSellingTenantName(development.getTenantName());
        }
        development.setCreatedBy(AuthContext.username());
        Development saved = repository.save(development);
        // After the save, because the amenity rows key off an id the project does not have until then.
        amenities.apply(AmenityService.Scope.DEVELOPMENT, saved.getId(), request.amenityCodes());
        // Its unit rows are properties too, and they carry the project's state, name and place.
        inventory.syncUnitRows(saved);

        audit.record(AppConstant.ACTION_CREATE, "Development", saved.getId(), null, snapshot(saved));
        log.info("Development {} drafted by {}", saved.getReference(), AuthContext.username());
        return toResponse(saved);
    }

    @Transactional
    public DevelopmentResponse update(String hashId, SaveDevelopmentRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(hashId);
        visibility.assertMayManage(development, caller);

        String before = snapshot(development);
        apply(development, request, caller);
        development.setStatus(AppConstant.STATUS_EDITED);
        development.setStatusFlag(AppConstant.FLAG_EDITED);
        development.setUpdatedBy(AuthContext.username());

        /*
         * A live development edited goes back for approval, exactly as a listing does.
         *
         * A project's name, location and unit plan are what a buyer decided on. Changing them silently while
         * the page stays live is the case the approvals queue exists for — and a private project has no
         * public face to take down, so it is left where it is.
         */
        boolean wasLive = development.isLive();
        Development saved = repository.save(development);
        amenities.apply(AmenityService.Scope.DEVELOPMENT, saved.getId(), request.amenityCodes());
        // Its unit rows are properties too, and they carry the project's state, name and place.
        inventory.syncUnitRows(saved);
        if (wasLive) {
            // Through the one method, so the typology cards come down with it. They used to stay live on a
            // project that had gone back to PENDING, which left the marketplace showing cards for a project
            // whose page was no longer there.
            publication.requireReapproval(saved.getId(),
                    "Edited while live; needs re-approval before it goes back on the marketplace.");
        }

        // The percentage may have been stated on a project with no phases.
        inventory.recomputeDevelopment(saved.getId());
        audit.record(AppConstant.ACTION_UPDATE, "Development", saved.getId(), before, snapshot(saved));
        return toResponse(repository.findById(saved.getId()).orElse(saved));
    }

    /** Sends a draft for approval. The Maker half. */
    @Transactional
    public DevelopmentResponse submit(String hashId, SubmitRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(hashId);
        visibility.assertMayManage(development, caller);

        if (development.isPrivate()) {
            throw new HodiException(
                    "That project is tracked rather than marketed. Give it a selling organisation first.",
                    HttpStatus.CONFLICT);
        }
        if (AppConstant.LISTING_PENDING.equals(development.getListingState())) {
            throw new HodiException("That development is already waiting for approval.",
                    HttpStatus.CONFLICT);
        }
        if (development.isLive()) {
            throw new HodiException("That development is already live.", HttpStatus.CONFLICT);
        }
        assertReadyToPublish(development);

        /*
         * Draft the cards buyers will see, here rather than by hand.
         *
         * Before this, a project could be created, have its units generated, pass every gate and go live —
         * and show nothing on Browse, because Browse shows typology cards and nobody had clicked "Put on
         * the marketplace" on each of them. The button stays for listing one early; nobody has to find it.
         */
        unitTypeService.ensureListings(development);

        String before = snapshot(development);
        development.setListingState(AppConstant.LISTING_PENDING);
        development.setUpdatedBy(AuthContext.username());
        Development saved = repository.save(development);
        // Its unit rows are properties too, and they carry the project's state, name and place.
        inventory.syncUnitRows(saved);

        approvals.submit(AppConstant.APPROVAL_ENTITY_DEVELOPMENT, saved.getId(),
                AppConstant.APPROVAL_ACTION_PUBLISH, DevelopmentPublication.ownerScopeId(saved), null,
                saved.getReference() + " — " + saved.getName(),
                request == null ? null : request.note());

        audit.record(AppConstant.ACTION_REQUEST, "Development", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    /**
     * What a development needs before it can go on the marketplace.
     *
     * <p>Checked at submit rather than at save, so a draft can be built up over several sittings — the same
     * reasoning {@code PropertyService.assertReadyToPublish} gives. The list is deliberately short: a name and
     * a town a buyer can find it by, at least one typology so there is something to buy, and somebody to
     * market it. Photographs are not on it, because a development's photographs may legitimately live on its
     * typologies.
     */
    private void assertReadyToPublish(Development development) {
        if (development.getSellingTenantId() == null) {
            throw new HodiException("Say which organisation is marketing this development.",
                    HttpStatus.BAD_REQUEST);
        }
        if (development.getTown() == null || development.getTown().isBlank()) {
            throw new HodiException("Add the town before publishing — a buyer searches by it.",
                    HttpStatus.BAD_REQUEST);
        }
        if (unitTypes.findForDevelopment(development.getId()).isEmpty()) {
            throw new HodiException(
                    "Add at least one unit type. A development with nothing to buy has nothing to publish.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    /**
     * Makes an approved development live. Called by the approval handler inside the deciding transaction.
     *
     * <p>Not public API and not guarded: everything deciding whether this may happen has already run.
     */
    @Transactional
    public void applyPublication(Long developmentId) {
        Development development = repository.findById(developmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Development", developmentId));
        String before = snapshot(development);
        development.setListingState(AppConstant.LISTING_LIVE);
        development.setPublishedAt(OffsetDateTime.now());
        development.setWithdrawnAt(null);
        development.setWithdrawnReason(null);
        development.setUpdatedBy(AuthContext.username());
        Development saved = repository.save(development);
        // Its unit rows are properties too, and they carry the project's state, name and place.
        inventory.syncUnitRows(saved);
        /*
         * And its typology cards, which are what the marketplace actually shows.
         *
         * They used to hold their own approval each: publishing one project with three typologies meant four
         * submit/approve round trips, three of them for rows nobody had drafted by hand — listOnMarketplace
         * creates them. A second queue for a decision the bank has just made on the thing they belong to is
         * a queue that only ever gets rubber-stamped.
         */
        publication.moveTypologies(saved, AppConstant.LISTING_LIVE, saved.getPublishedAt());
        audit.record(AppConstant.ACTION_APPROVE, "Development", saved.getId(), before, snapshot(saved));
        log.info("Development {} is live", saved.getReference());
    }

    /** Refused, and back to the drafter. */
    @Transactional
    public void applyRefusal(Long developmentId, String reason) {
        Development development = repository.findById(developmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Development", developmentId));
        String before = snapshot(development);
        development.setListingState(AppConstant.LISTING_DRAFT);
        development.setUpdatedBy(AuthContext.username());
        Development saved = repository.save(development);
        // Its unit rows are properties too, and they carry the project's state, name and place.
        inventory.syncUnitRows(saved);
        // The cards go back with it: a live typology card on a project that is no longer live is the
        // marketplace showing something the bank has just refused.
        publication.moveTypologies(saved, AppConstant.LISTING_DRAFT, null);
        audit.record(AppConstant.ACTION_UPDATE, "Development", saved.getId(), before, snapshot(saved));
        log.info("Development {} sent back: {}", saved.getReference(), reason);
    }

    @Transactional
    public DevelopmentResponse withdraw(String hashId, WithdrawRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(hashId);
        visibility.assertMayManage(development, caller);

        if (!development.isLive()
                && !AppConstant.LISTING_PENDING.equals(development.getListingState())) {
            throw new HodiException("Only a live or pending development can be withdrawn.",
                    HttpStatus.CONFLICT);
        }
        String before = snapshot(development);
        development.setListingState(AppConstant.LISTING_WITHDRAWN);
        development.setWithdrawnAt(OffsetDateTime.now());
        development.setWithdrawnReason(request.reason().trim());
        development.setUpdatedBy(AuthContext.username());
        Development saved = repository.save(development);
        // Its unit rows are properties too, and they carry the project's state, name and place.
        inventory.syncUnitRows(saved);
        audit.record(AppConstant.ACTION_DEACTIVATE, "Development", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    /**
     * Turns a development into a tracked-only project.
     *
     * <p>Clears the selling organisation, because a project that is not marketed has nobody marketing it — the
     * database says the same thing with a CHECK. Refused while live rather than silently pulling a page out
     * from under whoever is reading it: withdraw it first, which is a decision somebody makes deliberately.
     */
    @Transactional
    public DevelopmentResponse markPrivate(String hashId) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(hashId);
        visibility.assertMayManage(development, caller);

        if (development.isLive()) {
            throw new HodiException(
                    "Withdraw it from the marketplace before making it a tracked project.",
                    HttpStatus.CONFLICT);
        }
        String before = snapshot(development);
        development.setListingState(AppConstant.DEV_STATE_PRIVATE);
        development.setSellingTenantId(null);
        development.setSellingTenantName(null);
        development.setPublishedAt(null);
        development.setUpdatedBy(AuthContext.username());
        Development saved = repository.save(development);
        // Its unit rows are properties too, and they carry the project's state, name and place.
        inventory.syncUnitRows(saved);
        audit.record(AppConstant.ACTION_UPDATE, "Development", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    /**
     * Archives a development and everything under it.
     *
     * <p>The cascade is written here because there is none in the database: every foreign key in this module is
     * a plain reference and archiving is a status, so archiving only the parent would leave the marketplace
     * serving typology listings for a project that no longer exists.
     */
    @Transactional
    public void archive(String hashId) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(hashId);
        visibility.assertMayManage(development, caller);

        if (development.isLive()) {
            throw new HodiException("Withdraw it from the marketplace first.", HttpStatus.CONFLICT);
        }
        String before = snapshot(development);
        String who = AuthContext.username();

        for (DevelopmentUnitType type : unitTypes.findForDevelopment(development.getId())) {
            type.setStatus(AppConstant.STATUS_DELETED);
            type.setStatusFlag(AppConstant.FLAG_DELETED);
            type.setUpdatedBy(who);
            unitTypes.save(type);
        }
        for (DevelopmentPhase phase : phases.findForDevelopment(development.getId())) {
            phase.setStatus(AppConstant.STATUS_DELETED);
            phase.setStatusFlag(AppConstant.FLAG_DELETED);
            phase.setUpdatedBy(who);
            phases.save(phase);
        }
        development.setStatus(AppConstant.STATUS_DELETED);
        development.setStatusFlag(AppConstant.FLAG_DELETED);
        development.setUpdatedBy(who);
        Development saved = repository.save(development);
        // Its unit rows are properties too, and they carry the project's state, name and place.
        inventory.syncUnitRows(saved);

        audit.record(AppConstant.ACTION_DELETE, "Development", saved.getId(), before, snapshot(saved));
        log.info("Development {} archived with its phases and unit types", saved.getReference());
    }

    // ── internals ─────────────────────────────────────────────────────────────

    Development requireVisible(String hashId) {
        Development development = repository.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Development", hashId));
        if (!visibility.mayRead(development, AuthContext.require())) {
            // Not-found rather than forbidden: whether a development exists is itself information, and a
            // caller with no route to it should not learn that it does.
            throw new ResourceNotFoundException("Development", hashId);
        }
        return development;
    }

    /**
     * An A–G band, or nothing.
     *
     * <p>Same rule the listing keeps, and refused rather than dropped for the same reason: dropping a
     * value the seller typed tells them it was saved.
     */
    private static String energyBand(String requested) {
        String value = blankToNull(requested);
        if (value == null) return null;
        String band = value.trim().toUpperCase();
        if (!band.matches("[A-G]")) {
            throw new HodiException("An energy rating is a band from A to G.", HttpStatus.BAD_REQUEST);
        }
        return band;
    }

    private void apply(Development development, SaveDevelopmentRequest request, UserPrincipal caller) {
        development.setName(request.name().trim());
        development.setDescription(blankToNull(request.description()));
        development.setDevelopmentType(request.developmentType().trim().toUpperCase());
        if (request.purpose() != null && !request.purpose().isBlank()) {
            development.setPurpose(request.purpose().trim().toUpperCase());
        }
        development.setDeveloperName(blankToNull(request.developerName()));
        development.setCounty(blankToNull(request.county()));
        development.setTown(blankToNull(request.town()));
        development.setEstate(blankToNull(request.estate()));
        development.setAddressLine(blankToNull(request.addressLine()));
        development.setLatitude(request.latitude());
        development.setLongitude(request.longitude());
        development.setPlannedUnitCount(request.plannedUnitCount());
        development.setGreenCertified(Boolean.TRUE.equals(request.greenCertified()));
        development.setGreenCertification(blankToNull(request.greenCertification()));
        development.setEnergyRating(energyBand(request.energyRating()));
        development.setStartedOn(request.startedOn());
        development.setProjectedCompletionOn(request.projectedCompletionOn());
        /*
         * The money is written only by somebody who can see it.
         *
         * The form hides budget and facility from anyone without DEVELOPMENTS_FINANCE_VIEW, so a save from
         * them arrives with those fields empty — and applying the empties would wipe the bank's facility
         * every time a listing manager corrected a town name.
         */
        if (AuthContext.hasAuthority("DEVELOPMENTS_FINANCE_VIEW")) {
            development.setBudgetAmount(request.budgetAmount());
            development.setFacilityReference(blankToNull(request.facilityReference()));
            development.setFacilityAmount(request.facilityAmount());
        }

        applySellingTenant(development, request, caller);

        /*
         * A stated percentage is honoured only where there is nothing to derive one from.
         *
         * With phases the figure comes from them, and letting a request overwrite it would put a typed number
         * and a derived number in the same column with nothing to say which won. recomputeDevelopment runs
         * after this and will restate it from the phases if there are any.
         */
        if (request.percentComplete() != null
                && phases.findForDevelopment(development.getId() == null ? -1L : development.getId())
                        .isEmpty()) {
            development.setPercentComplete(request.percentComplete());
            development.setPercentBasis(AppConstant.PERCENT_BASIS_STATED);
        }
    }

    /**
     * Who owns the record: the one place the request's owner fields are ever read.
     *
     * <p>Derived for a caller who belongs somewhere, named by a caller who does not. A seller's own values
     * are ignored rather than refused — the shortest guarantee that one seller cannot assign a project to
     * another is for this method to never look at what they sent.
     *
     * <p>Platform staff say which kind, and name the organisation only when it is a seller. Saying
     * nothing is the common mistake and gets a sentence about what to choose rather than a constraint
     * violation from {@code ck_development_owner}, which is a worse way to learn it.
     *
     * <p>There is deliberately no third, owner-less state for "the platform built it". The bank has a row
     * in {@code banks} and {@code institution_id} has always pointed at it, so a project Co-op builds is
     * bank-owned. Inventing another ownership shape to say what an existing one already says would cost a
     * migration, a relaxed CHECK and a fourth branch in every visibility rule.
     */
    private void applyOwner(Development development, SaveDevelopmentRequest request,
                            UserPrincipal caller) {
        if (caller.getInstitutionId() != null) {
            development.setInstitutionId(caller.getInstitutionId());
            development.setInstitutionName(caller.getInstitutionName());
            return;
        }
        if (caller.getTenantId() != null) {
            development.setTenantId(caller.getTenantId());
            development.setTenantName(caller.getTenantName());
            return;
        }

        String kind = blankToNull(request.ownerKind());
        String tenantHash = blankToNull(request.ownerTenantHashId());

        if (AppConstant.DEV_OWNER_SELLER.equalsIgnoreCase(kind)) {
            if (tenantHash == null) {
                throw new HodiException("Choose the seller organisation this project belongs to.",
                        HttpStatus.BAD_REQUEST);
            }
            Tenant owner = tenants.findById(HashIdUtil.decodeId(tenantHash))
                    .filter(t -> !java.util.Objects.equals(t.getStatus(), AppConstant.STATUS_DELETED))
                    .orElseThrow(() -> new ResourceNotFoundException("Organisation", tenantHash));
            development.setTenantId(owner.getId());
            development.setTenantName(owner.getName());
            return;
        }

        if (AppConstant.DEV_OWNER_BANK.equalsIgnoreCase(kind)) {
            /*
             * Resolved here rather than named by the caller. There is one bank — it runs the platform —
             * and asking the client for its id would mean publishing a directory of banks to look it up
             * in, which is precisely the module that was retired.
             *
             * More than one is refused rather than guessed. A deployment that has somehow acquired a
             * second is not one this can pick for, and silently taking the first would attach a project
             * to whichever happened to be created earliest.
             */
            List<Bank> live = institutions.findByStatusNotOrderByNameAsc(AppConstant.STATUS_DELETED);
            if (live.isEmpty()) {
                throw new HodiException("No bank is set up to own a project yet.", HttpStatus.CONFLICT);
            }
            if (live.size() > 1) {
                throw new HodiException(
                        "There is more than one bank on this platform, so which one owns this project "
                                + "cannot be assumed.", HttpStatus.CONFLICT);
            }
            Bank owner = live.getFirst();
            development.setInstitutionId(owner.getId());
            development.setInstitutionName(owner.getName());
            return;
        }

        throw new HodiException("Say whose project this is — a seller organisation, or the bank.",
                HttpStatus.BAD_REQUEST);
    }

    /**
     * Sets who markets the development.
     *
     * <p>A seller drafting their own project is the selling organisation by default — asking them to name
     * themselves is a question with one answer. A bank has to say, because the answer is somebody else.
     */
    private void applySellingTenant(Development development, SaveDevelopmentRequest request,
                                    UserPrincipal caller) {
        if (request.sellingTenantHashId() != null && !request.sellingTenantHashId().isBlank()) {
            Long sellingTenantId = HashIdUtil.decodeId(request.sellingTenantHashId().trim());
            var tenant = tenants.findById(sellingTenantId)
                    .orElseThrow(() -> new ResourceNotFoundException("Organisation",
                            request.sellingTenantHashId()));
            development.setSellingTenantId(tenant.getId());
            development.setSellingTenantName(tenant.getName());
            return;
        }
        if (development.getSellingTenantId() == null && caller.getTenantId() != null) {
            development.setSellingTenantId(caller.getTenantId());
            development.setSellingTenantName(caller.getTenantName());
        }
    }

    /**
     * The organisation the approval queue scopes a request to.
     *
     * <p>A queue row carries a tenant id. An institution-owned development has none, and the approval is the
     * marketing organisation's business anyway — they are the ones publishing it.
     */
    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = RrnGenerator.generate("DV");
            if (!repository.existsByReference(candidate)) return candidate;
        }
        throw new HodiException("Could not allocate a development reference. Try again.",
                HttpStatus.CONFLICT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private DevelopmentResponse toResponse(Development d) {
        // Budget and facility are the finance permission's, not the module's: a contractor granted progress
        // rights on a bank's project reads the project without reading the bank's exposure.
        boolean money = AuthContext.hasAuthority("DEVELOPMENTS_FINANCE_VIEW");
        return new DevelopmentResponse(
                HashIdUtil.encodeId(d.getId()),
                d.getReference(),
                d.getName(),
                d.getDescription(),
                d.getDevelopmentType(),
                d.getPurpose(),
                d.isInstitutionOwned() ? AppConstant.DEV_OWNER_BANK : AppConstant.DEV_OWNER_SELLER,
                d.principalName(),
                d.getDeveloperName(),
                d.getSellingTenantName(),
                d.getCounty(),
                d.getTown(),
                d.getEstate(),
                d.getAddressLine(),
                d.getLatitude(),
                d.getLongitude(),
                d.getPlannedUnitCount(),
                d.getUnitsTotal(),
                d.getUnitsAvailable(),
                d.getUnitsReserved(),
                d.getUnitsSold(),
                d.getFromPrice(),
                d.getToPrice(),
                d.getCurrency(),
                d.getConstructionStatus(),
                d.getPercentComplete(),
                d.getPercentBasis(),
                d.getStartedOn(),
                d.getProjectedCompletionOn(),
                d.getActualCompletionOn(),
                money ? d.getBudgetAmount() : null,
                money ? d.getFacilityReference() : null,
                money ? d.getFacilityAmount() : null,
                amenities.codesFor(AmenityService.Scope.DEVELOPMENT, d.getId()),
                d.isGreenCertified(),
                d.getGreenCertification(),
                d.getEnergyRating(),
                d.getListingState(),
                d.getPublishedAt(),
                d.getWithdrawnAt(),
                d.getWithdrawnReason(),
                d.getPrimaryImageKey() == null ? null : storage.urlFor(d.getPrimaryImageKey()),
                phases.findForDevelopment(d.getId()).size(),
                unitTypes.findForDevelopment(d.getId()).size(),
                (int) collaborators.findForDevelopment(d.getId()).stream()
                        .filter(DevelopmentCollaborator::isLive).count(),
                d.getStatus(),
                d.getStatusFlag(),
                d.getCreatedAt(),
                d.getCreatedBy());
    }

    private String snapshot(Development d) {
        return "name=" + d.getName() + ", state=" + d.getListingState()
                + ", purpose=" + d.getPurpose() + ", units=" + d.getUnitsTotal()
                + ", percent=" + d.getPercentComplete() + " (" + d.getPercentBasis() + ")";
    }

    /** The list a caller may reach, for another service that needs the same rule. */
    @Transactional(readOnly = true)
    public List<Development> visibleTo(UserPrincipal caller) {
        Specification<Development> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(), visibility.mine(caller));
        return repository.findAll(spec);
    }
}
