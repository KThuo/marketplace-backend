package com.hodi.modules.assistant;

/** The vocabulary of M11. */
public final class AssistantConstants {

    private AssistantConstants() {}

    public static final String STATE_OPEN       = "OPEN";
    public static final String STATE_HANDED_OFF = "HANDED_OFF";
    public static final String STATE_CLOSED     = "CLOSED";

    public static final String SIDE_USER      = "USER";
    public static final String SIDE_ASSISTANT = "ASSISTANT";

    // ── what a question turned out to be about ────────────────────────────────
    /** "Three bedrooms in Kilimani under 20 million." */
    public static final String INTENT_SEARCH       = "SEARCH";
    /** "What can I afford on 180,000 a month?" */
    public static final String INTENT_AFFORD       = "AFFORDABILITY";
    /** "What is a guide price?" — the knowledge the platform edits. */
    public static final String INTENT_EXPLAIN      = "EXPLAIN";
    /** "Where is my viewing?" — their own enquiries and viewings. */
    public static final String INTENT_MY_ACTIVITY  = "MY_ACTIVITY";
    /** "What do I still owe?" — their own bookings. */
    public static final String INTENT_MY_BOOKINGS  = "MY_BOOKINGS";
    /** "Can I get a refund?" — the terms of their booking, or the platform's policy. */
    public static final String INTENT_TERMS        = "BOOKING_TERMS";
    /** "How many are left at Palm Heights?" */
    public static final String INTENT_UNITS_LEFT   = "UNITS_LEFT";
    /** "Can I see the second one?" */
    public static final String INTENT_VIEWING      = "VIEWING";
    /** "Tell me when a three-bed comes up in Karen." */
    public static final String INTENT_ALERT        = "ALERT";
    /** "Hello", "thanks". */
    public static final String INTENT_GREETING     = "GREETING";
    /** "Talk to a person." */
    public static final String INTENT_HANDOFF      = "HANDOFF";
    /** It did not know. Said so, and offered a person. */
    public static final String INTENT_UNKNOWN      = "UNKNOWN";

    // ── what an answer may offer to do, drawn by the client as a card ─────────
    /** The question needs an identity: sign in and come back to this conversation. */
    public static final String ACTION_SIGN_IN = "SIGN_IN";
    /** Ask for a viewing of one of the listings named in the action. */
    public static final String ACTION_VIEWING = "VIEWING";
    /** Save the search in the action as an alert. */
    public static final String ACTION_ALERT   = "ALERT";
    /** Pass the conversation to a person, about one of the listings named or about none. */
    public static final String ACTION_HANDOFF = "HANDOFF";

    // ── where a general hand-off goes (assistant.handoff.general) ─────────────
    public static final String HANDOFF_PLATFORM     = "PLATFORM";
    public static final String HANDOFF_ORGANISATION = "ORGANISATION";
    public static final String HANDOFF_OFF          = "OFF";

    /** Request header carrying a visitor's own token; never a query parameter, which ends up in logs. */
    public static final String VISITOR_HEADER = "X-Assistant-Visitor";
}
