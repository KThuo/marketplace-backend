package com.hodi.infra.notify;

import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;

import java.util.regex.Pattern;

/**
 * The brand, resolved for one email.
 *
 * <p>Read from the same {@link ConfigKey} entries {@code ThemeService} serves the frontend, so an email and
 * the page it links to cannot disagree about what the product looks like. Built per send rather than held as
 * a bean: the values are runtime configuration, and {@link ConfigurationService} resolves <em>tenant override
 * ?? global</em> underneath — a palette captured at startup would be one seller's brand on everybody's mail
 * the day the theme keys become overridable.
 *
 * <p><strong>Why this reads the keys itself instead of calling {@code ThemeService.theme()}.</strong> That map
 * is shaped for the client and carries {@code mapsApiKey}; a structure holding a credential has no business
 * inside a template renderer, one careless loop away from being written into an email. And an email needs two
 * things the client does not: a colour it can trust inside a {@code style} attribute, and a text colour
 * computed against the accent.
 *
 * @param onAccent text that sits on {@link #accent} — white or near-black, whichever the accent can carry
 * @param onPrimary text that sits on {@link #primary}, chosen the same way
 * @param accentText the accent as <em>text on a light surface</em>, darkened until it is readable there
 */
public record MailPalette(
        String primary,
        String accent,
        String accentLight,
        String ink,
        String onAccent,
        String onPrimary,
        String accentText,
        String logoUrl,
        String appName,
        String contactEmail,
        String contactPhone,
        String contactAddress,
        String baseUrl,
        boolean darkEnabled) {

    /**
     * A CSS hex colour and nothing else.
     *
     * <p>These values land in a {@code style} attribute, where {@code #000" onclick="…} would leave the
     * attribute and become markup. Platform staff write configuration, not the public, so this is defence in
     * depth — but the feature exists precisely so the values can be edited at runtime, and nobody reviewing an
     * email should have to work out who can reach that form first.
     */
    private static final Pattern HEX = Pattern.compile("#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6})");

    /** Not pure black: on a coloured field it reads as a hole. Matches the frontend's darkest ink. */
    private static final String NEAR_BLACK = "#0b2038";
    private static final String WHITE = "#ffffff";

    public static MailPalette from(ConfigurationService configs) {
        String primary = colour(configs, ConfigKey.THEME_PRIMARY);
        String accent = colour(configs, ConfigKey.THEME_ACCENT);
        return new MailPalette(
                primary,
                accent,
                colour(configs, ConfigKey.THEME_ACCENT_LIGHT),
                colour(configs, ConfigKey.THEME_INK),
                readableOn(accent),
                readableOn(primary),
                readableTextOn(accent, WHITE),
                trimmed(configs.getString(ConfigKey.THEME_LOGO_URL)),
                orDefault(configs.getString(ConfigKey.COMPANY_NAME), ConfigKey.COMPANY_NAME),
                trimmed(configs.getString(ConfigKey.COMPANY_EMAIL)),
                trimmed(configs.getString(ConfigKey.COMPANY_PHONE)),
                trimmed(configs.getString(ConfigKey.COMPANY_ADDRESS)),
                stripTrailingSlash(configs.getString(ConfigKey.PUBLIC_URL)),
                configs.getBoolean(ConfigKey.THEME_DARK_ENABLED));
    }

    /**
     * A configured colour, or the catalogue default when it is not one.
     *
     * <p>Falling back rather than throwing is deliberate: a mistyped colour should cost the brand on one
     * email, not the password reset somebody is waiting for.
     */
    static String colour(ConfigurationService configs, ConfigKey key) {
        return sanitise(configs.getString(key), key.getDefaultValue());
    }

    static String sanitise(String value, String fallback) {
        String trimmed = value == null ? "" : value.trim();
        return HEX.matcher(trimmed).matches() ? trimmed : fallback;
    }

    /**
     * White or near-black on the given background, whichever contrasts more.
     *
     * <p>The accent is editable, so "white on the accent" is an assumption with a shelf life: it holds for the
     * configured {@code #1B7F79} and fails the moment somebody sets a pale teal. This is the server-side twin
     * of the frontend's {@code --brand-contrast}, and the same WCAG 2.1 relative-luminance maths §25 used.
     */
    static String readableOn(String background) {
        double bg = luminance(background);
        return contrast(bg, luminance(WHITE)) >= contrast(bg, luminance(NEAR_BLACK)) ? WHITE : NEAR_BLACK;
    }

    /**
     * A colour that can be read <em>as text</em> on the given background, starting from the one asked for.
     *
     * <p>The accent works as a fill long before it works as text. A pale yellow makes a perfectly good button
     * — the label just goes dark, which {@link #readableOn} handles — but the same yellow as a link on white
     * is invisible, and the footer's contact address was exactly that until a preview showed it. So a link
     * gets the accent walked towards the far end of the range until it clears 4.5:1, which is the split the
     * frontend keeps as {@code --brand} for fills and {@code --brand-text} for text.
     *
     * <p>Walking rather than jumping to black keeps the brand where the brand can be kept: {@code #1B7F79}
     * clears the threshold as it is and comes back untouched.
     */
    static String readableTextOn(String colour, String background) {
        double bg = luminance(background);
        boolean lightBackground = bg > 0.5;
        String candidate = colour;
        for (int step = 0; step < 14 && contrast(luminance(candidate), bg) < 4.5; step++) {
            candidate = lightBackground ? scaled(candidate, 0.86) : lightened(candidate, 0.14);
        }
        // A hue with nowhere left to go — a mid grey against a mid background — gives up and takes the
        // guaranteed one rather than shipping something almost readable.
        return contrast(luminance(candidate), bg) >= 4.5
                ? candidate
                : (lightBackground ? NEAR_BLACK : WHITE);
    }

    /** Each channel towards black by a factor. */
    private static String scaled(String hex, double factor) {
        int[] rgb = channels(hex);
        return hex(rgb[0] * factor, rgb[1] * factor, rgb[2] * factor);
    }

    /** Each channel a fraction of the way towards white. */
    private static String lightened(String hex, double amount) {
        int[] rgb = channels(hex);
        return hex(rgb[0] + (255 - rgb[0]) * amount,
                rgb[1] + (255 - rgb[1]) * amount,
                rgb[2] + (255 - rgb[2]) * amount);
    }

    private static String hex(double r, double g, double b) {
        return String.format("#%02x%02x%02x", clamp(r), clamp(g), clamp(b));
    }

    private static int clamp(double value) {
        return Math.max(0, Math.min(255, (int) Math.round(value)));
    }

    private static int[] channels(String hex) {
        String h = expand(hex);
        return new int[] {
                Integer.parseInt(h.substring(0, 2), 16),
                Integer.parseInt(h.substring(2, 4), 16),
                Integer.parseInt(h.substring(4, 6), 16)};
    }

    private static double contrast(double a, double b) {
        double lighter = Math.max(a, b);
        double darker = Math.min(a, b);
        return (lighter + 0.05) / (darker + 0.05);
    }

    /** WCAG 2.1 relative luminance. */
    private static double luminance(String hex) {
        String h = expand(hex);
        double r = channel(Integer.parseInt(h.substring(0, 2), 16));
        double g = channel(Integer.parseInt(h.substring(2, 4), 16));
        double b = channel(Integer.parseInt(h.substring(4, 6), 16));
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** {@code #abc} is {@code #aabbcc}: each digit doubled, not padded with zeroes. */
    private static String expand(String hex) {
        String h = hex.substring(1);
        if (h.length() != 3) return h;
        StringBuilder expanded = new StringBuilder(6);
        for (char c : h.toCharArray()) expanded.append(c).append(c);
        return expanded.toString();
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private static String orDefault(String value, ConfigKey key) {
        String trimmed = trimmed(value);
        return trimmed.isEmpty() ? key.getDefaultValue() : trimmed;
    }

    private static String stripTrailingSlash(String url) {
        String trimmed = trimmed(url);
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
