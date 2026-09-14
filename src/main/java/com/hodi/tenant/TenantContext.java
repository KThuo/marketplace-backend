package com.hodi.tenant;

import java.util.function.Supplier;

/**
 * The seller organisation the current thread's work belongs to.
 *
 * <p>Unlike the axis original this is ported from, binding a tenant here <strong>routes nothing</strong>.
 * Every row lives in one schema, so there is no {@code search_path} to set and no connection to steer. What
 * it is for is narrower and worth stating precisely, because a name carried over from a system where it
 * meant "which database" invites the assumption that it still does:
 *
 * <ul>
 *   <li><strong>Configuration resolution.</strong> {@code ConfigurationService} resolves
 *       <em>tenant override ?? global</em>, and it needs to know whose override to look for.
 *   <li><strong>Cache partitioning.</strong> {@code CacheConfig}'s key generator folds this in, so one
 *       organisation's cached value can never be served to another.
 *   <li><strong>Log attribution.</strong> The Logback pattern emits it, which is what lets a line be
 *       attributed to an organisation after the fact.
 * </ul>
 *
 * <p><strong>It is never an authorisation input.</strong> Which rows a caller may read is
 * {@code TenantScope}'s decision, resolved from the principal — not from this. That separation is the
 * point: a bank's staff have a tenant context of none and yet legitimately read several sellers' rows,
 * so a filter keyed off this value would be both too narrow and too trusting.
 *
 * <p>Bound per request by {@link TenantBindingFilter} after authentication, and cleared in a
 * {@code finally} block so a pooled thread never leaks one request's organisation into the next. The login
 * path has no principal yet, so it uses {@link #runAs} explicitly — password policy and session windows are
 * read before anybody is authenticated, and reading them under the wrong organisation is how one seller's
 * lockout threshold ends up applied to another's staff.
 */
public final class TenantContext {

    private record Scope(Long tenantId, String tenantName) {}

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private TenantContext() {}

    public static void set(Long tenantId, String tenantName) {
        CURRENT.set(new Scope(tenantId, tenantName));
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** The bound organisation's id, or {@code null} for platform staff, buyers and scheduled work. */
    public static Long getTenantId() {
        Scope s = CURRENT.get();
        return s == null ? null : s.tenantId();
    }

    public static String getTenantName() {
        Scope s = CURRENT.get();
        return s == null ? null : s.tenantName();
    }

    /** True when no organisation is bound. Configuration then resolves at the global layer only. */
    public static boolean isPlatform() {
        return getTenantId() == null;
    }

    /**
     * A cache-key prefix for the bound organisation: {@code t42}, or {@code platform} when none is bound.
     *
     * <p>Exists so a {@code @Cacheable}/{@code @CacheEvict} pair can share one explicit key expression. The
     * shared key generator folds the <em>method name</em> into its key, which avoids collisions between
     * methods but makes targeted eviction impossible — the evicting method's name differs from the reading
     * one's, so the two never produce the same key.
     */
    public static String cacheScope() {
        Long tenantId = getTenantId();
        return tenantId == null ? "platform" : "t" + tenantId;
    }

    /** Runs {@code work} bound to one organisation, always restoring the previous binding. */
    public static <T> T runAs(Long tenantId, String tenantName, Supplier<T> work) {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(tenantId, tenantName));
        try {
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static void runAs(Long tenantId, String tenantName, Runnable work) {
        runAs(tenantId, tenantName, () -> {
            work.run();
            return null;
        });
    }
}
