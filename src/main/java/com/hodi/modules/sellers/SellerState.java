package com.hodi.modules.sellers;

/**
 * Where a seller's application stands, and what the identity checks said.
 *
 * <p>Constants rather than enums, matching {@code AgentState} and for the same reason: they are compared
 * against database CHECKs and travel in JSON, and an enum would add a mapping layer between three
 * representations of the same few words.
 */
public final class SellerState {

    private SellerState() {}

    /** Registered, still filling it in. The window between getting an account and asking for a decision. */
    public static final String DRAFT     = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    /** Returned for something missing. Unlike a rejection, it goes back into the applicant's hands. */
    public static final String MORE_INFO = "MORE_INFO";
    public static final String APPROVED  = "APPROVED";
    public static final String REJECTED  = "REJECTED";

    /**
     * The kinds of seller, matching {@code ck_tenant_seller_type}.
     *
     * <p>Named here because nothing validated the value on the way in: {@code save()} wrote whatever
     * arrived and only {@code tenants.seller_type} carries a CHECK, which is not reached until approval.
     * A typo therefore produced an empty document checklist — {@code checklistFor} returns nothing when
     * {@code currentVersion(entityType)} is null — and {@code outstanding()} then found nothing missing,
     * so the application could be submitted with no KYB evidence at all.
     *
     * <p>Duplicated from the CHECK constraint rather than read from it, which is the usual trade in this
     * codebase: the database is where the rule is enforced and this is where the refusal is readable.
     */
    public static final String TYPE_INDIVIDUAL = "INDIVIDUAL";
    public static final String TYPE_COMPANY    = "COMPANY";
    public static final String TYPE_SACCO      = "SACCO";
    public static final String TYPE_DEVELOPER  = "DEVELOPER";
    public static final String TYPE_AGENCY     = "AGENCY";
    public static final String TYPE_GOVERNMENT = "GOVERNMENT";

    public static final java.util.List<String> SELLER_TYPES = java.util.List.of(
            TYPE_INDIVIDUAL, TYPE_COMPANY, TYPE_SACCO, TYPE_DEVELOPER, TYPE_AGENCY, TYPE_GOVERNMENT);

    /** Where the personal details came from — the first thing a reviewer wants to know. */
    public static final String SOURCE_COOP  = "COOP_ACCOUNT";
    public static final String SOURCE_SELF  = "SELF_DECLARED";

    public static final String CHECK_COOP = "COOP_ACCOUNT";
    public static final String CHECK_AML  = "AML";
    public static final String CHECK_IPRS = "IPRS";

    public static final String VERDICT_PASS    = "PASS";
    public static final String VERDICT_FAIL    = "FAIL";
    public static final String VERDICT_REVIEW  = "REVIEW";
    /**
     * What a stub returns, and deliberately never {@link #VERDICT_PASS}.
     *
     * <p>A simulated check that passed would be indistinguishable from a real one that passed, and the day
     * a provider is wired in nobody could say which historical rows meant anything.
     */
    public static final String VERDICT_PENDING_INTEGRATION = "PENDING_INTEGRATION";
    /** The bank ran AML and IPRS to open the account; the row says so rather than being absent. */
    public static final String VERDICT_SKIPPED_COOP = "SKIPPED_COOP_VERIFIED";
}
