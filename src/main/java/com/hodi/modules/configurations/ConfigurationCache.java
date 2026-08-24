package com.hodi.modules.configurations;

import com.hodi.common.AppConstant;
import com.hodi.config.CacheRegions;
import com.hodi.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * The cached half of configuration reads, deliberately its own bean.
 *
 * <p>Spring's caching is proxy-based, so a {@code @Cacheable} method called from another method of the same
 * class is not intercepted at all — it just runs. Keeping these lookups here means
 * {@link ConfigurationService} reaches them through the proxy and the cache genuinely applies. Were they
 * alongside the typed getters that call them, the annotations would be decoration: every {@code getInt} would
 * hit the database and the {@code unless} expressions would sit unevaluated because the interceptor never
 * ran.
 *
 * <p>Small immutable records are cached rather than JPA entities: an entity carries persistence-context
 * identity that means nothing once it has been through Redis, and only these few fields are ever needed.
 *
 * <p>Values are cached <em>as stored</em> — ciphertext for secrets. Decryption happens per read in
 * {@link ConfigurationService}, so no plaintext credential is ever written to Redis.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigurationCache {

    /*
     * The region names come from CacheRegions, which is where the application's name is spelled. Declaring
     * them here as literals is what let a second application on the same Redis share these keys — see
     * CacheRegions for the failure that caused.
     */
    static final String CACHE = CacheRegions.CONFIG_VALUES;
    static final String TENANT_CACHE = CacheRegions.TENANT_CONFIG_VALUES;

    /**
     * The tenant-scoped key, shared verbatim by the read and its eviction.
     *
     * <p>Deliberately not the shared {@code tenantAwareKeyGenerator}: that generator folds the method name
     * into the key, so {@code tenant(key)} and {@code evictTenant(key)} would produce different keys and the
     * eviction would silently never hit — a stale override surviving a clear, which is exactly the failure
     * this avoids.
     */
    private static final String TENANT_KEY =
            "T(com.hodi.tenant.TenantContext).cacheScope() + ':' + #key";

    /** The fields a read needs: the value, and the flags that decide how to treat it. */
    public record GlobalEntry(
            String configKey,
            String storedValue,
            String valueType,
            String category,
            boolean secret,
            boolean overridable,
            int status) {

        static GlobalEntry from(Configuration c) {
            return new GlobalEntry(c.getConfigKey(), c.getConfigValue(), c.getValueType(),
                    c.getCategory(), c.isSecret(), c.isOverridable(),
                    c.getStatus() == null ? 1 : c.getStatus());
        }

        public boolean isLive() {
            return AppConstant.isLive(status);
        }
    }

    public record TenantEntry(String configKey, String storedValue, boolean secret, int status) {

        static TenantEntry from(TenantConfiguration c) {
            return new TenantEntry(c.getConfigKey(), c.getConfigValue(), c.isSecret(),
                    c.getStatus() == null ? 1 : c.getStatus());
        }

        public boolean isLive() {
            return AppConstant.isLive(status);
        }
    }

    private final ConfigurationRepository repository;
    private final TenantConfigurationRepository tenantRepository;

    /**
     * Keyed by config key alone, not by tenant: these rows are the same for everyone, and a tenant prefix
     * would store one identical copy per organisation.
     */
    @Cacheable(cacheNames = CACHE, key = "#key", unless = "#result == null")
    public GlobalEntry global(String key) {
        return repository.findByConfigKey(key).map(GlobalEntry::from).orElse(null);
    }

    /**
     * Tenant-prefixed, because these rows differ per organisation and an unprefixed key here would serve one
     * seller's gateway credential to another.
     *
     * <p>Unlike the axis original, the query itself carries the tenant id — there is no per-tenant schema
     * making the repository implicitly scoped, so the scoping has to be in the predicate. Returning null
     * when no tenant is bound rather than querying with a null id is deliberate: a lender's or a platform
     * user's request has no override layer at all, and a {@code WHERE tenant_id IS NULL} would invent one.
     */
    @Cacheable(cacheNames = TENANT_CACHE, key = TENANT_KEY, unless = "#result == null")
    public TenantEntry tenant(String key) {
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) return null;
        return tenantRepository.findByTenantIdAndConfigKey(tenantId, key)
                .map(TenantEntry::from)
                .orElse(null);
    }

    @CacheEvict(cacheNames = CACHE, key = "#key")
    public void evictGlobal(String key) {
        log.debug("Evicted global config {}", key);
    }

    /** Must be called with the same tenant bound as the write it follows — the key includes the tenant. */
    @CacheEvict(cacheNames = TENANT_CACHE, key = TENANT_KEY)
    public void evictTenant(String key) {
        log.debug("Evicted tenant override for config {}", key);
    }

    @CacheEvict(cacheNames = CACHE, allEntries = true)
    public void evictAllGlobal() {
        // Intentionally empty — the annotation is the behaviour.
    }

    /**
     * Clears every organisation's overrides, not only the current one. Needed when a key stops being
     * overridable: existing rows go inert and their cached values must go with them, and evicting them one
     * tenant at a time would mean enumerating every tenant holding one.
     */
    @CacheEvict(cacheNames = TENANT_CACHE, allEntries = true)
    public void evictAllTenant() {
        // Intentionally empty — the annotation is the behaviour.
    }
}
