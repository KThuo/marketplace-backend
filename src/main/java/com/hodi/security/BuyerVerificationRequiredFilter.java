package com.hodi.security;

import com.hodi.common.ApiResponse;
import com.hodi.security.principal.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;

/**
 * A buyer who has not confirmed the channels the configuration requires gets no access until they do.
 *
 * <h2>Why this is a filter and not a check inside the JWT filter</h2>
 *
 * <p>It started life there, and the effect was that an unverified buyer's token authenticated nothing at all:
 * every request, {@code /auth/me} included, came back 401. That is secure and unusable — the client had no
 * way to discover <em>why</em> it was being refused, so it could not render the "confirm your email" prompt
 * that resolves the situation. The only route out was the login response, which is gone as soon as the page
 * reloads.
 *
 * <p>Modelled on {@code PasswordChangeRequiredFilter} instead, and for the same reason: the rule is
 * "everything except the way out". A 403 with a machine-readable state lets the client route, and the
 * allowlist is the narrowest set that lets somebody finish verifying.
 *
 * <p>Staff are never affected. Verification applies only to self-registered buyers — a staff account was
 * created by somebody who had already authenticated, so a code sent to its own address proves nothing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuyerVerificationRequiredFilter extends OncePerRequestFilter {

    /**
     * Exactly what an unverified buyer needs.
     *
     * <p>{@code /auth/me} so the client can see the state and say so; the public verify endpoints so they can
     * finish; {@code /auth/logout} because refusing to let somebody sign out would be perverse; and
     * {@code /auth/refresh} because verifying an email can take longer than one idle window and being signed
     * out mid-way would send them back to the start.
     */
    private static final List<String> ALLOWED = List.of(
            "/api/v1/auth/me",
            "/api/v1/auth/logout",
            "/api/v1/auth/refresh",
            "/api/v1/public/");

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        UserPrincipal principal = currentPrincipal();
        if (principal == null || !principal.isBuyer() || principal.isVerified() || isAllowed(request)) {
            chain.doFilter(request, response);
            return;
        }

        log.debug("Refused {} for buyer {} — verification outstanding",
                request.getRequestURI(), principal.getUserId());
        // 403, not 401: the credentials are valid and refreshing changes nothing, so a 401 would send the
        // client's interceptor into a refresh-then-logout loop over something a refresh cannot fix.
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ApiResponse.error("Confirm your email address before continuing."));
    }

    private boolean isAllowed(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && ALLOWED.stream().anyMatch(path::startsWith);
    }

    private UserPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) return null;
        return principal;
    }
}
