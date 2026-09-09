package com.hodi.modules.developments;

import com.hodi.modules.properties.Property;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a unit is, once its own answers and its typology's have been reconciled.
 *
 * <p>Pure, so the rule can be pinned down without a database. It is worth pinning down because getting it
 * wrong is invisible: a flat advertised with the wrong number of bathrooms looks exactly like a flat
 * advertised with the right number, right up until somebody views it.
 */
class UnitSpecTest {

    private DevelopmentUnitType typology() {
        return DevelopmentUnitType.builder()
                .id(1L).code("2B").name("Two bedroom").description("The standard two-bed.")
                .bedrooms((short) 2).bathrooms((short) 2).parkingSpaces((short) 1)
                .floorAreaSqm(new BigDecimal("92")).balconyAreaSqm(new BigDecimal("11"))
                .listPrice(new BigDecimal("9500000")).currency("KES")
                .build();
    }

    private Property bare() {
        return Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit").id(10L).unitLabel("B-3-04").build();
    }

    @Test
    @DisplayName("a unit that says nothing is described entirely by its kind")
    void silentUnitInheritsEverything() {
        UnitSpec spec = UnitSpec.of(bare(), typology(), List.of(), List.of("OPEN_PLAN_KITCHEN"));

        assertEquals((short) 2, spec.bedrooms());
        assertEquals((short) 2, spec.bathrooms());
        assertEquals(0, spec.price().compareTo(new BigDecimal("9500000")));
        assertEquals(List.of("OPEN_PLAN_KITCHEN"), spec.featureCodes());

        /*
         * This is the point of storing nothing on the ordinary units: correcting the typology's bathroom count
         * fixes every one of them. Copying the figures onto each unit at generation time would have made the
         * correction reach none of them.
         */
        assertTrue(spec.isInherited("bathrooms"));
        assertTrue(spec.isInherited("features"));
    }

    @Test
    @DisplayName("a unit that disagrees wins, field by field")
    void unitOverridesFieldByField() {
        Property special = bare();
        special.setBathrooms((short) 3);
        special.setPrice(new BigDecimal("11250000"));

        UnitSpec spec = UnitSpec.of(special, typology(), List.of(), List.of());

        assertEquals((short) 3, spec.bathrooms(), "its own");
        assertFalse(spec.isInherited("bathrooms"));
        assertEquals((short) 2, spec.bedrooms(), "and everything it did not mention still comes from the kind");
        assertTrue(spec.isInherited("bedrooms"));
        assertEquals(0, spec.price().compareTo(new BigDecimal("11250000")));
    }

    @Test
    @DisplayName("its own features replace the kind's rather than adding to them")
    void ownFeaturesReplace() {
        /*
         * The case additive inheritance could not express: two flats of the same kind, one with the open-plan
         * kitchen the typology advertises and one without. If a unit's features were merged with its
         * typology's, there would be no way to say a flat lacks something its kind normally has.
         */
        UnitSpec spec = UnitSpec.of(bare(), typology(),
                List.of("SEPARATE_KITCHEN", "CORNER_UNIT"),
                List.of("OPEN_PLAN_KITCHEN", "EN_SUITE_MASTER"));

        assertEquals(List.of("SEPARATE_KITCHEN", "CORNER_UNIT"), spec.featureCodes());
        assertFalse(spec.featureCodes().contains("OPEN_PLAN_KITCHEN"),
                "a unit that lists its own features is not also claiming its kind's");
        assertFalse(spec.isInherited("features"));
    }

    @Test
    @DisplayName("balconies are never inherited, because that is the thing that varies")
    void balconiesAreAlwaysTheUnitsOwn() {
        /*
         * The example that prompted the whole change: one two-bed with two balconies and another with one.
         * There is nothing sensible for a typology to say about it, so a unit that has not said has not said —
         * rather than borrowing a figure that would be wrong for half the block.
         */
        assertNull(UnitSpec.of(bare(), typology(), List.of(), List.of()).balconies());

        Property two = bare();
        two.setBalconies((short) 2);
        assertEquals((short) 2, UnitSpec.of(two, typology(), List.of(), List.of()).balconies());
    }

    @Test
    @DisplayName("a unit with no typology at all still resolves")
    void survivesAMissingTypology() {
        Property orphan = bare();
        orphan.setBathrooms((short) 1);

        UnitSpec spec = UnitSpec.of(orphan, null, List.of(), List.of());

        assertEquals((short) 1, spec.bathrooms());
        assertNull(spec.bedrooms(), "nothing to inherit from, so nothing is claimed");
        assertEquals("KES", spec.currency());
        assertTrue(spec.featureCodes().isEmpty());
    }

    @Test
    @DisplayName("zero is an answer and not an absence")
    void zeroIsNotNull() {
        /*
         * The trap this codebase has already fallen into twice — a studio's zero bedrooms, a flat's zero
         * parking. A unit that says "no parking" must not silently borrow the typology's one bay.
         */
        Property noParking = bare();
        noParking.setParkingSpaces((short) 0);

        UnitSpec spec = UnitSpec.of(noParking, typology(), List.of(), List.of());

        assertEquals((short) 0, spec.parkingSpaces());
        assertFalse(spec.isInherited("parkingSpaces"),
                "0 is what this unit says, not a gap the typology fills");
    }
}
