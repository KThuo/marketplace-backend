package com.hodi.modules.developments;

import com.hodi.modules.properties.Property;
import java.math.BigDecimal;
import java.util.List;

/**
 * What a unit actually is, once the typology's answers and its own have been reconciled.
 *
 * <h2>Why this is a type rather than a few ternaries</h2>
 *
 * <p>The rule — a unit's own value, or the typology's — has to be applied identically by the public detail
 * page, the availability list, the workspace inventory screen and anything that comes later. Written inline it
 * would be applied four times and eventually differ in one of them, and the way it would differ is a flat
 * advertised with the wrong number of bathrooms.
 *
 * <p>{@link #inherited} says which values came from the typology, so a screen can show that a unit is
 * described by its kind rather than by anything specific to it. That is worth knowing: "3 baths" that came
 * from the typology and "3 baths" somebody typed on this unit are the same claim with different reliability.
 */
public record UnitSpec(
        Short bedrooms,
        Short bathrooms,
        Short balconies,
        Short parkingSpaces,
        BigDecimal floorAreaSqm,
        BigDecimal balconyAreaSqm,
        String aspect,
        String description,
        BigDecimal price,
        String currency,
        List<String> featureCodes,
        /** The fields above that came from the typology rather than from the unit. */
        List<String> inherited) {

    /**
     * Reconciles one unit against its typology.
     *
     * <p>Null on the unit means "as the typology says", which is what keeps two hundred units from storing two
     * hundred copies of the same four numbers — and what makes correcting the typology fix every unit that
     * never disagreed with it.
     *
     * @param ownFeatures    the unit's own feature codes; empty means it inherits
     * @param typeFeatures   the typology's
     */
    public static UnitSpec of(Property unit, DevelopmentUnitType type,
                              List<String> ownFeatures, List<String> typeFeatures) {
        List<String> inherited = new java.util.ArrayList<>();

        Short bedrooms = pick(unit.getBedrooms(), type == null ? null : type.getBedrooms(),
                "bedrooms", inherited);
        Short bathrooms = pick(unit.getBathrooms(), type == null ? null : type.getBathrooms(),
                "bathrooms", inherited);
        Short parking = pick(unit.getParkingSpaces(), type == null ? null : type.getParkingSpaces(),
                "parkingSpaces", inherited);
        BigDecimal floor = pick(unit.getFloorAreaSqm(), type == null ? null : type.getFloorAreaSqm(),
                "floorAreaSqm", inherited);
        BigDecimal balconyArea = pick(unit.getBalconyAreaSqm(),
                type == null ? null : type.getBalconyAreaSqm(), "balconyAreaSqm", inherited);

        /*
         * Balconies has no typology equivalent, deliberately.
         *
         * How many balconies a home has is exactly the kind of thing that varies between two flats of the same
         * kind — it is the example that prompted all of this — so there is nothing sensible to inherit. A unit
         * that does not say has not said.
         */
        Short balconies = unit.getBalconies();

        /*
         * A set has no null, so the rule is stated in terms of emptiness: any features of its own means those
         * are the complete list. That is what allows "this one, but without the open-plan kitchen" — additive
         * inheritance could only ever add.
         */
        List<String> features;
        if (ownFeatures != null && !ownFeatures.isEmpty()) {
            features = List.copyOf(ownFeatures);
        } else {
            features = typeFeatures == null ? List.of() : List.copyOf(typeFeatures);
            if (!features.isEmpty()) inherited.add("features");
        }

        BigDecimal price = pick(unit.getPrice(), type == null ? null : type.getListPrice(),
                "price", inherited);

        return new UnitSpec(bedrooms, bathrooms, balconies, parking, floor, balconyArea,
                unit.getAspect(),
                unit.getDescription() != null ? unit.getDescription()
                        : type == null ? null : type.getDescription(),
                price,
                unit.getCurrency() != null ? unit.getCurrency()
                        : type == null ? "KES" : type.getCurrency(),
                features, List.copyOf(inherited));
    }

    /** The unit's answer, or the typology's — recording which, so a screen can say so. */
    private static <T> T pick(T own, T fromType, String field, List<String> inherited) {
        if (own != null) return own;
        if (fromType != null) inherited.add(field);
        return fromType;
    }

    public boolean isInherited(String field) {
        return inherited.contains(field);
    }
}
