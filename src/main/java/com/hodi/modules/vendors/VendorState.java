package com.hodi.modules.vendors;

/**
 * The states a vendor registration and a catalogue item move through (M10).
 *
 * <p>Constants rather than enums, for the reason {@code AgentState} gives: they are compared against a
 * database CHECK and travel in JSON, and an enum would add a mapping layer between three spellings of the
 * same words.
 */
public final class VendorState {

    private VendorState() {}

    // ── the vendor ────────────────────────────────────────────────────────────
    public static final String PENDING   = "PENDING";
    public static final String APPROVED  = "APPROVED";
    public static final String REJECTED  = "REJECTED";
    public static final String SUSPENDED = "SUSPENDED";

    // ── a catalogue item ──────────────────────────────────────────────────────
    /** Theirs alone. */
    public static final String ITEM_DRAFT     = "DRAFT";
    /** Submitted, waiting on the platform. */
    public static final String ITEM_PENDING   = "PENDING";
    public static final String ITEM_LIVE      = "LIVE";
    public static final String ITEM_WITHDRAWN = "WITHDRAWN";

    /** The approval queue's name for a catalogue item. */
    public static final String APPROVAL_ENTITY_CATALOGUE_ITEM = "CATALOGUE_ITEM";
    public static final String APPROVAL_ACTION_PUBLISH = "PUBLISH";
}
