package com.hodi.modules.assistant;

import java.util.List;

/**
 * What answers a question (M11).
 *
 * <p>An interface with one rules-based implementation today, the same shape {@code AffordabilityProvider}
 * used for M3's mocked scoring: the seam is real, so putting a language model behind it later is a second
 * class rather than a rewrite of everything that calls it.
 *
 * <p>Whatever is behind it, an answer here is assembled from the platform's own data. The assistant is
 * orchestration over M2, M3 and M4 — it searches real listings, runs the real affordability arithmetic and
 * reads the caller's own leads. It does not have opinions about property.
 */
public interface AssistantProvider {

    /**
     * @param question what they typed
     * @param userId   whose conversation it is — the caller's own rows are all it may read
     * @return what it decided, what it found, and what to say
     */
    Answer answer(String question, Long userId);

    /**
     * @param intent  what the question turned out to be about
     * @param reply   what to say, in plain sentences
     * @param payload what was found, as JSON, or null
     * @param links   things worth clicking, in order
     */
    record Answer(String intent, String reply, String payload, List<Link> links) {}

    /** @param label what it says, {@param to} a path within this application */
    record Link(String label, String to) {}
}
