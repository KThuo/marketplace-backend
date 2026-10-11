package com.hodi.modules.assistant;

import java.util.List;
import java.util.Map;

/**
 * What answers a question (M11).
 *
 * <p>An interface with one rules-based implementation, so putting something else behind it later is a second
 * class rather than a rewrite of everything that calls it.
 *
 * <p>Whatever is behind it, an answer is assembled from the platform's own data, read as the person asking:
 * a visitor reaches only what the public marketplace shows, and a signed-in buyer only their own rows.
 */
public interface AssistantProvider {

    Answer answer(Question question);

    /**
     * @param text    what they typed
     * @param userId  who is asking, or null for a visitor
     * @param context what the conversation remembers; never null
     */
    record Question(String text, Long userId, AssistantContext context) {
        public boolean visitor() { return userId == null; }
    }

    /**
     * @param intent  what the question turned out to be about
     * @param reply   what to say, in plain sentences
     * @param data    what was found, kept for whoever asks "why did it say that"; may be null
     * @param links   places worth going, in order
     * @param actions things the client can do from here, drawn as cards
     * @param context what the conversation should remember now
     */
    record Answer(String intent, String reply, Map<String, Object> data, List<Link> links, List<Action> actions,
                  AssistantContext context) {}

    /** @param to a path within this application */
    record Link(String label, String to) {}

    /**
     * Something the client can do from this answer.
     *
     * @param type  one of the {@code ACTION_} constants
     * @param label what the card says
     * @param data  what the card needs: listings to choose between, a search to save, where to come back to
     */
    record Action(String type, String label, Map<String, Object> data) {}
}
