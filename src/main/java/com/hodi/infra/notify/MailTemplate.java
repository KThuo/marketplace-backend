package com.hodi.infra.notify;

import com.hodi.modules.configurations.ConfigurationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The house style for every email the platform sends.
 *
 * <p>Four templates over one shell: a {@link #code} for a verification code, an {@link #action} for a link
 * somebody has to click, a {@link #notice} for something that happened, and a {@link #digest} for a list of
 * things that did. The colours come from {@link MailPalette}, which is read per render, so an operator
 * changing {@code theme.accent} changes the next email without a deploy.
 *
 * <h3>Why the markup looks like 2004</h3>
 *
 * <p>Tables, inline styles, no external stylesheet, no web font, and nothing the recipient has to load before
 * the message reads. Outlook on Windows renders through the Word engine — no flexbox, no grid, no
 * {@code var()} — and Gmail strips much of what survives that. A shell built on CSS custom properties would
 * look right in a browser preview and arrive unstyled where most mail is actually read, which is why the
 * palette is interpolated here rather than declared as tokens in the document.
 *
 * <h3>Escaping</h3>
 *
 * <p>Every value a caller passes is escaped: names, listing titles and saved-search names are other people's
 * input, and a mail client renders whatever arrives. The one exception is the {@code footerHtml} argument,
 * which is trusted markup composed from static copy and {@link #link}; user input must never be passed there.
 */
@Component
@RequiredArgsConstructor
public class MailTemplate {

    /*
     * The neutral surfaces are fixed while the brand colours are configurable.
     *
     * An operator sets a brand, not a greyscale, and a configurable page background is the fastest way to an
     * unreadable email. The values are the frontend's own light tokens, so the mail and the app it links to
     * are recognisably the same surface.
     */
    private static final String PAGE = "#f1f4f8";
    private static final String CARD = "#ffffff";
    private static final String BORDER = "#e4e8ed";
    private static final String MUTED = "#5e7085";
    private static final String WELL = "#eef2f6";

    /** The frontend's dark tokens, for the clients that honour {@code prefers-color-scheme}. */
    private static final String DARK_PAGE = "#0a1a2b";
    private static final String DARK_CARD = "#0e2137";
    private static final String DARK_BORDER = "#1e3c5c";
    private static final String DARK_TEXT = "#e8eff6";
    private static final String DARK_MUTED = "#889bac";
    private static final String DARK_WELL = "#17334f";

    private static final String FONT = "Helvetica,Arial,sans-serif";

    private final ConfigurationService configs;

    /** One row in a {@link #digest}. */
    public record Card(String href, String title, String subtitle, String highlight) {}

    /**
     * The "there are more of these" line that closes a digest.
     *
     * <p>A record rather than a note and a URL as two more string parameters: the note is escaped and the
     * href is not the same kind of value, and the pair is meaningless apart. {@code null} for a digest that
     * showed everything it had.
     */
    public record More(String note, String label, String href) {}

    // ── the four templates ────────────────────────────────────────────────────

    /**
     * A verification code.
     *
     * <p>The code is a letter-spaced pill rather than a bold run of text, because it is going to be copied by
     * hand off a phone screen — and it is repeated nowhere else in the message, so there is nothing to copy by
     * mistake.
     */
    public String code(String firstName, String intro, String code, String expiryNote) {
        MailPalette palette = palette();
        String body = greeting(palette, firstName)
                + paragraph(palette, intro)
                + pill(palette, code)
                + muted(expiryNote)
                + divider()
                + muted("If you did not ask for this code, you can ignore this message — it expires on its "
                        + "own and nothing has changed.");
        return shell(palette, body, footerHtml(palette, "This is a security message about your account and "
                + "cannot be switched off."));
    }

    /** A link somebody has to click: password reset, and anything else with one obvious next step. */
    public String action(String firstName, String intro, String ctaLabel, String href, String note) {
        MailPalette palette = palette();
        String body = greeting(palette, firstName)
                + paragraph(palette, intro)
                + button(palette, ctaLabel, href)
                + muted(note);
        return shell(palette, body, footerHtml(palette, "This is a security message about your account and "
                + "cannot be switched off."));
    }

    /** Something happened that concerns the recipient, with a link to it. */
    public String notice(String firstName, String line, String ctaLabel, String href) {
        MailPalette palette = palette();
        String body = greeting(palette, firstName)
                + paragraph(palette, line)
                + button(palette, ctaLabel, href);
        return shell(palette, body, footerHtml(palette, "You are receiving this because it concerns something "
                + "you asked about. Messages of this kind cannot be switched off."));
    }

    /**
     * A list of things, each with its own link.
     *
     * <p>{@code footerHtml} is the caller's, because a digest is the one kind of message somebody can turn
     * off and the way to do that has to be in it. A preference people have to go looking for is a preference
     * they report as spam instead.
     */
    public String digest(String firstName, String intro, List<Card> cards, More more, String footerHtml) {
        MailPalette palette = palette();
        StringBuilder body = new StringBuilder(1024)
                .append(greeting(palette, firstName))
                .append(paragraph(palette, intro));
        for (Card card : cards) body.append(card(palette, card));
        if (more != null) body.append(moreLine(palette, more));
        return shell(palette, body.toString(), footerHtml(palette, null) + footerHtml);
    }

    // ── blocks ────────────────────────────────────────────────────────────────

    public MailPalette palette() {
        return MailPalette.from(configs);
    }

    /** An anchor with both halves escaped. The only sanctioned way to put a link in trusted markup. */
    public static String link(MailPalette palette, String label, String href) {
        return "<a class=\"lnk\" href=\"" + escape(href) + "\" style=\"color:" + palette.accentText()
                + ";text-decoration:underline\">" + escape(label) + "</a>";
    }

    private static String greeting(MailPalette palette, String firstName) {
        String name = firstName == null || firstName.isBlank() ? "there" : firstName;
        return paragraph(palette, "Hello " + name + ",");
    }

    private static String paragraph(MailPalette palette, String text) {
        return "<p class=\"txt\" style=\"margin:0 0 14px;color:" + palette.ink()
                + ";font-size:15px;line-height:1.55\">" + escape(text) + "</p>";
    }

    private static String pill(MailPalette palette, String code) {
        return "<div class=\"pill\" style=\"margin:4px 0 14px;padding:16px;background:" + WELL
                + ";border-radius:12px;text-align:center;font-size:28px;font-weight:700;letter-spacing:6px;"
                + "color:" + palette.ink() + "\">" + escape(code) + "</div>";
    }

    /**
     * The call to action.
     *
     * <p>A table rather than a styled anchor: Outlook drops padding on inline elements, and a button with no
     * padding is a word. The href is repeated underneath in plain text because a client that strips the button
     * still has to leave somebody a way to reset their password.
     */
    private static String button(MailPalette palette, String label, String href) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"margin:6px 0 16px\"><tr><td align=\"center\" style=\"background:"
                + palette.accent() + ";border-radius:10px\">"
                + "<a href=\"" + escape(href) + "\" style=\"display:inline-block;padding:13px 26px;color:"
                + palette.onAccent() + ";font-family:" + FONT
                + ";font-size:15px;font-weight:600;text-decoration:none\">" + escape(label)
                + "</a></td></tr></table>"
                + "<p class=\"muted\" style=\"margin:0 0 14px;color:" + MUTED
                + ";font-size:12px;line-height:1.5;word-break:break-all\">Or paste this into your browser: "
                + escape(href) + "</p>";
    }

    private static String card(MailPalette palette, Card card) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "class=\"card\" style=\"margin:0 0 10px;background:" + CARD + ";border:1px solid " + BORDER
                + ";border-radius:12px\"><tr><td style=\"padding:14px 16px\">"
                + "<a class=\"txt\" href=\"" + escape(card.href()) + "\" style=\"color:" + palette.ink()
                + ";font-size:15px;font-weight:600;text-decoration:none\">" + escape(card.title()) + "</a>"
                + "<div class=\"muted\" style=\"margin-top:3px;color:" + MUTED + ";font-size:13px\">"
                + escape(card.subtitle()) + "</div>"
                + "<div class=\"txt\" style=\"margin-top:6px;color:" + palette.ink()
                + ";font-size:15px;font-weight:700\">" + escape(card.highlight()) + "</div>"
                + "</td></tr></table>";
    }

    /**
     * The closing line, with the way to see the rest.
     *
     * <p>A digest that shows three of eight and does not link to the other five has told somebody they are
     * missing something and left them to go looking for it.
     */
    private static String moreLine(MailPalette palette, More more) {
        return "<p class=\"muted\" style=\"margin:0 0 12px;color:" + MUTED
                + ";font-size:13px;line-height:1.5\">" + escape(more.note()) + " "
                + link(palette, more.label(), more.href()) + "</p>";
    }

    private static String muted(String text) {
        if (text == null || text.isBlank()) return "";
        return "<p class=\"muted\" style=\"margin:0 0 12px;color:" + MUTED
                + ";font-size:13px;line-height:1.5\">" + escape(text) + "</p>";
    }

    private static String divider() {
        return "<div class=\"hr\" style=\"height:1px;margin:18px 0;background:" + BORDER + "\"></div>";
    }

    /** The "why you got this" sentence. {@code null} emits nothing, for a caller supplying its own. */
    private static String footerHtml(MailPalette palette, String sentence) {
        return sentence == null ? "" : "<p style=\"margin:0 0 8px\">" + escape(sentence) + "</p>";
    }

    // ── the shell ─────────────────────────────────────────────────────────────

    private String shell(MailPalette palette, String body, String footer) {
        String name = escape(palette.appName());
        String header = palette.logoUrl().isBlank()
                ? "<span style=\"color:" + palette.onPrimary()
                        + ";font-size:19px;font-weight:700;letter-spacing:-.2px\">" + name + "</span>"
                // Height in an attribute as well as the style: Outlook ignores one of the two.
                : "<img src=\"" + escape(palette.logoUrl()) + "\" alt=\"" + name
                        + "\" height=\"28\" style=\"height:28px;border:0;display:block\">";

        return "<!DOCTYPE html><html lang=\"en\"><head>"
                + "<meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + colourSchemeHead(palette)
                + "</head>"
                + "<body class=\"page\" style=\"margin:0;padding:0;background:" + PAGE + ";font-family:" + FONT
                + ";-webkit-text-size-adjust:100%\">"
                + "<table role=\"presentation\" class=\"page\" width=\"100%\" cellpadding=\"0\" "
                + "cellspacing=\"0\" border=\"0\" style=\"background:" + PAGE + "\"><tr>"
                + "<td align=\"center\" style=\"padding:26px 12px\">"
                + "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" "
                + "style=\"width:100%;max-width:600px\">"
                // Header band. background-color first, then the gradient: Outlook keeps the colour and
                // ignores the image, which is the right outcome rather than a fallback.
                + "<tr><td style=\"padding:22px 26px;background-color:" + palette.primary()
                + ";background-image:linear-gradient(135deg," + palette.primary() + " 0%,"
                + palette.accentLight() + " 140%);border-radius:16px 16px 0 0\">" + header + "</td></tr>"
                + "<tr><td class=\"card\" style=\"padding:26px;background:" + CARD + ";border:1px solid "
                + BORDER + ";border-top:0;border-radius:0 0 16px 16px\">" + body + "</td></tr>"
                + "<tr><td class=\"muted\" style=\"padding:16px 10px 0;color:" + MUTED
                + ";font-size:12px;line-height:1.55\">" + footer + contact(palette) + "</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    /**
     * The dark rendering, when the platform offers dark mode at all.
     *
     * <p>Governed by {@code theme.dark.enabled} — the same switch that offers the workspace its light/dark
     * toggle, which is what an operator would expect that switch to mean. The overrides need
     * {@code !important} to beat the inline styles, and a client that ignores {@code <style>} keeps the light
     * version intact: that is why the inline styles are the baseline and this is the addition.
     *
     * <p>Only the neutral surfaces move. The brand colours stay as configured, because an accent that shifts
     * with the reader's operating system is not the brand any more.
     */
    private static String colourSchemeHead(MailPalette palette) {
        if (!palette.darkEnabled()) {
            // Tell the client not to invert what it cannot improve.
            return "<meta name=\"color-scheme\" content=\"light\">"
                    + "<meta name=\"supported-color-schemes\" content=\"light\">";
        }
        return "<meta name=\"color-scheme\" content=\"light dark\">"
                + "<meta name=\"supported-color-schemes\" content=\"light dark\">"
                + "<style>@media (prefers-color-scheme: dark){"
                + "body.page,table.page{background:" + DARK_PAGE + " !important}"
                + ".card{background:" + DARK_CARD + " !important;border-color:" + DARK_BORDER + " !important}"
                + ".txt{color:" + DARK_TEXT + " !important}"
                + ".muted{color:" + DARK_MUTED + " !important}"
                + ".pill{background:" + DARK_WELL + " !important;color:" + DARK_TEXT + " !important}"
                + ".hr{background:" + DARK_BORDER + " !important}"
                // The link colour is computed against the dark card, not reused from the light one: a teal
                // darkened until it could be read on white is the wrong direction entirely on #0e2137.
                + ".lnk{color:" + MailPalette.readableTextOn(palette.accent(), DARK_CARD) + " !important}"
                + "}</style>";
    }

    /** Who sent it. An email with no postal identity behind it is the shape of a phishing attempt. */
    private static String contact(MailPalette palette) {
        StringBuilder details = new StringBuilder(160);
        details.append("<p style=\"margin:8px 0 0\">").append(escape(palette.appName()));
        if (!palette.contactAddress().isBlank()) {
            details.append(" · ").append(escape(palette.contactAddress()));
        }
        if (!palette.contactEmail().isBlank()) {
            details.append("<br>").append(link(palette, palette.contactEmail(),
                    "mailto:" + palette.contactEmail()));
        }
        if (!palette.contactPhone().isBlank()) {
            details.append(" · ").append(escape(palette.contactPhone()));
        }
        return details.append("</p>").toString();
    }

    /** A message body is somebody else's input and the recipient's client renders whatever arrives. */
    public static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
