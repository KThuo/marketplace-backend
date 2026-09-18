package com.hodi.modules.payments;

import com.hodi.common.EncryptionUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A channel's configuration: driven by its descriptor, and a secret that does not come back.
 *
 * <p>The rules here fail silently when they are wrong — a form round trip that overwrites a real password
 * with the mask breaks nothing at save time and everything on the next call to the bank, with no sign on
 * the screen of what happened. So they are pinned.
 */
@SpringBootTest
class ChannelConfigIT {

    @Autowired EncryptionUtil crypto;

    /** A channel that needs a host, a path and a password — the shape every Co-op channel has. */
    private Map<String, Object> descriptor() {
        return Map.of("fields", List.of(
                Map.of("key", "sandboxBaseUrl", "label", "Sandbox host", "type", "text", "required", true),
                Map.of("key", "tokenPath", "label", "Token path", "type", "text", "required", true),
                Map.of("key", "consumerSecret", "label", "Secret", "type", "password", "required", true)));
    }

    @Test
    @DisplayName("a secret is encrypted going in and never comes back out")
    void secretsAreEncryptedAndMasked() {
        Map<String, Object> submitted = new LinkedHashMap<>();
        submitted.put("sandboxBaseUrl", "https://sandbox.example.invalid");
        submitted.put("tokenPath", "/token");
        submitted.put("consumerSecret", "the-real-secret");

        Map<String, Object> stored = ChannelConfig.merge(descriptor(), null, submitted, crypto);

        assertNotEquals("the-real-secret", stored.get("consumerSecret"),
                "a secret sitting in the row in clear is a secret in every backup and every query log");
        assertEquals("the-real-secret",
                ChannelConfig.value(descriptor(), stored, "consumerSecret", crypto),
                "and it has to come back out for the call that needs it");

        var shown = ChannelConfig.describe(descriptor(), stored, crypto);
        var secret = shown.stream().filter(f -> "consumerSecret".equals(f.key())).findFirst().orElseThrow();
        assertEquals(ChannelConfig.MASK, secret.value(), "a read must never return it");
        assertTrue(secret.set(), "but the screen still has to know it is set");

        var host = shown.stream().filter(f -> "sandboxBaseUrl".equals(f.key())).findFirst().orElseThrow();
        assertEquals("https://sandbox.example.invalid", host.value(), "a host is not a secret");
    }

    @Test
    @DisplayName("sending the mask back leaves the stored secret alone")
    void themaskMeansNoChange() {
        Map<String, Object> stored = ChannelConfig.merge(descriptor(), null,
                Map.of("sandboxBaseUrl", "https://one.example.invalid", "tokenPath", "/token",
                        "consumerSecret", "first-secret"), crypto);

        // The operator edits the host and leaves the password field as the screen gave it to them.
        Map<String, Object> next = ChannelConfig.merge(descriptor(), stored,
                Map.of("sandboxBaseUrl", "https://two.example.invalid", "tokenPath", "/token",
                        "consumerSecret", ChannelConfig.MASK), crypto);

        assertEquals("https://two.example.invalid", next.get("sandboxBaseUrl"));
        assertEquals("first-secret", ChannelConfig.value(descriptor(), next, "consumerSecret", crypto),
                "editing the host must not silently replace the password with four bullets");
    }

    @Test
    @DisplayName("a secret sent blank is cleared, because a revoked key has to be removable")
    void blankClearsASecret() {
        Map<String, Object> stored = ChannelConfig.merge(descriptor(), null,
                Map.of("sandboxBaseUrl", "https://one.example.invalid", "tokenPath", "/token",
                        "consumerSecret", "first-secret"), crypto);

        Map<String, Object> next = ChannelConfig.merge(descriptor(), stored,
                Map.of("sandboxBaseUrl", "https://one.example.invalid", "tokenPath", "/token",
                        "consumerSecret", ""), crypto);

        assertNull(ChannelConfig.value(descriptor(), next, "consumerSecret", crypto));
    }

    @Test
    @DisplayName("a field the channel does not declare cannot be smuggled in")
    void undeclaredFieldsAreDropped() {
        Map<String, Object> stored = ChannelConfig.merge(descriptor(), null,
                Map.of("sandboxBaseUrl", "https://one.example.invalid", "tokenPath", "/token",
                        "consumerSecret", "s", "somethingElse", "value"), crypto);

        assertFalse(stored.containsKey("somethingElse"));
    }

    @Test
    @DisplayName("what is still missing is named, so \"not configured\" is actionable")
    void missingRequiredFieldsAreNamed() {
        Map<String, Object> partial = ChannelConfig.merge(descriptor(), null,
                Map.of("sandboxBaseUrl", "https://one.example.invalid"), crypto);

        var missing = ChannelConfig.missing(descriptor(), partial);
        assertTrue(missing.contains("Token path"), missing.toString());
        assertTrue(missing.contains("Secret"), missing.toString());
        assertFalse(missing.contains("Sandbox host"));
    }
}
