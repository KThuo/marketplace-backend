package com.hodi.modules.approvals;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What an edit actually changed, as a checker needs to read it.
 *
 * <h2>Why an approval needs this at all</h2>
 *
 * <p>A pending approval used to carry a label and a note — "PR260915CB7H — Two bedroom at Highrise" and
 * "Edited while live; needs re-approval before it goes back on the marketplace." Both true, neither an
 * answer to the only question the checker has: <em>what changed?</em> Approving on that is approving the
 * fact that somebody edited something, which is not oversight, it is a rubber stamp with extra steps.
 *
 * <p>So the maker's own before and after are recorded with the request, and the difference between them is
 * computed once, on the server, so every screen shows the same list rather than each inventing one.
 *
 * <h2>Recorded, not re-derived</h2>
 *
 * <p>The pair is stored on the approval row rather than read back from the entity when the queue is opened.
 * The entity carries the <em>new</em> values — that is what "edited" means — so re-deriving would compare
 * the new value with itself and show nothing changed. And a second edit before anybody decides has to
 * restate the difference from the last approved state, which only a stored snapshot can do.
 */
public final class ChangeSet {

    private ChangeSet() {}

    /**
     * One field that moved.
     *
     * @param field the key, for a client that wants to group or filter
     * @param label what to call it on screen — the maker's words, not the column's
     * @param from  what it was; null means it was not set
     * @param to    what it is now; null means it has been cleared
     */
    public record Change(String field, String label, String from, String to) {}

    /**
     * A builder for the two snapshots, so a caller records them in one place and in one order.
     *
     * <p>Ordered deliberately: the screen reads top to bottom in the order the form does, and a map that
     * reordered itself would shuffle the diff between two runs of the same edit.
     */
    public static final class Snapshot {
        private final Map<String, String> labels = new LinkedHashMap<>();
        private final Map<String, Object> values = new LinkedHashMap<>();

        public Snapshot put(String field, String label, Object value) {
            labels.put(field, label);
            values.put(field, value);
            return this;
        }

        /*
         * An unmodifiable copy, not Map.copyOf: a snapshot legitimately holds nulls — an estate nobody
         * filled in, a description that is not set — and Map.copyOf refuses a null value. Dropping those
         * keys instead would be worse than refusing: a field going from a value to empty is exactly the
         * kind of change a checker needs to see, and it would silently vanish from the diff.
         */
        public Map<String, Object> values() {
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        public Map<String, String> labels() {
            return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(labels));
        }
    }

    public static Snapshot of() {
        return new Snapshot();
    }

    /**
     * The fields that differ, in the order they were recorded.
     *
     * <p>Compared as text after normalising, because the two sides arrive from JSON and a value that was a
     * {@code BigDecimal} going in is a {@code Double} or a {@code String} coming out — 9500000 and
     * 9500000.00 are the same price and must not be reported as a change. That normalisation is the whole
     * reason this is one function rather than a loop at each call site.
     */
    public static List<Change> between(Map<String, Object> before, Map<String, Object> after,
                                       Map<String, String> labels) {
        List<Change> changes = new ArrayList<>();
        if (after == null) return changes;
        Map<String, Object> was = before == null ? Map.of() : before;

        for (Map.Entry<String, Object> entry : after.entrySet()) {
            String field = entry.getKey();
            String now = normalise(entry.getValue());
            String then = normalise(was.get(field));
            if (java.util.Objects.equals(then, now)) continue;
            changes.add(new Change(field,
                    labels == null ? field : labels.getOrDefault(field, field),
                    then, now));
        }
        return java.util.List.copyOf(changes);
    }

    /**
     * One value as text, in a form two sides of a JSON round trip can agree on.
     *
     * <p>Numbers lose their trailing zeros — a price re-read from the database as 9500000.00 is not an edit
     * of 9500000 — and blank becomes null, because a cleared field and an empty one are the same fact and
     * reporting the difference would be noise the checker has to dismiss every time.
     */
    private static String normalise(Object value) {
        if (value == null) return null;
        if (value instanceof BigDecimal d) return d.stripTrailingZeros().toPlainString();
        if (value instanceof Number n) {
            return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
