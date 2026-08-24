package com.hodi.modules.publicapi;

import com.hodi.common.ApiResponse;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * The unauthenticated bootstrap endpoints.
 *
 * <p>Everything here is readable by anyone, and everything here is checked against that standard. The theme is
 * brand tokens and a platform name; the contact details are the ones already printed on the marketplace. No
 * key marked {@code secret} is reachable from this controller, and adding one would be the mistake to watch
 * for — {@code ConfigurationService.getString} decrypts, so a secret exposed here would be exposed in the
 * clear.
 */
@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
public class PublicController {

    private final ConfigurationService configs;

    /**
     * Brand tokens, so the marketplace and the login screen are themed before anybody signs in.
     *
     * <p>Unauthenticated by necessity: the first paint happens before there is a session, and a theme fetched
     * after authentication would mean every visitor sees the default palette flash first.
     *
     * <p>Global-only in this phase. Hodi is one marketplace with one brand, so there is no tenant to resolve a
     * theme for — and because these keys are not overridable, an organisation cannot shadow them even if a
     * tenant were bound. Per-seller theming becomes meaningful alongside vanity seller hosts; when it does,
     * this endpoint grows a slug parameter and the keys become overridable, with no structural change.
     */
    @GetMapping("/theme")
    public ApiResponse<Map<String, Object>> theme() {
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
        return ApiResponse.success(theme);
    }

    /**
     * What the sign-up screen needs before it renders.
     *
     * <p>Whether registration is open at all, and which channels a new buyer will have to confirm — so the
     * form can ask for a phone number when one will be required rather than after. Read live from
     * configuration, so turning phone verification on changes the form without a deploy.
     */
    @GetMapping("/registration-policy")
    public ApiResponse<Map<String, Object>> registrationPolicy() {
        Map<String, Object> policy = new HashMap<>();
        policy.put("enabled", configs.getBoolean(ConfigKey.BUYER_SELF_REGISTRATION_ENABLED));
        policy.put("emailVerificationRequired",
                configs.getBoolean(ConfigKey.BUYER_EMAIL_VERIFICATION_REQUIRED));
        policy.put("phoneVerificationRequired",
                configs.getBoolean(ConfigKey.BUYER_PHONE_VERIFICATION_REQUIRED));
        return ApiResponse.success(policy);
    }

    /** Blank is not a value a client should have to special-case; null is. */
    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
