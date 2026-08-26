package com.hodi.modules.agents;

/**
 * The four states an agent registration can be in.
 *
 * <p>Constants rather than an enum because they are compared against a database CHECK and travel in JSON;
 * an enum would add a mapping layer between three representations of the same four words.
 *
 * <p>{@code SUSPENDED} is distinct from {@code REJECTED} on purpose: a rejection is a decision about an
 * application that never became anything, a suspension is a decision about a working agent who has listings
 * and clients. Only one of them can be reinstated.
 */
public final class AgentState {

    private AgentState() {}

    public static final String PENDING   = "PENDING";
    public static final String APPROVED  = "APPROVED";
    public static final String REJECTED  = "REJECTED";
    public static final String SUSPENDED = "SUSPENDED";

    /** What a listing says about whose property it is (FR161). */
    public static final String OWNERSHIP_SELF   = "SELF";
    public static final String OWNERSHIP_CLIENT = "CLIENT";

    /** What a signature was captured for. One value today; see the migration for why the column exists. */
    public static final String SIGNATURE_AGENT_TERMS = "AGENT_TERMS";

    public static final String SIGNATURE_DRAWN = "DRAWN";
    public static final String SIGNATURE_TYPED = "TYPED";
}
