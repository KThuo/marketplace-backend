package com.hodi.modules.assistant;

import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Whole-word matching for the rules.
 *
 * <p>The first version used {@code contains}, so "agent" inside "the agent's commission" offered a person, and
 * "rent" matched "current". A word here only matches with nothing alphanumeric on either side.
 */
final class Words {

    private Words() {}

    /** Whether {@code phrase} appears in the lower-cased {@code text} as a whole word or phrase. */
    static boolean has(String text, String phrase) {
        String p = phrase.toLowerCase(Locale.ROOT).trim();
        if (p.isEmpty()) return false;
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(p) + "(?![\\p{L}\\p{N}])").matcher(text).find();
    }

    static boolean any(String text, Collection<String> phrases) {
        for (String p : phrases) if (has(text, p)) return true;
        return false;
    }
}
