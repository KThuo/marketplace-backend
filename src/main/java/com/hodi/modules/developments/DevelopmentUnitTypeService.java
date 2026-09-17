package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.infra.storage.StorageService;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.developments.DevelopmentUnitTypeDtos.SaveUnitTypeRequest;
import com.hodi.modules.developments.DevelopmentUnitTypeDtos.UnitTypeResponse;
import com.hodi.modules.media.MediaAssetRepository;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * A development's typologies, and how one becomes something a buyer can enquire about.
 *
 * <h2>Why a typology gets a listing</h2>
 *
 * <p>Ten tables already point at {@code properties(id)}: enquiries, viewings, offers, valuations, saved
 * listings, media, promotions, commissions, affordability checks and progress. A buyer asking about "the
 * two-beds at Highrise" has to land somewhere those all work, and making every one of them polymorphic to
 * avoid a row here would have been the larger change by a wide margin.
 *
 * <p>So {@link #listOnMarketplace} creates or relinks a {@code properties} row for the typology and hands it
 * to the ordinary listing lifecycle — draft, submit, approve, live. What the marketplace search *shows* is the
 * development's own card; the typology's listing is where a buyer arrives after clicking into it. One
 * listing per typology, enforced by a partial unique index rather than by this service hoping.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevelopmentUnitTypeService {

    private final DevelopmentUnitTypeRepository repository;
    private final DevelopmentRepository developments;
    private final DevelopmentUnitRepository units;
    private final PropertyRepository properties;
    private final MediaAssetRepository media;
    private final DevelopmentVisibility visibility;
    private final DevelopmentInventoryService inventory;
    private final DevelopmentPublication publication;
    private final TypologyListingMirror mirror;
    private final AuditService audit;
    private final AmenityService amenities;
    private final StorageService storage;

    @Transactional(readOnly = true)
    public List<UnitTypeResponse> list(String developmentHashId) {
        Development development = requireVisible(developmentHashId);
        return repository.findForDevelopment(development.getId()).stream().map(this::toResponse).toList();
    }

    @Transactional
    public UnitTypeResponse create(String developmentHashId, SaveUnitTypeRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        assertCodeFree(development.getId(), request.code(), -1L);

        DevelopmentUnitType type = DevelopmentUnitType.builder()
                .developmentId(development.getId())
                .reference(nextReference())
                .currency(development.getCurrency())
                .build();
        apply(type, request);
        type.setCreatedBy(AuthContext.username());

        DevelopmentUnitType saved = repository.save(type);
        // After the save: the rows key off an id the typology does not have until it exists.
        amenities.apply(AmenityService.Scope.UNIT_TYPE, saved.getId(), request.amenityCodes());
        inventory.recountUnitType(saved.getId());
        audit.record(AppConstant.ACTION_CREATE, "DevelopmentUnitType", saved.getId(), null, snapshot(saved));
        return toResponse(repository.findById(saved.getId()).orElse(saved));
    }

    @Transactional
    public UnitTypeResponse update(String developmentHashId, String typeHashId,
                                   SaveUnitTypeRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        DevelopmentUnitType type = require(development, typeHashId);
        assertCodeFree(development.getId(), request.code(), type.getId());

        String before = snapshot(type);
        String materialBefore = material(type);
        // Kept from before `apply`, so the mirror can tell a title it generated from one somebody wrote.
        String nameBefore = type.getName();
        apply(type, request);
        /*
         * What the bank has not seen must not be what the marketplace shows.
         *
         * Wider than the price, because a two-bed becoming a three-bed is the same kind of change: the card
         * a buyer decided on now describes a different home. Bedrooms, bathrooms, parking, floor area,
         * balcony area, service charge and the kind of property all count, and so does the price.
         *
         * Narrower than "anything": the code, the name, the description, the planned count and the sort
         * order are not here. Re-approving because somebody fixed a typo would make the rule the thing
         * people work around, and a rule people work around protects nobody.
         */
        String materialAfter = material(type);
        if (!materialBefore.equals(materialAfter)) {
            publication.requireReapproval(development.getId(),
                    changeNote(type, materialBefore, materialAfter));
        }
        type.setStatus(AppConstant.STATUS_EDITED);
        type.setStatusFlag(AppConstant.FLAG_EDITED);
        type.setUpdatedBy(AuthContext.username());

        DevelopmentUnitType saved = repository.save(type);
        /*
         * Amenities are deliberately not part of `material`.
         *
         * Adding a wardrobe to the description of a two-bed is not the price changing or a two-bed becoming
         * a three-bed, and sending a live project back to the bank because somebody ticked "fitted
         * wardrobes" is how a re-approval rule becomes the thing people work around.
         */
        amenities.apply(AmenityService.Scope.UNIT_TYPE, saved.getId(), request.amenityCodes());
        // A changed price moves the "from" figure on the typology, the development and the listing.
        inventory.recountUnitType(saved.getId());
        /*
         * And the rest of the edit reaches the card as well.
         *
         * The recount above carries availability and the "from" price, which is all the listing has ever
         * been told. Everything a buyer actually reads — the bedroom count, the description, the service
         * charge, what it comes with — stopped at the typology, so a two-bed corrected to a three-bed here
         * went on advertising two bedrooms on the marketplace.
         */
        mirror.toListing(saved, nameBefore);
        audit.record(AppConstant.ACTION_UPDATE, "DevelopmentUnitType", saved.getId(), before,
                snapshot(saved));
        return toResponse(repository.findById(saved.getId()).orElse(saved));
    }

    /**
     * Gives a typology a listing, so buyers can find and enquire about it.
     *
     * <p>Creates the row as a DRAFT and stops there: publishing goes through the approvals queue like any
     * other listing, and short-circuiting that here would mean a development's typology reaching the
     * marketplace by a route a listing cannot take.
     *
     * <p>Idempotent — calling it twice returns the listing that already exists rather than colliding with the
     * unique index, because a person clicking a button twice is not an error worth a 409.
     */
    /**
     * The facts about a typology a buyer decided on, as one string.
     *
     * <p>A fingerprint rather than eight comparisons, so adding a field to the record is one edit here
     * rather than a condition somebody forgets. {@code plain} normalises the money and the areas, because
     * {@code BigDecimal.equals} says 9500000 and 9500000.00 differ — and a save that re-read the same
     * price from the database would otherwise look like a change and pull a live project down.
     */
    static String material(DevelopmentUnitType t) {
        return String.join("|",
                String.valueOf(t.getPropertyType()),
                String.valueOf(t.getBedrooms()),
                String.valueOf(t.getBathrooms()),
                String.valueOf(t.getParkingSpaces()),
                plain(t.getFloorAreaSqm()),
                plain(t.getBalconyAreaSqm()),
                plain(t.getListPrice()),
                plain(t.getServiceCharge()));
    }

    static String plain(java.math.BigDecimal value) {
        return value == null ? "-" : value.stripTrailingZeros().toPlainString();
    }

    /** What the checker is told, in the words of whatever actually moved. */
    private static String changeNote(DevelopmentUnitType type, String before, String after) {
        String[] was = before.split("\\|", -1);
        String[] now = after.split("\\|", -1);
        String[] fields = {"the kind of property", "bedrooms", "bathrooms", "parking",
                "floor area", "balcony area", "the price", "the service charge"};
        java.util.List<String> moved = new java.util.ArrayList<>();
        for (int i = 0; i < fields.length && i < was.length && i < now.length; i++) {
            if (!was[i].equals(now[i])) moved.add(fields[i]);
        }
        return moved.isEmpty()
                ? type.getName() + " changed."
                : "On " + type.getName() + ", " + String.join(" and ", moved) + " changed.";
    }

    /** The marketplace card for one typology, drafted. The one definition both paths build. */
    private Property cardFor(Development development, DevelopmentUnitType type) {
        return Property.builder()
                // The card for this kind of home: one row, whatever the number of units behind it.
                .listingKind(AppConstant.LISTING_KIND_TYPOLOGY)
                .tenantId(development.getSellingTenantId())
                .tenantName(development.getSellingTenantName())
                .reference(nextListingReference())
                .title(type.getName() + " at " + development.getName())
                .description(type.getDescription() != null
                        ? type.getDescription() : development.getDescription())
                .propertyType(type.getPropertyType())
                .price(type.getFromPrice() != null ? type.getFromPrice() : type.getListPrice())
                .currency(type.getCurrency())
                .serviceCharge(type.getServiceCharge())
                .bedrooms(type.getBedrooms())
                .bathrooms(type.getBathrooms())
                .parkingSpaces(type.getParkingSpaces())
                .floorAreaSqm(type.getFloorAreaSqm())
                .county(development.getCounty())
                .town(development.getTown())
                .estate(development.getEstate())
                .addressLine(development.getAddressLine())
                .latitude(development.getLatitude())
                .longitude(development.getLongitude())
                .listingState(AppConstant.LISTING_DRAFT)
                .developmentId(development.getId())
                .unitTypeId(type.getId())
                .developmentName(development.getName())
                .createdBy(AuthContext.username())
                .build();
    }

    /**
     * Gives every priced unit type a marketplace card, if it has not got one.
     *
     * <p>Called from {@code DevelopmentService.submit}, so sending a project for approval also drafts the
     * cards buyers will actually see. They used to be made by hand, one "Put on the marketplace" click per
     * typology, and a project whose units were generated but whose cards were never created looked complete
     * from the inside and showed nothing on Browse — which is the failure this closes.
     *
     * <p>Silent about types with no price. A card has to carry a figure, and refusing the whole submission
     * because one of four typologies is unpriced would be the gate this is removing, wearing a hat.
     */
    @Transactional
    public int ensureListings(Development development) {
        int drafted = 0;
        for (DevelopmentUnitType type : repository.findForDevelopment(development.getId())) {
            if (type.getListPrice() == null && type.getFromPrice() == null) continue;
            if (properties.findByUnitTypeId(type.getId()).isPresent()) continue;
            properties.save(cardFor(development, type));
            inventory.recountUnitType(type.getId());
            drafted++;
        }
        return drafted;
    }

    @Transactional
    public String listOnMarketplace(String developmentHashId, String typeHashId) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        if (development.getSellingTenantId() == null) {
            throw new HodiException(
                    "Say which organisation is marketing this development before listing a unit type.",
                    HttpStatus.BAD_REQUEST);
        }
        DevelopmentUnitType type = require(development, typeHashId);

        Optional<Property> existing = properties.findByUnitTypeId(type.getId());
        if (existing.isPresent()) return existing.get().getReference();

        if (type.getListPrice() == null && type.getFromPrice() == null) {
            throw new HodiException("Give the unit type a price before listing it.",
                    HttpStatus.BAD_REQUEST);
        }

        Property listing = cardFor(development, type);
        Property saved = properties.save(listing);
        // Availability and construction status arrive through the one writer, not by being set here.
        inventory.recountUnitType(type.getId());

        audit.record(AppConstant.ACTION_CREATE, "Property", saved.getId(), null,
                "listing for unit type " + type.getCode() + " of " + development.getReference());
        log.info("Unit type {} of {} is listed as {}", type.getCode(), development.getReference(),
                saved.getReference());
        return saved.getReference();
    }

    /**
     * Archives a typology and its listing.
     *
     * <p>Refused while units exist under it, for the reason a phase is: a unit whose typology has gone is a
     * unit with no price, no bedroom count and nothing to render. The composite foreign key would still hold,
     * which is exactly why the service has to say no.
     */
    @Transactional
    public void archive(String developmentHashId, String typeHashId) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        DevelopmentUnitType type = require(development, typeHashId);
        long existing = units.countForUnitType(type.getId());
        if (existing > 0) {
            throw new HodiException(
                    "That unit type still has " + existing + " unit" + (existing == 1 ? "" : "s")
                            + " under it. Remove them first.", HttpStatus.CONFLICT);
        }

        String before = snapshot(type);
        type.setStatus(AppConstant.STATUS_DELETED);
        type.setStatusFlag(AppConstant.FLAG_DELETED);
        type.setUpdatedBy(AuthContext.username());
        repository.save(type);

        // The listing goes with it. Left behind it would be a live page for something that is not for sale,
        // and the partial unique index would block relisting the typology if it ever came back.
        properties.findByUnitTypeId(type.getId()).ifPresent(listing -> {
            listing.setStatus(AppConstant.STATUS_DELETED);
            listing.setStatusFlag(AppConstant.FLAG_DELETED);
            listing.setUpdatedBy(AuthContext.username());
            properties.save(listing);
            log.info("Listing {} archived with its unit type", listing.getReference());
        });

        inventory.recomputeDevelopment(development.getId());
        audit.record(AppConstant.ACTION_DELETE, "DevelopmentUnitType", type.getId(), before,
                snapshot(type));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void apply(DevelopmentUnitType type, SaveUnitTypeRequest request) {
        type.setCode(request.code().trim().toUpperCase());
        type.setName(request.name().trim());
        type.setDescription(blankToNull(request.description()));
        type.setPropertyType(request.propertyType().trim().toUpperCase());
        type.setBedrooms(request.bedrooms());
        type.setBathrooms(request.bathrooms());
        type.setParkingSpaces(request.parkingSpaces());
        type.setFloorAreaSqm(request.floorAreaSqm());
        type.setBalconyAreaSqm(request.balconyAreaSqm());
        type.setListPrice(request.listPrice());
        type.setServiceCharge(request.serviceCharge());
        if (request.plannedUnitCount() != null) type.setPlannedUnitCount(request.plannedUnitCount());
        if (request.sortOrder() != null) type.setSortOrder(request.sortOrder());
    }

    private void assertCodeFree(Long developmentId, String code, Long exceptId) {
        if (repository.countWithCode(developmentId, code.trim(), exceptId) > 0) {
            throw new HodiException("Another unit type here is already called " + code.trim().toUpperCase()
                    + ".", HttpStatus.CONFLICT);
        }
    }

    private Development requireVisible(String developmentHashId) {
        Development development = developments.findById(HashIdUtil.decodeId(developmentHashId))
                .orElseThrow(() -> new ResourceNotFoundException("Development", developmentHashId));
        if (!visibility.mayRead(development, AuthContext.require())) {
            throw new ResourceNotFoundException("Development", developmentHashId);
        }
        return development;
    }

    private DevelopmentUnitType require(Development development, String typeHashId) {
        DevelopmentUnitType type = repository.findById(HashIdUtil.decodeId(typeHashId))
                .orElseThrow(() -> new ResourceNotFoundException("Unit type", typeHashId));
        if (!type.getDevelopmentId().equals(development.getId())) {
            throw new ResourceNotFoundException("Unit type", typeHashId);
        }
        return type;
    }

    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = RrnGenerator.generate("UT");
            if (!repository.existsByReference(candidate)) return candidate;
        }
        throw new HodiException("Could not allocate a unit type reference. Try again.",
                HttpStatus.CONFLICT);
    }

    private String nextListingReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = RrnGenerator.generate("PR");
            if (!properties.existsByReference(candidate)) return candidate;
        }
        throw new HodiException("Could not allocate a listing reference. Try again.", HttpStatus.CONFLICT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private UnitTypeResponse toResponse(DevelopmentUnitType t) {
        Optional<Property> listing = properties.findByUnitTypeId(t.getId());
        return new UnitTypeResponse(
                HashIdUtil.encodeId(t.getId()),
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
                t.getListPrice(),
                t.getServiceCharge(),
                t.getCurrency(),
                t.getPlannedUnitCount(),
                t.getUnitsTotal(),
                t.getUnitsAvailable(),
                t.getUnitsReserved(),
                t.getUnitsSold(),
                t.getFromPrice(),
                t.getConstructionStatus(),
                t.getFloorPlanKey() == null ? null : storage.urlFor(t.getFloorPlanKey()),
                t.getPrimaryImageKey() == null ? null : storage.urlFor(t.getPrimaryImageKey()),
                t.getSortOrder(),
                listing.map(Property::getReference).orElse(null),
                listing.map(Property::getListingState).orElse(null),
                (int) media.countForOwner(AppConstant.MEDIA_OWNER_UNIT_TYPE, t.getId()),
                amenities.codesFor(AmenityService.Scope.UNIT_TYPE, t.getId()));
    }

    private String snapshot(DevelopmentUnitType t) {
        return "code=" + t.getCode() + ", name=" + t.getName() + ", beds=" + t.getBedrooms()
                + ", price=" + t.getListPrice() + ", planned=" + t.getPlannedUnitCount()
                + ", units=" + t.getUnitsTotal();
    }
}
