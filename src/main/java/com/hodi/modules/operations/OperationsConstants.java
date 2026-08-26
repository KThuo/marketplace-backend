package com.hodi.modules.operations;

/** The vocabulary of M12: what a rule routes, and what a diary entry came from. */
public final class OperationsConstants {

    private OperationsConstants() {}

    // ── what a rule routes ────────────────────────────────────────────────────
    public static final String WORK_ENQUIRY          = "ENQUIRY";
    public static final String WORK_SITE_VISIT       = "SITE_VISIT";
    public static final String WORK_PURCHASE_REQUEST = "PURCHASE_REQUEST";

    // ── where a diary entry came from ─────────────────────────────────────────
    public static final String SOURCE_SITE_VISIT = "SITE_VISIT";
    public static final String SOURCE_VALUATION  = "VALUATION";
    public static final String SOURCE_AUCTION    = "AUCTION";
    /** Typed by somebody. The only kind the calendar itself owns. */
    public static final String SOURCE_MANUAL     = "MANUAL";

    public static final String ENTRY_SCHEDULED = "SCHEDULED";
    public static final String ENTRY_DONE      = "DONE";
    public static final String ENTRY_CANCELLED = "CANCELLED";
}
