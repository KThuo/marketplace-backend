package com.hodi.modules.developments;

import com.hodi.common.exception.HodiException;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What a place comes with, at whichever level it is true.
 *
 * <h2>Three owners, one table, one rule</h2>
 *
 * <p>{@code unit_features} has always had two owner columns and a documented inheritance rule — a unit with
 * its own rows has exactly those, otherwise it inherits its typology's. What it did not have was a writer
 * for the typology column: {@code PropertyService.applyAmenities} was the only writer anywhere and it always
 * wrote {@code unit_id}. So the inheritance machinery, and everything the public read paths built on it,
 * was inert. A development had no column at all.
 *
 * <p>All three now write through here, which is what makes the feedback's distinction expressible:
 *
 * <ul>
 *   <li><b>Development</b> — the estate's own: the borehole, the gate, the clubhouse. One fact, recorded
 *       once, rather than ticked on each of ninety listings.</li>
 *   <li><b>Unit type</b> — true of every home of that kind: all-en-suite, a private garden.</li>
 *   <li><b>Listing</b> — this one home, overriding its typology where they differ.</li>
 * </ul>
 *
 * <h2>The whole set, and the diff that writes it</h2>
 *
 * <p>Callers pass what the thing has now, not a delta — reconciling two lists client-side goes wrong the
 * first time somebody unticks and reticks the same box. Null leaves them alone, so a screen that does not
 * edit amenities can still save without wiping them.
 *
 * <p>What is written is the difference, and that is a bug fix rather than an optimisation. Deleting every
 * row and re-inserting the set fails the moment somebody keeps an amenity they already had: Hibernate orders
 * inserts before deletes within a transaction, so the re-inserted row meets its own predecessor and the
 * partial unique index refuses it — saving without changing anything, the commonest save there is, was a
 * 500. Flushing between the two would have fixed the collision and kept the rewrite; the diff is better
 * anyway, because these rows carry {@code created_at} and {@code created_by} and re-inserting an amenity
 * chosen last month would restamp it with today and whoever pressed save.
 */
@Service
@RequiredArgsConstructor
public class AmenityService {

    private final UnitFeatureRepository features;
    private final UnitFeatureConfigRepository configs;

    /** Which column an amenity hangs off. */
    public enum Scope { DEVELOPMENT, UNIT_TYPE, LISTING }

    // ── reading ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<String> codesFor(Scope scope, Long ownerId) {
        if (ownerId == null) return List.of();
        return held(scope, ownerId).stream().map(UnitFeature::getFeatureCode).toList();
    }

    // ── writing ───────────────────────────────────────────────────────────────

    /**
     * Replaces what this owner comes with.
     *
     * @param requested the complete set; {@code null} leaves the existing rows untouched
     */
    @Transactional
    public void apply(Scope scope, Long ownerId, List<String> requested) {
        if (requested == null || ownerId == null) return;

        LinkedHashSet<String> wanted = requested.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertKnown(wanted);

        List<UnitFeature> held = held(scope, ownerId);
        Set<String> already = held.stream().map(UnitFeature::getFeatureCode).collect(Collectors.toSet());

        List<UnitFeature> gone = held.stream()
                .filter(f -> !wanted.contains(f.getFeatureCode()))
                .toList();
        if (!gone.isEmpty()) features.deleteAll(gone);

        for (String code : wanted) {
            if (already.contains(code)) continue;
            features.save(row(scope, ownerId, code));
        }
    }

    /**
     * Rejects a code the catalogue does not know, by name.
     *
     * <p>Naming them matters: the catalogue is database rows rather than an enum, so a code that was
     * retired is indistinguishable from one that was mistyped without being told which one it was.
     */
    private void assertKnown(Set<String> wanted) {
        if (wanted.isEmpty()) return;
        Set<String> known = configs.findLive().stream()
                .map(UnitFeatureConfig::getCode)
                .collect(Collectors.toSet());
        List<String> unknown = wanted.stream().filter(c -> !known.contains(c)).toList();
        if (!unknown.isEmpty()) {
            throw new HodiException("These are not amenities on this platform: "
                    + String.join(", ", unknown), HttpStatus.BAD_REQUEST);
        }
    }

    private List<UnitFeature> held(Scope scope, Long ownerId) {
        return switch (scope) {
            case DEVELOPMENT -> features.findForDevelopment(ownerId);
            case UNIT_TYPE -> features.findForUnitType(ownerId);
            case LISTING -> features.findForUnit(ownerId);
        };
    }

    private UnitFeature row(Scope scope, Long ownerId, String code) {
        UnitFeature.UnitFeatureBuilder builder = UnitFeature.builder()
                .featureCode(code)
                .createdBy(AuthContext.username());
        return switch (scope) {
            case DEVELOPMENT -> builder.developmentId(ownerId).build();
            case UNIT_TYPE -> builder.unitTypeId(ownerId).build();
            case LISTING -> builder.unitId(ownerId).build();
        };
    }

    // ── grouping, for the screens that show several owners at once ────────────

    /**
     * Codes for a set of typologies, keyed by typology id, in one query.
     *
     * <p>The unit-type editor lists every typology with what each comes with; a lookup per row would be one
     * query per card on a screen that exists to show them together.
     */
    @Transactional(readOnly = true)
    public java.util.Map<Long, List<String>> codesForUnitTypes(List<Long> unitTypeIds) {
        if (unitTypeIds == null || unitTypeIds.isEmpty()) return java.util.Map.of();
        return features.findForUnitTypes(unitTypeIds).stream().collect(Collectors.groupingBy(
                UnitFeature::getUnitTypeId,
                Collectors.mapping(UnitFeature::getFeatureCode, Collectors.toList())));
    }

    /** Every amenity the platform knows, in the catalogue's own order. Feeds every picker. */
    @Transactional(readOnly = true)
    public <T> List<T> catalogue(Function<UnitFeatureConfig, T> as) {
        return configs.findLive().stream().map(as).toList();
    }
}
