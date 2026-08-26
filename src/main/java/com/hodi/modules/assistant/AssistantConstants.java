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
    /** "What is a guide price?" — the glossary. */
    public static final String INTENT_EXPLAIN      = "EXPLAIN";
    /** "Where is my viewing?" — their own things. */
    public static final String INTENT_MY_ACTIVITY  = "MY_ACTIVITY";
    /** "Talk to a person." */
    public static final String INTENT_HANDOFF      = "HANDOFF";
    /** It did not know. Said so, and offered a person. */
    public static final String INTENT_UNKNOWN      = "UNKNOWN";
}
