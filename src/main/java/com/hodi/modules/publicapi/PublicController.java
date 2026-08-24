package com.hodi.modules.publicapi;

import com.hodi.common.ApiResponse;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.configurations.ThemeService;
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
    private final ThemeService themeService;

    /**
     * Brand tokens, so the marketplace and the login screen are themed before anybody signs in.
     *
     * <p>Unauthenticated by necessity: the first paint happens before there is a session, and a theme fetched
     * after authentication would mean every visitor sees the default palette flash first.
     *
     * <p><strong>The platform's brand, always.</strong> No tenant is bound on this path, so
     * {@code ConfigurationService} resolves the global layer — which is the right answer for a marketplace
     * shared by every seller. A seller's own palette is a property of their workspace, and the workspace asks
     * for it through {@code /api/v1/configurations/theme} once it knows who is asking.
     */
    @GetMapping("/theme")
    public ApiResponse<Map<String, Object>> theme() {
        return ApiResponse.success(themeService.theme());
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
}
