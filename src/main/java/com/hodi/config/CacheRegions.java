package com.hodi.config;

import java.util.Set;

/**
 * Every Redis cache region this application declares, and the one place the application's name is
 * spelled.
 *
 * <h2>Why the app name is in the region name itself</h2>
 *
 * <p>Every project in this family runs against the same local Redis, and more than one of them declares
 * a region called {@code configValues}. Prefixing at the cache-manager level fixes the stored keys, and
 * that fix is still in place — but the <em>region</em> stayed anonymous, which leaves the name unqualified
 * everywhere a region name is actually read by a person: in a {@code @Cacheable} annotation, in a cache
 * miss logged by Spring, in {@code CacheManager.getCacheNames()}, and in the exception text when a region
 * does not exist. Putting {@link #APP} in the name means those all say which application they belong to.
 *
 * <h2>Why this is a compile-time constant and not a property</h2>
 *
 * <p>Region names appear in annotations, so they have to be compile-time constants — {@code APP} could
 * not come from configuration even if that seemed desirable. It therefore is not: this constant is the
 * single source of truth, and {@code TokenBlacklistService} derives its raw key namespace from it rather
 * than repeating the literal. There is deliberately no {@code hodimp.redis.prefix} property any more,
 * because two sources for one name is how they drift apart.
 *
 * <h2>Adding a region</h2>
 *
 * <p>Declare it here and add it to {@link #ALL}. {@code RedisConfig} registers exactly that set and
 * refuses to create anything else, so a {@code @Cacheable("somethingNew")} that skipped this file fails
 * loudly on first use instead of quietly creating an unnamespaced region — which is the bug this whole
 * class exists to prevent.
 */
public final class CacheRegions {

    /** The application's name. Every region below and every raw key namespace is built from it. */
    public static final String APP = "hodimp";

    /** Separator between the application name and the region name. */
    private static final String SEP = ":";

    /**
     * Global configuration values, keyed by config key alone.
     *
     * <p>Not tenant-prefixed, because these rows are the same for everyone — a tenant prefix here would
     * store one identical copy per organisation.
     */
    public static final String CONFIG_VALUES = APP + SEP + "configValues";

    /**
     * Per-organisation configuration overrides.
     *
     * <p>Tenant-prefixed <em>inside</em> the key by {@code ConfigurationCache}, because these rows differ
     * per organisation and an unprefixed key here would serve one seller's gateway credential to another.
     */
    public static final String TENANT_CONFIG_VALUES = APP + SEP + "tenantConfigValues";

    /**
     * Raw (non-{@code @Cacheable}) key namespaces, for the places that use a template directly.
     *
     * <p>{@code TokenBlacklistService} is the only one: a revoked JWT is not a cached value, it is a
     * tombstone with a TTL, so it does not belong to the cache abstraction.
     */
    public static final String BLACKLIST_KEYS = APP + SEP + "blacklist" + SEP;

    /**
     * The complete set of regions. {@code RedisConfig} registers these and only these.
     *
     * <p>Kept beside the constants rather than derived by reflection so that adding a region is one
     * deliberate edit in one file, and so this list can be read.
     */
    public static final Set<String> ALL = Set.of(CONFIG_VALUES, TENANT_CONFIG_VALUES);

    private CacheRegions() {}
}
