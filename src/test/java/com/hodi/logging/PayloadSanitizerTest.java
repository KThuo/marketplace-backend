package com.hodi.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the request log is allowed to carry: never a secret, never a whole identity, never eighty codes. */
class PayloadSanitizerTest {

    private final PayloadSanitizer sanitizer = new PayloadSanitizer();

    @Test
    @DisplayName("a permission list becomes its count; the fields beside it are untouched")
    void permissionsAreCounted() {
        String out = sanitizer.sanitize(Map.of(
                "username", "uma",
                "permissions", List.of("USERS_VIEW", "USERS_CREATE", "BOOKINGS_VIEW"),
                "visibleTenants", List.of("abc", "def")));
        assertTrue(out.contains("\"permissions\":\"[3 items]\""), out);
        assertFalse(out.contains("USERS_VIEW"), "not one code should reach the log");
        assertTrue(out.contains("\"visibleTenants\":[\"abc\",\"def\"]"), "short useful lists stay as they are");
        assertTrue(out.contains("\"username\":\"uma\""));
    }

    @Test
    @DisplayName("a permission list nested inside a response envelope is counted too")
    void nestedListsAreCounted() {
        String out = sanitizer.sanitize(Map.of("data", Map.of("user", Map.of(
                "permissions", List.of("A", "B"), "accessToken", "eyJ..."))));
        assertTrue(out.contains("\"permissions\":\"[2 items]\""), out);
        assertTrue(out.contains("\"accessToken\":\"***\""), "credentials are still redacted outright");
    }
}
