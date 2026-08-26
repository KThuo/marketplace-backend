package com.hodi.modules.ratings;

/**
 * What a rating can be about, and the states it moves through (M7).
 *
 * <p>Constants rather than enums, for the reason the other modules give: they are compared against a
 * database CHECK and travel in JSON.
 */
public final class RatingSubject {

    private RatingSubject() {}

    public static final String PROPERTY        = "PROPERTY";
    public static final String SELLER          = "SELLER";
    public static final String AGENT           = "AGENT";
    public static final String VENDOR          = "VENDOR";
    public static final String CATALOGUE_ITEM  = "CATALOGUE_ITEM";

    /** Live and readable by anybody. */
    public static final String PUBLISHED = "PUBLISHED";
    /** Caught on the way in and waiting on a moderator. Not public. */
    public static final String HELD      = "HELD";
    /** A moderator took it down. Not public, and the reason is recorded. */
    public static final String HIDDEN    = "HIDDEN";

    /** How the platform satisfied itself there was a transaction behind a rating. */
    public static final String VIA_SITE_VISIT = "SITE_VISIT";
    public static final String VIA_OFFER      = "OFFER";
    public static final String VIA_ENQUIRY    = "ENQUIRY";
}
