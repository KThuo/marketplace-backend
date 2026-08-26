package com.hodi.infra.notify;

import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two properties of a themed email that are not obvious from reading it: <strong>a configured
 * colour cannot leave its style attribute</strong>, and <strong>text on the accent stays readable whatever
 * the accent is set to</strong>.
 *
 * <p>Both exist because the palette is editable at runtime. A test over the default palette would pass
 * forever and prove nothing about the case that matters, which is the value somebody types next year.
 */
class MailTemplateTest {

    /** A configuration service answering the catalogue defaults, with named keys overridden. */
    private static ConfigurationService configs(String... overrides) {
        ConfigurationService configs = Mockito.mock(ConfigurationService.class);
        Mockito.when(configs.getString(Mockito.any(ConfigKey.class)))
                .thenAnswer(call -> call.getArgument(0, ConfigKey.class).getDefaultValue());
        Mockito.when(configs.getBoolean(Mockito.any(ConfigKey.class)))
                .thenAnswer(call -> "true".equals(call.getArgument(0, ConfigKey.class).getDefaultValue()));
        for (int i = 0; i < overrides.length; i += 2) {
            ConfigKey key = ConfigKey.valueOf(overrides[i]);
            Mockito.when(configs.getString(key)).thenReturn(overrides[i + 1]);
        }
        return configs;
    }

    private static MailTemplate template(ConfigurationService configs) {
        return new MailTemplate(configs);
    }

    // ── the colour cannot become markup ───────────────────────────────────────

    @Test
    @DisplayName("a colour that is not a colour falls back instead of reaching the document")
    void colourInjectionIsRefused() {
        String attack = "#000\" onmouseover=\"alert(1)";
        MailPalette palette = MailPalette.from(configs("THEME_ACCENT", attack));

        assertEquals(ConfigKey.THEME_ACCENT.getDefaultValue(), palette.accent(),
                "a value that is not a hex colour must not be used at all");

        String html = template(configs("THEME_ACCENT", attack))
                .action("Ada", "Reset it.", "Choose a new password", "https://hodi.test/r/abc", "Once only.");
        assertFalse(html.contains("onmouseover"), "the attack string must not appear in the email");
    }

    @Test
    @DisplayName("shorthand hex is accepted, everything else is not")
    void hexValidation() {
        assertEquals("#abc", MailPalette.sanitise("#abc", "#fallback"));
        assertEquals("#A1B2C3", MailPalette.sanitise("  #A1B2C3  ", "#fallback"));
        for (String bad : new String[] {null, "", "abc", "#ab", "#abcd", "red", "rgb(0,0,0)", "#12345g"}) {
            assertEquals("#fallback", MailPalette.sanitise(bad, "#fallback"), "should reject: " + bad);
        }
    }

    // ── text on the accent stays readable ─────────────────────────────────────

    @Test
    @DisplayName("the text on a button is chosen against the accent, not assumed")
    void contrastFollowsTheAccent() {
        assertEquals("#ffffff", MailPalette.readableOn("#1B7F79"), "the configured teal carries white");
        assertEquals("#ffffff", MailPalette.readableOn("#0B2545"), "the ink certainly does");
        assertEquals("#0b2038", MailPalette.readableOn("#A8DAD5"), "a pale teal cannot carry white");
        assertEquals("#0b2038", MailPalette.readableOn("#4FB3A9"), "nor can the light accent");
    }

    @Test
    @DisplayName("a pale accent turns the button text dark rather than leaving it unreadable")
    void paleAccentInTheRenderedButton() {
        String html = template(configs("THEME_ACCENT", "#A8DAD5"))
                .action("Ada", "Reset it.", "Choose a new password", "https://hodi.test/r/abc", "Once only.");
        assertTrue(html.contains("background:#A8DAD5"), "the accent is still the button");
        assertTrue(html.contains("color:#0b2038"), "but the label is not white on a pale teal");
    }

    // ── escaping, and the shell ───────────────────────────────────────────────

    @Test
    @DisplayName("a listing title carrying markup arrives as text")
    void titlesAreEscaped() {
        String html = template(configs()).digest("Ada", "New listings match your search.",
                List.of(new MailTemplate.Card("https://hodi.test/property/P-1",
                        "<script>alert(1)</script> Bungalow", "Karen, Nairobi", "KES 24,000,000")),
                null, "");
        assertFalse(html.contains("<script>"), "the title must not arrive as an element");
        assertTrue(html.contains("&lt;script&gt;"), "it must still arrive as words");
    }

    @Test
    @DisplayName("every template renders one complete document with the brand in it")
    void everyTemplateIsAWholeDocument() {
        MailTemplate mail = template(configs());
        List<String> emails = List.of(
                mail.code("Ada", "Use this code.", "481920", "Expires in 10 minutes."),
                mail.action("Ada", "Reset it.", "Choose a new password", "https://hodi.test/r/a", "Once."),
                mail.notice("Ada", "Someone enquired about your listing.", "Open it", "https://hodi.test/e/1"),
                mail.digest("Ada", "New listings match.", List.of(new MailTemplate.Card(
                        "https://hodi.test/p/1", "Bungalow", "Karen", "KES 24,000,000")), null, ""));
        for (String html : emails) {
            assertTrue(html.startsWith("<!DOCTYPE html>"), html.substring(0, 40));
            assertTrue(html.endsWith("</html>"));
            assertTrue(html.contains(ConfigKey.THEME_PRIMARY.getDefaultValue()), "no brand primary");
            assertTrue(html.contains(ConfigKey.COMPANY_NAME.getDefaultValue()), "nothing says who sent it");
            assertFalse(html.contains("null"), "a null reached the document: " + html);
        }
    }

    @Test
    @DisplayName("the dark block is emitted only when the platform offers dark mode")
    void darkModeFollowsTheThemeSwitch() {
        ConfigurationService on = configs();
        assertTrue(template(on).notice("Ada", "Something happened.", "Open", "https://hodi.test/x")
                .contains("prefers-color-scheme: dark"));

        ConfigurationService off = configs();
        Mockito.when(off.getBoolean(ConfigKey.THEME_DARK_ENABLED)).thenReturn(false);
        String html = template(off).notice("Ada", "Something happened.", "Open", "https://hodi.test/x");
        assertFalse(html.contains("prefers-color-scheme"), "no dark block when dark mode is switched off");
        assertTrue(html.contains("content=\"light\""), "and the client is told not to invert it");
    }

    @Test
    @DisplayName("a missing first name does not greet nobody")
    void greetingWithoutAName() {
        String html = template(configs()).notice(null, "Something happened.", "Open", "https://hodi.test/x");
        assertTrue(html.contains("Hello there,"), "a blank name should read as a greeting, not a gap");
    }
}
