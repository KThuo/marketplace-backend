package com.hodi.modules.auth;

import com.hodi.common.AppConstant;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The refresh-token cookie, and why there are two of them.
 *
 * <p>Hodi serves the public marketplace, a buyer's own area and the staff back office from one origin
 * (plan section 1). One cookie name would mean a buyer signing in on a shared machine silently ends the
 * agent's session on the next tab, and — worse — that whichever session refreshed last owns the cookie.
 * Two names let the two coexist, and make "which session is this" a property of the request rather than a
 * race.
 *
 * <p><strong>httpOnly, always.</strong> The refresh token is the long-lived half of the pair; script must
 * not be able to read it. That is also why the access token lives only in the client's memory: between them,
 * an XSS on this origin gets at most the remainder of one idle window rather than a renewable session.
 *
 * <p>{@code SameSite=Lax} rather than {@code Strict} so a link from an email — a password reset, a
 * verification prompt — lands on a page that can still refresh. {@code Secure} is configuration, off only so
 * that plain-http local development works; anything reachable from outside a laptop sets it.
 */
@Slf4j
@Component
public class RefreshCookies {

    /** Scoped to the auth paths: no other endpoint needs it, so no other endpoint receives it. */
    private static final String PATH = "/api/v1/auth";

    @Value("${hodi.auth.cookie.staff:hodi_rt}")
    private String staffCookie;

    @Value("${hodi.auth.cookie.buyer:hodi_brt}")
    private String buyerCookie;

    @Value("${hodi.auth.cookie.secure:false}")
    private boolean secure;

    @Value("${hodi.auth.cookie.same-site:Lax}")
    private String sameSite;

    public String nameFor(String sessionClass) {
        return AppConstant.SESSION_CLASS_BUYER.equals(sessionClass) ? buyerCookie : staffCookie;
    }

    /** The Set-Cookie header value for a freshly issued or rotated token. */
    public String build(String sessionClass, String rawToken, long maxAgeSeconds) {
        return ResponseCookie.from(nameFor(sessionClass), rawToken)
                .httpOnly(true)
                .secure(secure)
                .path(PATH)
                .sameSite(sameSite)
                // Matches the server-side row's TTL — the window plus grace. The row remains the enforcer;
                // this only stops the browser from sending something already dead.
                .maxAge(Duration.ofSeconds(maxAgeSeconds))
                .build()
                .toString();
    }

    /** A cookie that expires immediately, for logout. */
    public String clear(String sessionClass) {
        return ResponseCookie.from(nameFor(sessionClass), "")
                .httpOnly(true)
                .secure(secure)
                .path(PATH)
                .sameSite(sameSite)
                .maxAge(Duration.ZERO)
                .build()
                .toString();
    }

    /**
     * Reads the refresh token off a request.
     *
     * <p>Prefers the class the caller names, and falls back to whichever cookie is present. The fallback is
     * what makes a plain {@code POST /auth/refresh} work from either surface without the client having to
     * remember which kind of session it is holding — and if both are present, the requested class decides,
     * which is the only unambiguous reading.
     */
    public Presented read(HttpServletRequest request, String requestedClass) {
        String staff = cookieValue(request, staffCookie);
        String buyer = cookieValue(request, buyerCookie);

        if (AppConstant.SESSION_CLASS_BUYER.equalsIgnoreCase(requestedClass) && buyer != null) {
            return new Presented(buyer, AppConstant.SESSION_CLASS_BUYER);
        }
        if (AppConstant.SESSION_CLASS_ADMIN.equalsIgnoreCase(requestedClass) && staff != null) {
            return new Presented(staff, AppConstant.SESSION_CLASS_ADMIN);
        }
        if (staff != null) return new Presented(staff, AppConstant.SESSION_CLASS_ADMIN);
        if (buyer != null) return new Presented(buyer, AppConstant.SESSION_CLASS_BUYER);
        return new Presented(null, null);
    }

    public record Presented(String token, String sessionClass) {
        public boolean isEmpty() {
            return token == null || token.isBlank();
        }
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        if (request.getCookies() == null) return null;
        for (var cookie : request.getCookies()) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null
                    && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }

    /** Convenience for the controllers, which all set the header the same way. */
    public static String header() {
        return HttpHeaders.SET_COOKIE;
    }
}
