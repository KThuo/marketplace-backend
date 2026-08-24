package com.hodi.modules.configurations;

import com.hodi.common.EncryptionUtil;
import com.hodi.enums.ConfigKey;
import com.hodi.logging.HodiLogger;
import com.hodi.modules.configurations.ConfigurationCache.GlobalEntry;
import com.hodi.modules.configurations.ConfigurationCache.TenantEntry;
import com.hodi.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Reads runtime configuration. Global by default, tenant override by exception (plan section 7.2).
 *
 * <p>Resolution is {@code tenant override ?? global}. The {@code overridable} flag is checked <em>before</em>
 * the tenant lookup, which buys two things: a non-overridable key costs no second query, and a stray tenant
 * row for a non-overridable key — hand-inserted, or restored from a backup taken when the flag was different —
 * can never take effect. The write path rejects such a row too; this is the line of defence that still holds
 * for data which arrived by another route.
 *
 * <p>Which layer won is logged at debug, because "which key did it actually use" is the first question asked
 * when a gateway call fails with somebody's credentials.
 *
 * <p>Caching lives in {@link ConfigurationCache} rather than here, so these methods reach it through the proxy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigurationService {

    private final ConfigurationCache cache;
    private final EncryptionUtil encryption;

    // ── reads ─────────────────────────────────────────────────────────────────

    /**
     * The effective value for a key, or empty when unset at both layers. Secrets are decrypted here so no
     * caller ever handles ciphertext.
     */
    public Optional<String> resolve(String key) {
        GlobalEntry global = cache.global(key);

        if (global != null && global.overridable() && !TenantContext.isPlatform()) {
            TenantEntry override = cache.tenant(key);
            if (override != null && override.isLive() && hasValue(override.storedValue())) {
                log.debug("Config {} resolved from the tenant override layer (tenant {})",
                        key, TenantContext.getTenantId());
                return Optional.ofNullable(decrypt(override.secret(), override.storedValue(), key));
            }
        }

        if (global == null || !global.isLive() || !hasValue(global.storedValue())) {
            log.debug("Config {} has no value at either layer", key);
            return Optional.empty();
        }
        log.debug("Config {} resolved from the global layer", key);
        return Optional.ofNullable(decrypt(global.secret(), global.storedValue(), key));
    }

    public String getString(ConfigKey key) {
        return resolve(key.getKey()).orElse(key.getDefaultValue());
    }

    public String getString(String key, String fallback) {
        return resolve(key).orElse(fallback);
    }

    public int getInt(ConfigKey key) {
        return parseInt(resolve(key.getKey()).orElse(key.getDefaultValue()), key.getKey(), 0);
    }

    public int getInt(ConfigKey key, int fallback) {
        return parseInt(resolve(key.getKey()).orElse(null), key.getKey(), fallback);
    }

    public boolean getBoolean(ConfigKey key) {
        String raw = resolve(key.getKey()).orElse(key.getDefaultValue());
        return "true".equalsIgnoreCase(raw == null ? "" : raw.trim());
    }

    /** Whether a tenant may shadow this key at all (plan section 7.2). */
    public boolean isOverridable(String key) {
        GlobalEntry global = cache.global(key);
        return global != null && global.overridable();
    }

    /** Which layer a key currently resolves from — used by the settings screen and when diagnosing. */
    public String resolvedLayer(String key) {
        GlobalEntry global = cache.global(key);
        if (global != null && global.overridable() && !TenantContext.isPlatform()) {
            TenantEntry override = cache.tenant(key);
            if (override != null && override.isLive() && hasValue(override.storedValue())) return "TENANT";
        }
        return "GLOBAL";
    }

    // ── evictions, for the write paths ────────────────────────────────────────

    public void evict(String key) {
        cache.evictGlobal(key);
    }

    /** Must be called with the same tenant bound as the write it follows — the key includes the tenant. */
    public void evictTenantValue(String key) {
        cache.evictTenant(key);
    }

    public void evictAll() {
        cache.evictAllGlobal();
    }

    public void evictAllTenantValues() {
        cache.evictAllTenant();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private static boolean hasValue(String stored) {
        return stored != null && !stored.isBlank();
    }

    private String decrypt(boolean secret, String stored, String key) {
        if (!secret) return stored;
        try {
            return encryption.decryptString(stored);
        } catch (RuntimeException e) {
            // A secret that will not decrypt is a configuration error, not a request error: fail the read
            // loudly in the log but let the caller fall back to its default rather than 500.
            HodiLogger.error("Could not decrypt config value for key {}", key);
            return null;
        }
    }

    /** A malformed number in config must not take a request down — log it and use the fallback. */
    private int parseInt(String raw, String key, int fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            HodiLogger.warn("Config {} is not an integer ({}), using {}", key, raw, fallback);
            return fallback;
        }
    }
}
