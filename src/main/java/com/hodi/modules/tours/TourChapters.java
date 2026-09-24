package com.hodi.modules.tours;

import com.hodi.common.exception.HodiException;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The rooms of a walkthrough, written the way YouTube descriptions already write them.
 *
 * <pre>
 * 0:00 The gate
 * 0:24 Living room
 * 1:12 Kitchen
 * 2:05 Master bedroom
 * </pre>
 *
 * <p>That format is chosen, not invented: a seller whose videographer put chapters in the description can
 * copy them across unchanged, and one who did not can type them in the only shape anybody has seen. A buyer
 * then gets a row of rooms to jump between instead of a scrub bar to hunt along — "show me the kitchen" is
 * the question a walkthrough is watched to answer.
 *
 * <p>The label may also come first ({@code Kitchen - 1:12}), because that is the other way people write a
 * list of times, and refusing it would be pedantry about punctuation.
 */
public final class TourChapters {

    private TourChapters() {}

    public record Chapter(int seconds, String label) {}

    /** More than this is a shot list, not a set of rooms, and the strip under the player stops being scannable. */
    static final int MAX_CHAPTERS = 30;
    static final int MAX_LABEL = 60;
    /** Twelve hours. A walkthrough is minutes; a larger number is a typo worth pointing at. */
    private static final int MAX_SECONDS = 12 * 60 * 60;

    private static final String CLOCK = "(\\d{1,2}(?::\\d{1,2}){1,2})";
    /** Separators people put between a time and a name: spaces, dashes of every width, a colon, a bar. */
    private static final String SEP = "[\\s\\-–—:|.)]*";
    private static final Pattern LEADING = Pattern.compile("^[-*•·\\s]*\\(?" + CLOCK + "\\)?" + SEP + "(.+)$");
    private static final Pattern TRAILING = Pattern.compile("^[-*•·\\s]*(.+?)" + SEP + "\\(?" + CLOCK + "\\)?\\s*$");

    /**
     * Every line a room, blank lines ignored, in the order the video reaches them.
     *
     * <p>Refusals name the line, because "the chapters are invalid" beside a box of twenty lines leaves the
     * seller reading all twenty.
     */
    public static List<Chapter> parse(String text) {
        List<Chapter> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;

        String[] lines = text.split("\\R");
        int previous = -1;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            int lineNo = i + 1;

            Matcher lead = LEADING.matcher(line);
            Matcher trail = TRAILING.matcher(line);
            String clock;
            String label;
            if (lead.matches()) {
                clock = lead.group(1);
                label = lead.group(2);
            } else if (trail.matches()) {
                clock = trail.group(2);
                label = trail.group(1);
            } else {
                throw refuse("Line %d has no time. Write it as “1:12 Kitchen”.".formatted(lineNo));
            }

            label = label.trim();
            if (label.isEmpty()) throw refuse("Line %d has a time but no room.".formatted(lineNo));
            if (label.length() > MAX_LABEL) {
                throw refuse("Line %d is longer than %d characters. A room's name is a few words."
                        .formatted(lineNo, MAX_LABEL));
            }

            int seconds = clockToSeconds(clock);
            if (seconds < 0 || seconds > MAX_SECONDS) {
                throw refuse("Line %d has a time that cannot be right (%s).".formatted(lineNo, clock));
            }
            if (seconds <= previous) {
                throw refuse(("Line %d is at %s, which is not after the line before it. List the rooms in the "
                        + "order the video reaches them.").formatted(lineNo, clock));
            }
            previous = seconds;
            out.add(new Chapter(seconds, label));
        }
        if (out.size() > MAX_CHAPTERS) {
            throw refuse("That is %d rooms; a tour can name %d. Keep the ones a buyer would jump to."
                    .formatted(out.size(), MAX_CHAPTERS));
        }
        return out;
    }

    /** The reverse, for putting a saved tour back into the editor's box exactly as it would be typed. */
    public static String format(List<Chapter> chapters) {
        if (chapters == null || chapters.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (Chapter c : chapters) {
            if (!out.isEmpty()) out.append('\n');
            out.append(clock(c.seconds())).append(' ').append(c.label());
        }
        return out.toString();
    }

    /** {@code 72} is {@code 1:12}; {@code 3725} is {@code 1:02:05}. */
    public static String clock(int seconds) {
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        return h > 0 ? "%d:%02d:%02d".formatted(h, m, s) : "%d:%02d".formatted(m, s);
    }

    /**
     * {@code m:ss} or {@code h:mm:ss}. Minutes and seconds past 59 are refused, not wrapped: {@code 1:75}
     * is a mistake, and quietly reading it as 2:15 would put the kitchen somewhere the seller did not.
     */
    static int clockToSeconds(String clock) {
        String[] parts = clock.split(":");
        int total = 0;
        for (int i = 0; i < parts.length; i++) {
            int value = Integer.parseInt(parts[i]);
            if (i > 0 && value > 59) return -1;
            total = total * 60 + value;
        }
        return total;
    }

    private static HodiException refuse(String message) {
        return new HodiException(message, HttpStatus.BAD_REQUEST);
    }
}
