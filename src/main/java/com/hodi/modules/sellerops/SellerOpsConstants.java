package com.hodi.modules.sellerops;

/** The vocabulary of M13: what a placement does, and where money stands. */
public final class SellerOpsConstants {

    private SellerOpsConstants() {}

    // ── what a promotion package does ─────────────────────────────────────────
    /** A badge on the card and a lift in results. */
    public static final String PLACEMENT_FEATURED = "FEATURED";
    /** Above everything else matching the search. */
    public static final String PLACEMENT_TOP = "TOP_OF_SEARCH";
    public static final String PLACEMENT_HOMEPAGE = "HOMEPAGE";

    // ── where a promotion stands ──────────────────────────────────────────────
    /** Asked for; the platform has not started it. */
    public static final String PROMO_REQUESTED = "REQUESTED";
    public static final String PROMO_ACTIVE    = "ACTIVE";
    public static final String PROMO_EXPIRED   = "EXPIRED";
    public static final String PROMO_CANCELLED = "CANCELLED";

    // ── where a commission stands ─────────────────────────────────────────────
    public static final String COMMISSION_DUE      = "DUE";
    public static final String COMMISSION_INVOICED = "INVOICED";
    public static final String COMMISSION_PAID     = "PAID";
    public static final String COMMISSION_WAIVED   = "WAIVED";
}
