package com.hodi.security;

import java.util.function.Supplier;

/**
 * Marks work being done for a member of the public browsing the marketplace, rather than for staff.
 *
 * <h2>What it decides</h2>
 *
 * <p>One thing, following from one sentence — <em>the person on the other end is a house-hunter, not an
 * employee</em>: {@link com.hodi.security.hashid.HashIdUtil} salts ids per username so one user cannot read
 * another's. That is right for staff and wrong for a shop window. A property link is meant to be
 * <em>sent to somebody</em>, and a saved search is meant to survive its owner signing in halfway through.
 * Under a per-user salt both break — the link decodes to nothing for the person it was sent to, and signing
 * in mid-session turns every id already on the page into a stranger's.
 *
 * <h2>Why a marker and not an inference</h2>
 *
 * <p>"There is no principal" would look like the same condition and would be wrong the moment a signed-in
 * buyer browses listings — which is the common case, not the edge one. It would also silently change the
 * behaviour of any endpoint somebody later makes public. This is explicit, it greps, and it cannot be
 * arrived at by accident.
 *
 * <h2>What it does not weaken</h2>
 *
 * <p>Nothing here grants access to an organisation's data. {@code TenantScope} still decides which rows any
 * authenticated caller may read, the marketplace still shows only listings a seller has published, and every
 * write still goes through the same services with the same rules. Ids stop being per-person secrets on this
 * one surface, which is the point of a shop window: what is in it is public by construction.
 *
 * <p>Note what is <em>not</em> in this class, unlike the axis original it is ported from: there is no
 * "scoping does not apply" clause. Hodi has no branch axis to disapply (plan section 11 deviation 4), and
 * buyer-facing reads are identity-scoped rather than unscoped — a buyer sees their own rows because the query
 * filters on their own user id, not because a marker switched a filter off.
 */
public final class PublicMarketplace {

    /**
     * The identity ids are salted with on this surface. A fixed word rather than the absent principal's
     * {@code "system"}, so it reads as a decision at the call site rather than an accident of being logged
     * out.
     */
    public static final String IDENTITY = "marketplace";

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    private PublicMarketplace() {}

    /** True while the current thread is serving the public marketplace. */
    public static boolean isActive() {
        return ACTIVE.get();
    }

    /** Runs {@code work} as the public marketplace, always restoring the previous state. */
    public static <T> T run(Supplier<T> work) {
        boolean previous = ACTIVE.get();
        ACTIVE.set(true);
        try {
            return work.get();
        } finally {
            ACTIVE.set(previous);
        }
    }

    /** Runs {@code work} as the public marketplace, for the callers with nothing to return. */
    public static void run(Runnable work) {
        run(() -> {
            work.run();
            return null;
        });
    }
}
