package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link DevelopmentInventoryService} against a real database.
 *
 * <p>{@link InventoryMathsTest} proves the arithmetic. This proves the half that arithmetic cannot: that the
 * grouped tally counts the right states into the right columns, that the "from" price follows a unit's own
 * price over its typology's, and that a typology's listing ends up carrying what a marketplace card needs.
 * Those are all queries, and a query is only right against rows.
 *
 * <p>{@code @Transactional} on the class, so every row written here is rolled back — the seeded development
 * database is somebody's working environment, not a fixture.
 */
@SpringBootTest
@Transactional
class DevelopmentInventoryIT {

    @Autowired DevelopmentInventoryService inventory;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired DevelopmentUnitRepository units;
    @Autowired DevelopmentPhaseRepository phases;
    @Autowired PropertyRepository properties;
    @Autowired PayCodeAllocator payCodes;
    @Autowired JdbcTemplate jdbc;

    private Long someTenant() {
        return jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
    }

    private Development development(Long tenantId) {
        return developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV"))
                .tenantId(tenantId)
                .sellingTenantId(tenantId)
                .name("Highrise Apartments")
                .developmentType("APARTMENT")
                .build());
    }

    private DevelopmentUnitType typology(Development d, String code, int beds, String price) {
        return unitTypes.save(DevelopmentUnitType.builder()
                .developmentId(d.getId())
                .reference(RrnGenerator.generate("UT"))
                .code(code)
                .name(code)
                .propertyType("APARTMENT")
                .bedrooms((short) beds)
                .listPrice(new BigDecimal(price))
                .build());
    }

    private void unit(Development d, DevelopmentUnitType t, String label, String state, String ownPrice) {
        DevelopmentUnit.DevelopmentUnitBuilder b = DevelopmentUnit.builder()
                .developmentId(d.getId())
                .unitTypeId(t.getId())
                .reference(RrnGenerator.generate("UN"))
                .payReference(payCodes.next())
                .unitLabel(label)
                .saleState(state);
        if (ownPrice != null) b.listPrice(new BigDecimal(ownPrice));
        // The database insists a sold unit has a buyer, a price and a date — as it should.
        if (AppConstant.UNIT_SOLD.equals(state)) {
            b.soldAt(java.time.OffsetDateTime.now()).soldPrice(new BigDecimal("9500000")).buyerName("Ada");
        }
        if (AppConstant.UNIT_RESERVED.equals(state) || AppConstant.UNIT_HELD.equals(state)) {
            b.reservedUntil(java.time.OffsetDateTime.now().plusDays(14));
        }
        units.save(b.build());
    }

    @Test
    @DisplayName("the four tallies count the right states, and the three do not have to sum to the total")
    void tallies() {
        Long tenantId = someTenant();
        Development d = development(tenantId);
        DevelopmentUnitType twoBed = typology(d, "2BED", 2, "9500000");

        for (int i = 1; i <= 60; i++) unit(d, twoBed, "A-" + i, AppConstant.UNIT_AVAILABLE, null);
        for (int i = 61; i <= 65; i++) unit(d, twoBed, "A-" + i, AppConstant.UNIT_RESERVED, null);
        unit(d, twoBed, "A-66", AppConstant.UNIT_HELD, null);
        for (int i = 67; i <= 69; i++) unit(d, twoBed, "A-" + i, AppConstant.UNIT_SOLD, null);
        // Inventory that was never on offer: in the total, in none of the other three.
        unit(d, twoBed, "A-70", AppConstant.UNIT_RETAINED, null);

        inventory.recountUnitType(twoBed.getId());

        DevelopmentUnitType after = unitTypes.findById(twoBed.getId()).orElseThrow();
        assertEquals(70, after.getUnitsTotal());
        assertEquals(60, after.getUnitsAvailable());
        assertEquals(6, after.getUnitsReserved(), "five reserved plus one held — a hold is spoken for");
        assertEquals(3, after.getUnitsSold());
        assertEquals(69, after.getUnitsAvailable() + after.getUnitsReserved() + after.getUnitsSold(),
                "the retained unit is in the total and in none of these");

        Development dev = developments.findById(d.getId()).orElseThrow();
        assertEquals(70, dev.getUnitsTotal(), "the development inherits its typologies' figures");
        assertEquals(60, dev.getUnitsAvailable());
    }

    @Test
    @DisplayName("the 'from' price follows a unit's own price over its typology's, and ignores sold units")
    void fromPrice() {
        Long tenantId = someTenant();
        Development d = development(tenantId);
        DevelopmentUnitType twoBed = typology(d, "2BED", 2, "9500000");

        unit(d, twoBed, "B-1", AppConstant.UNIT_AVAILABLE, null);          // takes 9,500,000
        unit(d, twoBed, "B-2", AppConstant.UNIT_AVAILABLE, "8800000");     // its own, cheaper
        unit(d, twoBed, "B-3", AppConstant.UNIT_SOLD, "7000000");          // cheapest, but gone

        inventory.recountUnitType(twoBed.getId());

        assertEquals(new BigDecimal("8800000.00"),
                unitTypes.findById(twoBed.getId()).orElseThrow().getFromPrice(),
                "a unit's own price wins, and a sold one is not something a buyer can pay");
    }

    @Test
    @DisplayName("a development's percentage is derived from its phases, with the basis recorded")
    void percentFromPhases() {
        Long tenantId = someTenant();
        Development d = development(tenantId);

        phases.save(DevelopmentPhase.builder().developmentId(d.getId())
                .reference(RrnGenerator.generate("PH")).name("Foundation").sequenceNo((short) 1)
                .budgetAmount(new BigDecimal("20000000")).percentComplete((short) 100)
                .actualCompletionOn(java.time.LocalDate.now()).build());
        phases.save(DevelopmentPhase.builder().developmentId(d.getId())
                .reference(RrnGenerator.generate("PH")).name("Superstructure").sequenceNo((short) 2)
                .budgetAmount(new BigDecimal("60000000")).percentComplete((short) 50).build());
        phases.save(DevelopmentPhase.builder().developmentId(d.getId())
                .reference(RrnGenerator.generate("PH")).name("Finishes").sequenceNo((short) 3)
                .budgetAmount(new BigDecimal("20000000")).percentComplete((short) 0).build());

        inventory.recomputeDevelopment(d.getId());

        Development after = developments.findById(d.getId()).orElseThrow();
        // 20m done + 30m of 60m = 50m of 100m
        assertEquals(50, after.getPercentComplete());
        assertEquals(AppConstant.PERCENT_BASIS_BUDGET, after.getPercentBasis());
        assertEquals(AppConstant.BUILD_UNDER_CONSTRUCTION, after.getConstructionStatus());
    }

    @Test
    @DisplayName("a typology's listing ends up carrying what a marketplace card reads")
    void mirrorsOntoTheListing() {
        Long tenantId = someTenant();
        Development d = development(tenantId);
        DevelopmentUnitType twoBed = typology(d, "2BED", 2, "9500000");
        unit(d, twoBed, "C-1", AppConstant.UNIT_AVAILABLE, null);
        unit(d, twoBed, "C-2", AppConstant.UNIT_SOLD, null);

        Property listing = properties.save(Property.builder()
                .tenantId(tenantId)
                .reference(RrnGenerator.generate("PR"))
                .title("Two bedroom at Highrise")
                .propertyType("APARTMENT")
                .price(new BigDecimal("9500000"))
                .county("Nairobi")
                .developmentId(d.getId())
                .unitTypeId(twoBed.getId())
                .build());

        inventory.recountUnitType(twoBed.getId());

        Property after = properties.findById(listing.getId()).orElseThrow();
        assertEquals(1, after.getUnitsAvailable());
        assertEquals(2, after.getUnitsTotal());
        assertEquals("Highrise Apartments", after.getDevelopmentName());
        assertEquals(AppConstant.BUILD_PLANNED, after.getConstructionStatus());
    }

    @Test
    @DisplayName("an ordinary listing is untouched by any of this")
    void ordinaryListingUnaffected() {
        Long tenantId = someTenant();
        Property plain = properties.save(Property.builder()
                .tenantId(tenantId)
                .reference(RrnGenerator.generate("PR"))
                .title("A normal house")
                .propertyType("APARTMENT")
                .price(new BigDecimal("4200000"))
                .county("Nairobi")
                .build());

        Property after = properties.findById(plain.getId()).orElseThrow();
        assertNull(after.getDevelopmentId());
        assertNull(after.getUnitTypeId());
        assertNull(after.getUnitsAvailable());
        assertNull(after.getConstructionStatus());
        assertNotNull(after.getReference());
    }

    @Test
    @DisplayName("recounting a whole development costs one pass per typology, not one per unit")
    void recountAllTypologies() {
        Long tenantId = someTenant();
        Development d = development(tenantId);
        DevelopmentUnitType studio = typology(d, "STUDIO", 0, "5400000");
        DevelopmentUnitType oneBed = typology(d, "1BED", 1, "7200000");
        DevelopmentUnitType twoBed = typology(d, "2BED", 2, "9500000");

        for (int i = 1; i <= 40; i++) unit(d, studio, "S-" + i, AppConstant.UNIT_AVAILABLE, null);
        for (int i = 1; i <= 60; i++) unit(d, oneBed, "O-" + i, AppConstant.UNIT_AVAILABLE, null);
        for (int i = 1; i <= 70; i++) unit(d, twoBed, "T-" + i, AppConstant.UNIT_AVAILABLE, null);

        inventory.recountAll(d.getId());

        assertEquals(40, unitTypes.findById(studio.getId()).orElseThrow().getUnitsTotal());
        assertEquals(60, unitTypes.findById(oneBed.getId()).orElseThrow().getUnitsTotal());
        assertEquals(70, unitTypes.findById(twoBed.getId()).orElseThrow().getUnitsTotal());

        Development after = developments.findById(d.getId()).orElseThrow();
        assertEquals(170, after.getUnitsTotal());
        assertEquals(new BigDecimal("5400000.00"), after.getFromPrice(), "the cheapest studio");
        assertEquals(new BigDecimal("9500000.00"), after.getToPrice(), "the dearest two-bed");

        List<DevelopmentUnitType> all = unitTypes.findForDevelopment(d.getId());
        assertEquals(3, all.size());
    }
}
