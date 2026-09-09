package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The single writer of every counted and derived figure on a development.
 *
 * <p>Eleven cached columns across four tables: the four unit tallies and the price on a typology, the four
 * tallies, both prices and the percentage on a development, and the label caches mirrored onto a typology's
 * listing — plus, since the cost ledger, a phase's committed and spent figures. Nothing else in the codebase
 * writes any of them.
 *
 * <p>That rule is the design. A counter with two writers is a counter that disagrees with the rows it counts,
 * and the disagreement surfaces as "the site says sixty available and there are fifty-eight" — which nobody
 * can debug afterwards because both numbers were written by something plausible. So every mutation of a unit,
 * a typology or a phase ends here, and the figures are recomputed from the rows rather than adjusted by a
 * delta. Recomputing is a grouped count over an indexed partial index; adjusting is a guess that drifts.
 *
 * <p>Why the figures are stored at all, given they are derived: a marketplace card reads them once per result,
 * and an aggregate per card is a join per card. The same reasoning that put {@code promotionBoost} on
 * {@code properties}.
 *
 * <h2>The arithmetic is not in here</h2>
 *
 * <p>{@link InventoryMaths} holds it, with no database in it and a test that pins all five derivation rules.
 * What is left in this class is reading rows and saving them, which is the part that is hard to get wrong.
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <p>It does not mark a typology's listing SOLD when the last unit goes. Availability is mirrored, so the
 * marketplace can and does stop offering a sold-out typology — but moving a listing to SOLD raises a
 * commission through {@code PropertyService}, and a commission per *typology* is not the same fact as a
 * commission per unit sold. That is a pricing decision belonging with the bookings slice, and inventing it
 * here would put a wrong invoice in front of a client. Recorded rather than left as a surprise.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevelopmentInventoryService {

    private final DevelopmentRepository developments;
    private final DevelopmentUnitTypeRepository unitTypes;
    private final DevelopmentUnitRepository units;
    private final DevelopmentPhaseRepository phases;
    private final PropertyRepository properties;
    private final DevelopmentExpenditureRepository expenditures;

    /**
     * Recounts every phase's committed and spent figures from the cost ledger.
     *
     * <p>The entry point after any write to {@code development_expenditures}. The two columns used to be
     * typed; now they are the sum of the phase's recorded lines, which is the same arrangement as every unit
     * tally here — one writer, recomputed rather than adjusted, so a voided line drops out on its own.
     */
    @Transactional
    public void recountPhaseMoney(Long developmentId) {
        for (DevelopmentPhase phase : phases.findForDevelopment(developmentId)) {
            phase.setCommittedAmount(expenditures.sumForPhase(phase.getId(), AppConstant.COST_COMMITTED));
            phase.setSpentAmount(expenditures.sumForPhase(phase.getId(), AppConstant.COST_SPENT));
            phases.save(phase);
        }
    }

    /**
     * Recounts one typology, then the development above it.
     *
     * <p>The entry point after any change to a unit. Cascading upward rather than asking the caller to
     * remember both: a typology whose figures moved always moves its development's, and a caller that forgets
     * the second call leaves a card showing yesterday's availability.
     */
    @Transactional
    public void recountUnitType(Long unitTypeId) {
        DevelopmentUnitType type = unitTypes.findById(unitTypeId).orElse(null);
        if (type == null) {
            // Not an error: a unit archived along with its typology recounts a typology that has gone.
            log.debug("Recount skipped — unit type {} is not there", unitTypeId);
            return;
        }
        applyTally(units.tallyForUnitType(unitTypeId), type::setUnitsTotal, type::setUnitsAvailable,
                type::setUnitsReserved, type::setUnitsSold);
        type.setFromPrice(unitTypes.cheapestAvailable(unitTypeId));
        type.setConstructionStatus(
                InventoryMaths.leastAdvanced(units.constructionStatusesForUnitType(unitTypeId)));
        unitTypes.save(type);

        mirrorToListing(type);
        recomputeDevelopment(type.getDevelopmentId());
    }

    /**
     * Recomputes a development's tallies, price range, percentage and build status.
     *
     * <p>Also the entry point after any change to a phase, because the percentage is derived from them.
     */
    @Transactional
    public void recomputeDevelopment(Long developmentId) {
        Development development = developments.findById(developmentId).orElse(null);
        if (development == null) {
            log.debug("Recompute skipped — development {} is not there", developmentId);
            return;
        }

        applyTally(units.tallyForDevelopment(developmentId), development::setUnitsTotal,
                development::setUnitsAvailable, development::setUnitsReserved, development::setUnitsSold);

        BigDecimal[] range = priceRange(developmentId);
        development.setFromPrice(range[0]);
        development.setToPrice(range[1]);

        InventoryMaths.Derived derived = InventoryMaths.derivePercent(
                figuresFor(developmentId), development.getPercentComplete());
        development.setPercentComplete(derived.percent());
        development.setPercentBasis(derived.basis());
        development.setConstructionStatus(InventoryMaths.rollUpStatus(derived.percent(),
                InventoryMaths.leastAdvanced(units.constructionStatusesForDevelopment(developmentId))));

        developments.save(development);
    }

    /**
     * Recounts every typology of a development, then the development.
     *
     * <p>For the changes that touch many units at once — a generated block of two hundred, a CSV import, a
     * bulk status change by phase. One pass per typology rather than one per unit: the recount reads the same
     * grouped count either way, so doing it per unit would be two hundred identical queries.
     */
    @Transactional
    public void recountAll(Long developmentId) {
        for (DevelopmentUnitType type : unitTypes.findForDevelopment(developmentId)) {
            applyTally(units.tallyForUnitType(type.getId()), type::setUnitsTotal, type::setUnitsAvailable,
                    type::setUnitsReserved, type::setUnitsSold);
            type.setFromPrice(unitTypes.cheapestAvailable(type.getId()));
            type.setConstructionStatus(
                    InventoryMaths.leastAdvanced(units.constructionStatusesForUnitType(type.getId())));
            unitTypes.save(type);
            mirrorToListing(type);
        }
        recomputeDevelopment(developmentId);
    }

    /**
     * Copies a typology's availability onto its listing, if it has one.
     *
     * <p>The card needs the project's name and "sixty of seventy" without two joins per result. A typology
     * with no listing is the ordinary case for a project that is tracked and not marketed, so an absent
     * listing is silence rather than a warning.
     */
    @Transactional
    public void mirrorToListing(DevelopmentUnitType type) {
        Optional<Property> listing = properties.findByUnitTypeId(type.getId());
        if (listing.isEmpty()) return;

        Property property = listing.get();
        property.setUnitsAvailable(type.getUnitsAvailable());
        property.setUnitsTotal(type.getUnitsTotal());
        property.setConstructionStatus(type.getConstructionStatus());
        property.setUnitTypeReference(type.getReference());
        developments.findById(type.getDevelopmentId()).ifPresent(d -> {
            property.setDevelopmentName(d.getName());
            // The reference too, so the card's link to the project needs no join.
            property.setDevelopmentReference(d.getReference());
        });

        /*
         * The price becomes what the cheapest available one actually costs.
         *
         * A typology listing is a group, and "from 9.5M" has to mean something a buyer can still buy. Units
         * are priced individually — a third-floor two-bed costs more than a first-floor one — so the
         * typology's own list price is a starting figure that stops being true as the cheap ones sell.
         *
         * Only when there is a from-price to use: a typology whose units carry no prices of their own keeps
         * whatever was set on the listing, which is the seller's figure and not ours to overwrite.
         */
        if (type.getFromPrice() != null) {
            property.setPrice(type.getFromPrice());
        }
        properties.save(property);
    }

    /**
     * Copies a development's state and details onto every one of its unit rows.
     *
     * <p>A unit is a property, and a property has a listing state; a unit's is its project's — LIVE while the
     * project is on the marketplace, WITHDRAWN when it is taken down, DRAFT otherwise — except that a sold unit
     * stays SOLD whatever happens to the project. The name and the place are copied too, so a unit row reads
     * correctly on its own. Called after any write to the development that changes those; nothing else writes
     * these columns on a unit row.
     */
    @Transactional
    public void syncUnitRows(Development development) {
        units.syncDetails(development.getId(), development.getName(), development.getReference(),
                development.getCounty(), development.getTown(), development.getEstate(),
                development.getAddressLine(), development.getLatitude(), development.getLongitude());
        units.syncListingState(development.getId(), unitListingStateFor(development),
                development.getPublishedAt());
    }

    /**
     * A unit row's listing state, given its own sale and its project's state.
     *
     * <p>SOLD is the unit's own fact and outlives the project's: a home sold in a project later taken down is
     * still sold. Everything else follows the project. Called by whoever changes a unit's sale state, in the
     * same transaction, so the two columns on the row never disagree.
     */
    public static void applyListingState(Property unit, Development development) {
        unit.setListingState(unit.isSoldUnit() ? AppConstant.LISTING_SOLD : unitListingStateFor(development));
        if (unit.isSoldUnit() && unit.getSoldAt() == null) unit.setSoldAt(java.time.OffsetDateTime.now());
    }

    /** What a unit row's listing state is, given its project's. */
    public static String unitListingStateFor(Development development) {
        String state = development.getListingState();
        if (AppConstant.LISTING_LIVE.equals(state)) return AppConstant.LISTING_LIVE;
        if (AppConstant.LISTING_WITHDRAWN.equals(state)) return AppConstant.LISTING_WITHDRAWN;
        return AppConstant.LISTING_DRAFT;
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Spreads a grouped count across the four setters.
     *
     * <p>The three named states do not sum to the total on purpose: NOT_FOR_SALE and RETAINED units are
     * inventory that was never on offer, so they count in the total and in none of the others. The database
     * says the same thing with {@code <=} rather than {@code =}.
     */
    private void applyTally(List<DevelopmentUnitRepository.StateTally> tally,
                            java.util.function.IntConsumer total,
                            java.util.function.IntConsumer available,
                            java.util.function.IntConsumer reserved,
                            java.util.function.IntConsumer sold) {
        Map<String, Long> byState = tally.stream().collect(Collectors.toMap(
                DevelopmentUnitRepository.StateTally::getSaleState,
                DevelopmentUnitRepository.StateTally::getTally,
                Long::sum));

        int all = byState.values().stream().mapToInt(Long::intValue).sum();
        total.accept(all);
        available.accept(count(byState, AppConstant.UNIT_AVAILABLE));
        // A held unit is spoken for, and a card that counts it as available sells it twice.
        reserved.accept(count(byState, AppConstant.UNIT_RESERVED) + count(byState, AppConstant.UNIT_HELD));
        sold.accept(count(byState, AppConstant.UNIT_SOLD));
    }

    private static int count(Map<String, Long> byState, String state) {
        return byState.getOrDefault(state, 0L).intValue();
    }

    private BigDecimal[] priceRange(Long developmentId) {
        List<Object[]> rows = units.priceRangeForDevelopment(developmentId);
        if (rows.isEmpty() || rows.getFirst() == null) return new BigDecimal[] {null, null};
        Object[] row = rows.getFirst();
        return new BigDecimal[] {(BigDecimal) row[0], (BigDecimal) row[1]};
    }

    private List<InventoryMaths.PhaseFigures> figuresFor(Long developmentId) {
        List<InventoryMaths.PhaseFigures> out = new ArrayList<>();
        for (DevelopmentPhase phase : phases.findForDevelopment(developmentId)) {
            out.add(new InventoryMaths.PhaseFigures(phase.getPercentComplete(), phase.getWeightPct(),
                    phase.getBudgetAmount(), phase.getPlannedUnitCount()));
        }
        return out;
    }
}
