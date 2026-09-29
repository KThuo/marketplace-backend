package com.hodi.common.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code {{name}}} in a template, filled from a map.
 *
 * <p>Used by the booking terms and by every notification the catalogue renders. An unknown name stays
 * visible rather than vanishing, so a typo in a template shows up in a preview instead of producing a
 * sentence with a hole in it.
 */
public final class Placeholders {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z]+)\\s*}}");

    private Placeholders() {}

    public static String render(String template, Map<String, ?> values) {
        if (template == null) return "";
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object value = values == null ? null : values.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group(0) : String.valueOf(value)));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** The names a template asks for, in order of first appearance. */
    public static java.util.List<String> namesIn(String template) {
        java.util.List<String> names = new java.util.ArrayList<>();
        if (template == null) return names;
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) if (!names.contains(m.group(1))) names.add(m.group(1));
        return names;
    }
}
