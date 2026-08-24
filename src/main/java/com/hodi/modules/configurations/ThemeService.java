package com.hodi.modules.configurations;

import com.hodi.enums.ConfigKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * The brand, assembled once and served to two callers.
 *
 * <p>The anonymous marketplace fetches it before there is a session, and the workspace fetches it again once
 * there is one. Both go through {@link ConfigurationService}, which resolves <em>tenant override ?? global</em>
 * — so the same code answers "what does this marketplace look like" and "what does this seller's workspace look
 * like" depending only on whether a tenant is bound to the request.
 *
 * <p>It exists as a service rather than inline in the two controllers because the shape is a contract with the
 * client's theme store: a second copy of this map is a second place for a key to be forgotten, and the symptom
 * would be a colour that changes when somebody signs in.
 */
@Service
@RequiredArgsConstructor
public class ThemeService {

    private final ConfigurationService configs;

    public Map<String, Object> theme() {
        Map<String, Object> theme = new HashMap<>();
        theme.put("primary", configs.getString(ConfigKey.THEME_PRIMARY));
        theme.put("accent", configs.getString(ConfigKey.THEME_ACCENT));
        theme.put("accentLight", emptyToNull(configs.getString(ConfigKey.THEME_ACCENT_LIGHT)));
        theme.put("ink", configs.getString(ConfigKey.THEME_INK));
        theme.put("logoUrl", emptyToNull(configs.getString(ConfigKey.THEME_LOGO_URL)));
        theme.put("logoMarkUrl", emptyToNull(configs.getString(ConfigKey.THEME_LOGO_MARK_URL)));
        theme.put("faviconUrl", emptyToNull(configs.getString(ConfigKey.THEME_FAVICON_URL)));
        theme.put("appName", configs.getString(ConfigKey.COMPANY_NAME));
        theme.put("darkEnabled", configs.getBoolean(ConfigKey.THEME_DARK_ENABLED));
        theme.put("fieldHints", configs.getBoolean(ConfigKey.UI_FIELD_HINTS));
        theme.put("contactEmail", emptyToNull(configs.getString(ConfigKey.COMPANY_EMAIL)));
        theme.put("contactPhone", emptyToNull(configs.getString(ConfigKey.COMPANY_PHONE)));
        theme.put("contactAddress", emptyToNull(configs.getString(ConfigKey.COMPANY_ADDRESS)));
        return theme;
    }

    /** An unset key and an empty one mean the same thing to the client: fall back to the built-in. */
    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
