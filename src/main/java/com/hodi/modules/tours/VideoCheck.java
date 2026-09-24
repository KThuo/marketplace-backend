package com.hodi.modules.tours;

/**
 * Whether a video can actually be played on somebody else's page, asked before a tour is saved.
 *
 * <p>An interface so the answer can be supplied in a test without the test depending on YouTube being
 * reachable from wherever it runs. {@link YouTubeOEmbed} is the only production implementation.
 */
public interface VideoCheck {

    /** What the provider said, or that it could not be asked. */
    enum Verdict {
        /** Public and embeddable. */
        PLAYABLE,
        /** There is no such video. */
        MISSING,
        /** It exists, but is private or its owner has turned embedding off, so it would not play. */
        NOT_EMBEDDABLE,
        /** No answer — a timeout, a network fault, a response nobody expected. Not the seller's problem. */
        UNKNOWN
    }

    record Result(Verdict verdict, String title) {
        static Result unknown() { return new Result(Verdict.UNKNOWN, null); }
    }

    Result check(String videoId);
}
