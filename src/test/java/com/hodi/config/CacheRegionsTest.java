package com.hodi.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the one rule this class exists for: <strong>every Redis namespace carries the application's
 * name</strong>.
 *
 * <p>Written by reflection rather than by listing the constants again, deliberately. A test that
 * restated the names would pass forever while a newly added region quietly skipped the prefix — which is
 * precisely the mistake that let this application read a value another one wrote. Reflecting over the
 * declared fields means a region added tomorrow is covered by a test written today.
 *
 * <p>No Spring context and no Redis: these are compile-time constants, and the point is to fail in a
 * fast unit test rather than when two applications happen to be running at once.
 */
class CacheRegionsTest {

    /** Every public String constant on {@link CacheRegions} except {@code APP} itself. */
    private static List<Field> namespaceFields() {
        List<Field> fields = new ArrayList<>();
        for (Field f : CacheRegions.class.getDeclaredFields()) {
            if (!Modifier.isPublic(f.getModifiers())) continue;
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (f.getType() != String.class) continue;
            if (f.getName().equals("APP")) continue;
            fields.add(f);
        }
        return fields;
    }

    private static String valueOf(Field f) {
        try {
            return (String) f.get(null);
        } catch (IllegalAccessException e) {
            throw new AssertionError("Could not read " + f.getName(), e);
        }
    }

    @Test
    @DisplayName("every declared namespace starts with the application name")
    void everyNamespaceCarriesTheAppName() {
        List<Field> fields = namespaceFields();
        assertFalse(fields.isEmpty(), "no namespace constants found — has CacheRegions been renamed?");

        for (Field f : fields) {
            String value = valueOf(f);
            assertTrue(
                    value.startsWith(CacheRegions.APP + ":"),
                    () -> "CacheRegions." + f.getName() + " is \"" + value + "\", which does not start "
                            + "with \"" + CacheRegions.APP + ":\". Every Redis namespace must carry the "
                            + "application's name — a bare name like \"configValues\" collides with the "
                            + "sibling projects on the shared Redis.");
        }
    }

    @Test
    @DisplayName("ALL lists every cache region, so RedisConfig registers all of them")
    void allListsEveryRegion() {
        /*
         * RedisConfig registers exactly CacheRegions.ALL and refuses to create anything else, so a region
         * declared as a constant but left out of ALL would fail at runtime on first use. Checking the two
         * agree here turns that into a build failure instead.
         *
         * BLACKLIST_KEYS is excluded by design: it is a raw key namespace used with a RedisTemplate
         * directly, not a cache region — a revoked token is a tombstone with a TTL, not a cached value.
         */
        Set<String> declared = Set.copyOf(
                namespaceFields().stream()
                        .filter(f -> !f.getName().equals("BLACKLIST_KEYS"))
                        .map(CacheRegionsTest::valueOf)
                        .toList());

        assertEquals(declared, CacheRegions.ALL,
                "CacheRegions.ALL must contain exactly the declared cache regions. A region missing from "
                        + "ALL is not registered by RedisConfig and fails on first use; an extra entry is "
                        + "a region nothing declares.");
    }

    @Test
    @DisplayName("the app name is not accidentally blank or punctuated")
    void appNameIsUsable() {
        assertTrue(CacheRegions.APP.matches("[a-z0-9]+"),
                "APP must be lower-case alphanumeric — it becomes part of a Redis key and a log line. "
                        + "Got: \"" + CacheRegions.APP + "\"");
    }

    @Test
    @DisplayName("regions are distinct, so two do not share a key space")
    void regionsAreDistinct() {
        List<String> values = namespaceFields().stream().map(CacheRegionsTest::valueOf).toList();
        assertEquals(values.size(), Set.copyOf(values).size(),
                "two namespaces have the same value, which would make them share a key space: " + values);
    }
}
