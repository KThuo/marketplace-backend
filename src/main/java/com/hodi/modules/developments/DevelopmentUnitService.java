package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.developments.DevelopmentUnitDtos.BuildStatusRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.GeneratePreview;
import com.hodi.modules.developments.DevelopmentUnitDtos.GenerateUnitsRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.ReserveUnitRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.SaveUnitRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.SellUnitRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.UnitListRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.UnitResponse;
import com.hodi.security.hashid.HashIdUtil;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The unit inventory: two hundred doors, and what has become of each.
 *
 * <h2>Generating a block rather than clicking two hundred times</h2>
 *
 * <p>{@link #preview} computes the labels and shows them, {@link #generate} writes them. Splitting the two is
 * the point: a mistyped "units per floor" produces two hundred wrong door numbers, and undoing that is two
 * hundred deletions. So the labels are computed by {@link UnitLabels}, which is pure and tested, checked
 * against what already exists, and put in front of somebody before anything is saved.
 *
 * <h2>Sale state moves through this class only</h2>
 *
 * <p>Reserve, sell, release and hand over are the four transitions, and each one recounts. A unit's state is
 * what the availability figures are counted from, so a write that skipped the recount would leave a card
 * advertising something already sold — which is why nothing here saves a unit without going through
 * {@link DevelopmentInventoryService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevelopmentUnitService {

    /** Default hold, in days, when a reservation does not say. Two weeks is the usual sales-office term. */
    private static final int DEFAULT_HOLD_DAYS = 14;

    private final DevelopmentUnitRepository repository;
    private final DevelopmentUnitTypeRepository unitTypes;
    private final DevelopmentPhaseRepository phases;
    private final DevelopmentRepository developments;
    private final DevelopmentVisibility visibility;
    private final DevelopmentInventoryService inventory;
    private final PayCodeAllocator payCodes;
    private final AuditService audit;
    private final com.hodi.modules.bookings.UnitBookingRepository bookings;

    // ── reads ─────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<UnitResponse> list(String developmentHashId, UnitListRequest request) {
        Development development = requireVisible(developmentHashId);

        Long typeId = request.getUnitTypeHashId() == null || request.getUnitTypeHashId().isBlank()
                ? null : HashIdUtil.decodeId(request.getUnitTypeHashId());
        Long phaseId = request.getPhaseHashId() == null || request.getPhaseHashId().isBlank()
                ? null : HashIdUtil.decodeId(request.getPhaseHashId());

        // SearchSpecs.allOf skips the nulls that an unsupplied filter leaves; Spring's own allOf throws on
        // them. Field name first in eq() — reversed, it filters on a column named by the value.
        Specification<DevelopmentUnit> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.eq("developmentId", development.getId()),
                SearchSpecs.eq("unitTypeId", typeId),
                SearchSpecs.eq("phaseId", phaseId),
                SearchSpecs.eq("saleState", blankToNull(request.getSaleState())),
                SearchSpecs.eq("constructionStatus", blankToNull(request.getConstructionStatus())),
                SearchSpecs.eq("block", blankToNull(request.getBlock())),
                labelOrBuyerLike(request.getSearch()));

        var page = repository.findAll(spec, request.toPageable(
                Sort.by(Sort.Direction.ASC, "block", "floorNo", "unitLabel")));

        // Typology and phase names for every row, fetched once rather than per unit: an inventory page is
        // two hundred rows and a lookup each would be four hundred queries behind one screen.
        Map<Long, DevelopmentUnitType> typesById = unitTypes.findForDevelopment(development.getId())
                .stream().collect(Collectors.toMap(DevelopmentUnitType::getId, Function.identity()));
        Map<Long, String> phaseNames = phases.findForDevelopment(development.getId())
                .stream().collect(Collectors.toMap(DevelopmentPhase::getId, DevelopmentPhase::getName));

        // And the booking holding each unit, one query for the page rather than one per row.
        Map<Long, com.hodi.modules.bookings.UnitBooking> bookingsByUnit = liveBookings(
                page.getContent().stream().map(DevelopmentUnit::getId).toList());

        return PagedResponse.from(page, unit -> toResponse(unit, typesById, phaseNames, bookingsByUnit));
    }

    /**
     * The labels a generation would produce, and whether any of them clash.
     *
     * <p>Writes nothing. The screen shows this and asks.
     */
    @Transactional(readOnly = true)
    public GeneratePreview preview(String developmentHashId, GenerateUnitsRequest request) {
        Development development = requireVisible(developmentHashId);
        List<UnitLabels.Slot> slots = UnitLabels.expand(planOf(request));

        Set<String> existing = repository.labelsForDevelopment(development.getId()).stream()
                .map(label -> label.toUpperCase())
                .collect(Collectors.toSet());
        List<String> clashes = slots.stream()
                .map(UnitLabels.Slot::label)
                .filter(label -> existing.contains(label.toUpperCase()))
                .toList();

        return new GeneratePreview(slots.size(), slots.stream().map(UnitLabels.Slot::label).toList(),
                UnitLabels.hasDuplicates(slots), clashes);
    }

    // ── writes ────────────────────────────────────────────────────────────────

    @Transactional
    public UnitResponse create(String developmentHashId, SaveUnitRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        DevelopmentUnitType type = requireType(development, request.unitTypeHashId());
        Long phaseId = resolvePhase(development, request.phaseHashId());
        assertLabelFree(development.getId(), request.unitLabel(), -1L);

        DevelopmentUnit unit = DevelopmentUnit.builder()
                .developmentId(development.getId())
                .unitTypeId(type.getId())
                .phaseId(phaseId)
                .reference(nextReference())
                .payReference(payCodes.next())
                .currency(type.getCurrency())
                .createdBy(AuthContext.username())
                .build();
        apply(unit, request);

        DevelopmentUnit saved = repository.save(unit);
        inventory.recountUnitType(type.getId());
        audit.record(AppConstant.ACTION_CREATE, "DevelopmentUnit", saved.getId(), null, snapshot(saved));
        return toResponse(saved);
    }

    /**
     * Writes a block of units.
     *
     * <p>Refuses before writing anything if the plan would repeat a label or collide with one already there.
     * Half a block is worse than none: the person cannot tell which rows are theirs and the labels they wanted
     * are now partly taken.
     */
    @Transactional
    public int generate(String developmentHashId, GenerateUnitsRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        DevelopmentUnitType type = requireType(development, request.unitTypeHashId());
        Long phaseId = resolvePhase(development, request.phaseHashId());

        List<UnitLabels.Slot> slots = UnitLabels.expand(planOf(request));
        if (UnitLabels.hasDuplicates(slots)) {
            throw new HodiException(
                    "That pattern gives the same label to more than one unit. Add {n} or {i} to it.",
                    HttpStatus.BAD_REQUEST);
        }
        Set<String> existing = repository.labelsForDevelopment(development.getId()).stream()
                .map(String::toUpperCase).collect(Collectors.toCollection(HashSet::new));
        List<String> clashes = slots.stream().map(UnitLabels.Slot::label)
                .filter(label -> existing.contains(label.toUpperCase())).toList();
        if (!clashes.isEmpty()) {
            throw new HodiException(
                    "These labels are already in this development: " + String.join(", ",
                            clashes.size() > 8 ? clashes.subList(0, 8) : clashes)
                            + (clashes.size() > 8 ? " and " + (clashes.size() - 8) + " more" : ""),
                    HttpStatus.CONFLICT);
        }

        List<String> codes = payCodes.nextBatch(slots.size());
        List<DevelopmentUnit> batch = new ArrayList<>(slots.size());
        for (int i = 0; i < slots.size(); i++) {
            UnitLabels.Slot slot = slots.get(i);
            batch.add(DevelopmentUnit.builder()
                    .developmentId(development.getId())
                    .unitTypeId(type.getId())
                    .phaseId(phaseId)
                    .reference(nextReference())
                    .payReference(codes.get(i))
                    .unitLabel(slot.label())
                    .block(slot.block())
                    .floorNo(slot.floorNo())
                    .listPrice(request.listPrice())
                    .currency(type.getCurrency())
                    .saleState(AppConstant.UNIT_AVAILABLE)
                    .constructionStatus(AppConstant.BUILD_PLANNED)
                    .createdBy(AuthContext.username())
                    .build());
        }
        repository.saveAll(batch);
        inventory.recountUnitType(type.getId());

        audit.record(AppConstant.ACTION_CREATE, "DevelopmentUnit", type.getId(), null,
                "generated " + batch.size() + " units for " + type.getCode()
                        + " of " + development.getReference());
        log.info("Generated {} units for unit type {} of {}", batch.size(), type.getCode(),
                development.getReference());
        return batch.size();
    }

    @Transactional
    public UnitResponse update(String developmentHashId, String unitHashId, SaveUnitRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        DevelopmentUnit unit = require(development, unitHashId);
        DevelopmentUnitType type = requireType(development, request.unitTypeHashId());
        assertLabelFree(development.getId(), request.unitLabel(), unit.getId());

        String before = snapshot(unit);
        Long previousType = unit.getUnitTypeId();
        unit.setUnitTypeId(type.getId());
        unit.setPhaseId(resolvePhase(development, request.phaseHashId()));
        apply(unit, request);
        unit.setStatus(AppConstant.STATUS_EDITED);
        unit.setStatusFlag(AppConstant.FLAG_EDITED);
        unit.setUpdatedBy(AuthContext.username());

        DevelopmentUnit saved = repository.save(unit);
        // Both typologies are recounted when a unit moves between them, or the one it left keeps its figure.
        inventory.recountUnitType(type.getId());
        if (!previousType.equals(type.getId())) inventory.recountUnitType(previousType);

        audit.record(AppConstant.ACTION_UPDATE, "DevelopmentUnit", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    /** Holds a unit for a named buyer, until a date. */
    @Transactional
    public UnitResponse reserve(String developmentHashId, String unitHashId, ReserveUnitRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        DevelopmentUnit unit = require(development, unitHashId);
        assertNoLiveBooking(unit, "Change");
        if (unit.isSold()) {
            throw new HodiException("That unit is already sold.", HttpStatus.CONFLICT);
        }
        if (unit.isOnHold() && !unit.isHoldExpired()) {
            throw new HodiException("That unit is already held until "
                    + unit.getReservedUntil().toLocalDate() + ".", HttpStatus.CONFLICT);
        }
        if (AppConstant.UNIT_NOT_FOR_SALE.equals(unit.getSaleState())
                || AppConstant.UNIT_RETAINED.equals(unit.getSaleState())) {
            throw new HodiException("That unit is not on offer.", HttpStatus.CONFLICT);
        }

        String before = snapshot(unit);
        int days = request.holdDays() == null ? DEFAULT_HOLD_DAYS : request.holdDays();
        unit.setSaleState(AppConstant.UNIT_RESERVED);
        unit.setReservedAt(OffsetDateTime.now());
        unit.setReservedUntil(OffsetDateTime.now().plusDays(days));
        unit.setBuyerName(request.buyerName().trim());
        unit.setBuyerPhone(blankToNull(request.buyerPhone()));
        unit.setBuyerEmail(blankToNull(request.buyerEmail()));
        unit.setNotes(blankToNull(request.note()));
        unit.setUpdatedBy(AuthContext.username());

        DevelopmentUnit saved = repository.save(unit);
        inventory.recountUnitType(saved.getUnitTypeId());
        audit.record(AppConstant.ACTION_UPDATE, "DevelopmentUnit", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    /** Records a sale. The buyer, the price and the date are all required by the table itself. */
    @Transactional
    public UnitResponse sell(String developmentHashId, String unitHashId, SellUnitRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        DevelopmentUnit unit = require(development, unitHashId);
        // A booked unit is sold by completing its booking, which checks the balance first. Selling it from
        // here would mark it sold with money still outstanding and no record of the terms.
        assertNoLiveBooking(unit, "Complete");
        if (unit.isSold()) {
            throw new HodiException("That unit is already sold.", HttpStatus.CONFLICT);
        }
        DevelopmentUnitType type = unitTypes.findById(unit.getUnitTypeId())
                .orElseThrow(() -> new ResourceNotFoundException("Unit type", unit.getUnitTypeId()));

        BigDecimal price = request.soldPrice() != null
                ? request.soldPrice()
                : unit.effectivePrice(type.getListPrice());
        if (price == null) {
            throw new HodiException("Say what it sold for — neither the unit nor its type has a price.",
                    HttpStatus.BAD_REQUEST);
        }

        String before = snapshot(unit);
        unit.setSaleState(AppConstant.UNIT_SOLD);
        unit.setSoldAt(OffsetDateTime.now());
        unit.setSoldPrice(price);
        unit.setBuyerName(request.buyerName().trim());
        unit.setBuyerPhone(blankToNull(request.buyerPhone()));
        unit.setBuyerEmail(blankToNull(request.buyerEmail()));
        unit.setReservedUntil(null);
        if (request.note() != null && !request.note().isBlank()) unit.setNotes(request.note().trim());
        unit.setUpdatedBy(AuthContext.username());

        DevelopmentUnit saved = repository.save(unit);
        inventory.recountUnitType(saved.getUnitTypeId());
        audit.record(AppConstant.ACTION_UPDATE, "DevelopmentUnit", saved.getId(), before, snapshot(saved));
        log.info("Unit {} of {} sold", saved.getUnitLabel(), development.getReference());
        return toResponse(saved);
    }

    /**
     * Puts a held unit back on offer.
     *
     * <p>Clears the buyer's details with it. A released unit carrying the last enquirer's name and phone number
     * is personal data kept for no reason, and the next person to look at the row would read it as a claim.
     */
    @Transactional
    public UnitResponse release(String developmentHashId, String unitHashId) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        DevelopmentUnit unit = require(development, unitHashId);
        // Releasing a booked unit would free it while the booking still claimed it. Cancel the booking.
        assertNoLiveBooking(unit, "Cancel");
        if (unit.isSold()) {
            throw new HodiException("A sold unit cannot be released.", HttpStatus.CONFLICT);
        }
        String before = snapshot(unit);
        unit.setSaleState(AppConstant.UNIT_AVAILABLE);
        unit.setReservedAt(null);
        unit.setReservedUntil(null);
        unit.setBuyerName(null);
        unit.setBuyerPhone(null);
        unit.setBuyerEmail(null);
        unit.setBuyerUserId(null);
        unit.setUpdatedBy(AuthContext.username());

        DevelopmentUnit saved = repository.save(unit);
        inventory.recountUnitType(saved.getUnitTypeId());
        audit.record(AppConstant.ACTION_UPDATE, "DevelopmentUnit", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    /** Moves a unit's build status. The dates the table insists on are filled in where they are implied. */
    @Transactional
    public UnitResponse setBuildStatus(String developmentHashId, String unitHashId,
                                       BuildStatusRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        DevelopmentUnit unit = require(development, unitHashId);
        String status = request.constructionStatus().trim().toUpperCase();
        String before = snapshot(unit);

        unit.setConstructionStatus(status);
        if (AppConstant.BUILD_COMPLETE.equals(status) || AppConstant.BUILD_HANDED_OVER.equals(status)) {
            unit.setCompletedOn(request.completedOn() != null ? request.completedOn() : LocalDate.now());
        }
        if (AppConstant.BUILD_HANDED_OVER.equals(status)) {
            unit.setHandedOverOn(request.handedOverOn() != null ? request.handedOverOn() : LocalDate.now());
        }
        unit.setUpdatedBy(AuthContext.username());

        DevelopmentUnit saved = repository.save(unit);
        inventory.recountUnitType(saved.getUnitTypeId());
        audit.record(AppConstant.ACTION_UPDATE, "DevelopmentUnit", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public void archive(String developmentHashId, String unitHashId) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayWriteUnits(development, caller);

        DevelopmentUnit unit = require(development, unitHashId);
        if (unit.isSold()) {
            throw new HodiException(
                    "A sold unit cannot be removed — it is the record of a sale.", HttpStatus.CONFLICT);
        }
        String before = snapshot(unit);
        unit.setStatus(AppConstant.STATUS_DELETED);
        unit.setStatusFlag(AppConstant.FLAG_DELETED);
        unit.setUpdatedBy(AuthContext.username());
        DevelopmentUnit saved = repository.save(unit);

        inventory.recountUnitType(saved.getUnitTypeId());
        audit.record(AppConstant.ACTION_DELETE, "DevelopmentUnit", saved.getId(), before, snapshot(saved));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private UnitLabels.Plan planOf(GenerateUnitsRequest request) {
        return new UnitLabels.Plan(request.count(), blankToNull(request.block()),
                request.firstFloor(), request.unitsPerFloor(), request.labelPattern());
    }

    private void apply(DevelopmentUnit unit, SaveUnitRequest request) {
        unit.setUnitLabel(request.unitLabel().trim());
        unit.setBlock(blankToNull(request.block()));
        unit.setFloorNo(request.floorNo());
        unit.setDoorNo(blankToNull(request.doorNo()));
        unit.setListPrice(request.listPrice());
        unit.setNotes(blankToNull(request.notes()));
        if (request.constructionStatus() != null && !request.constructionStatus().isBlank()) {
            String status = request.constructionStatus().trim().toUpperCase();
            unit.setConstructionStatus(status);
            if ((AppConstant.BUILD_COMPLETE.equals(status)
                    || AppConstant.BUILD_HANDED_OVER.equals(status))
                    && unit.getCompletedOn() == null) {
                unit.setCompletedOn(request.completedOn() != null ? request.completedOn() : LocalDate.now());
            }
            if (AppConstant.BUILD_HANDED_OVER.equals(status) && unit.getHandedOverOn() == null) {
                unit.setHandedOverOn(LocalDate.now());
            }
        }
    }

    private void assertLabelFree(Long developmentId, String label, Long exceptId) {
        if (repository.countWithLabel(developmentId, label.trim(), exceptId) > 0) {
            throw new HodiException("Another unit here is already called " + label.trim() + ".",
                    HttpStatus.CONFLICT);
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

    /**
     * Refuses to touch a unit that a booking is holding.
     *
     * <p>Two writers of one field is how a unit ends up sold with no booking, or booked while showing
     * available. The boundary drawn: a unit with a live booking belongs to {@code BookingService}, and this
     * simple path — a name taken over the phone, no money, no schedule — is for units without one.
     *
     * <p>The message names the booking and its buyer, because "you cannot do that" on a screen that shows an
     * available unit is an answer nobody can act on.
     */
    private void assertNoLiveBooking(DevelopmentUnit unit, String action) {
        bookings.findLiveForUnit(unit.getId()).ifPresent(booking -> {
            throw new HodiException(unit.getUnitLabel() + " is booked under " + booking.getReference()
                    + " by " + booking.getBuyerName() + ". " + action + " it through that booking instead.",
                    HttpStatus.CONFLICT);
        });
    }

    private DevelopmentUnit require(Development development, String unitHashId) {
        DevelopmentUnit unit = repository.findById(HashIdUtil.decodeId(unitHashId))
                .orElseThrow(() -> new ResourceNotFoundException("Unit", unitHashId));
        if (!unit.getDevelopmentId().equals(development.getId())) {
            throw new ResourceNotFoundException("Unit", unitHashId);
        }
        return unit;
    }

    private DevelopmentUnitType requireType(Development development, String typeHashId) {
        DevelopmentUnitType type = unitTypes.findById(HashIdUtil.decodeId(typeHashId))
                .orElseThrow(() -> new ResourceNotFoundException("Unit type", typeHashId));
        if (!type.getDevelopmentId().equals(development.getId())) {
            throw new ResourceNotFoundException("Unit type", typeHashId);
        }
        return type;
    }

    /** A phase id, checked to belong to this development — the composite key would allow the wrong one. */
    private Long resolvePhase(Development development, String phaseHashId) {
        if (phaseHashId == null || phaseHashId.isBlank()) return null;
        DevelopmentPhase phase = phases.findById(HashIdUtil.decodeId(phaseHashId))
                .orElseThrow(() -> new ResourceNotFoundException("Phase", phaseHashId));
        if (!phase.getDevelopmentId().equals(development.getId())) {
            throw new ResourceNotFoundException("Phase", phaseHashId);
        }
        return phase.getId();
    }

    /** Free text across the label and the buyer, which is what somebody at a sales desk searches by. */
    private Specification<DevelopmentUnit> labelOrBuyerLike(String search) {
        if (search == null || search.isBlank()) return null;
        String like = "%" + search.trim().toLowerCase() + "%";
        return (root, query, cb) -> {
            List<Predicate> ors = new ArrayList<>(4);
            ors.add(cb.like(cb.lower(root.get("unitLabel")), like));
            ors.add(cb.like(cb.lower(root.get("reference")), like));
            ors.add(cb.like(cb.lower(root.get("payReference")), like));
            ors.add(cb.like(cb.lower(cb.coalesce(root.get("buyerName"), "")), like));
            return cb.or(ors.toArray(new Predicate[0]));
        };
    }

    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = RrnGenerator.generate("UN");
            if (!repository.existsByReference(candidate)) return candidate;
        }
        throw new HodiException("Could not allocate a unit reference. Try again.", HttpStatus.CONFLICT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private UnitResponse toResponse(DevelopmentUnit unit) {
        Optional<DevelopmentUnitType> type = unitTypes.findById(unit.getUnitTypeId());
        Map<Long, DevelopmentUnitType> one = type
                .map(t -> Map.of(t.getId(), t)).orElse(Map.of());
        Map<Long, String> phaseName = unit.getPhaseId() == null ? Map.of()
                : phases.findById(unit.getPhaseId())
                        .map(p -> Map.of(p.getId(), p.getName())).orElse(Map.of());
        return toResponse(unit, one, phaseName, liveBookings(List.of(unit.getId())));
    }

    /** The live booking against each of these units, keyed by unit id. Empty in, empty out. */
    private Map<Long, com.hodi.modules.bookings.UnitBooking> liveBookings(List<Long> unitIds) {
        if (unitIds.isEmpty()) return Map.of();
        return bookings.findLiveForUnits(unitIds).stream().collect(Collectors.toMap(
                com.hodi.modules.bookings.UnitBooking::getUnitId, Function.identity(),
                // Cannot happen — the partial unique index permits one live booking per unit — but a merge
                // function is required and throwing here would be a 500 for a state the database forbids.
                (a, b) -> a));
    }

    private UnitResponse toResponse(DevelopmentUnit u, Map<Long, DevelopmentUnitType> types,
                                    Map<Long, String> phaseNames,
                                    Map<Long, com.hodi.modules.bookings.UnitBooking> bookingsByUnit) {
        DevelopmentUnitType type = types.get(u.getUnitTypeId());
        com.hodi.modules.bookings.UnitBooking booking = bookingsByUnit.get(u.getId());
        return new UnitResponse(
                HashIdUtil.encodeId(u.getId()),
                u.getReference(),
                u.getPayReference(),
                u.getUnitLabel(),
                u.getBlock(),
                u.getFloorNo(),
                u.getDoorNo(),
                type == null ? null : type.getCode(),
                type == null ? null : type.getName(),
                u.getPhaseId() == null ? null : phaseNames.get(u.getPhaseId()),
                u.getListPrice(),
                u.effectivePrice(type == null ? null : type.getListPrice()),
                u.getCurrency(),
                u.getSaleState(),
                u.getConstructionStatus(),
                u.getCompletedOn(),
                u.getHandedOverOn(),
                u.isHoldExpired(),
                u.getReservedUntil(),
                u.getBuyerName(),
                u.getBuyerPhone(),
                u.getBuyerEmail(),
                u.getSoldPrice(),
                u.getSoldAt(),
                u.getNotes(),
                booking == null ? null : HashIdUtil.encodeId(booking.getId()),
                booking == null ? null : booking.getReference(),
                booking == null ? null : booking.getState());
    }

    private String snapshot(DevelopmentUnit u) {
        return "label=" + u.getUnitLabel() + ", state=" + u.getSaleState()
                + ", build=" + u.getConstructionStatus()
                + ", buyer=" + (u.getBuyerName() == null ? "-" : u.getBuyerName())
                + ", sold=" + u.getSoldPrice();
    }
}
