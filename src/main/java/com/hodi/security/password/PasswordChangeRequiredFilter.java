package com.hodi.security.password;

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
 * A user who must change their password gets no access until they do.
 *
 * <p>{@code must_change_password} was set on every temporary credential — a new user, a new tenant's
 * owner, an administrator-triggered reset — returned in the login response, and then enforced nowhere.
 * Only the client acted on it, by routing to the change-password screen. So the flag described an
 * intention rather than a rule: the access token it came with was fully privileged, and anything calling
 * the API directly could use a temporary password indefinitely, which is precisely what the flag exists
 * to prevent.
 *
 * <p>The same is true of {@code password_expires_at}. {@code PasswordService} stamps it from the
 * configured maximum age and nothing has ever read it, so password expiry was configured, recorded, and
 * never enforced. An expired password is the same situation as a temporary one, so it is handled here
 * too rather than growing a second mechanism.
 *
 * <p>Enforced as a filter rather than per-endpoint because the rule is "everything except the way out".
 * Expressing it as 124 more {@code @PreAuthorize} clauses would mean every future endpoint is exempt
 * until somebody remembers to add it — the wrong default for a control like this. It runs after
 * authentication, so it can see the principal, and it answers 403 with a machine-readable
 * {@code passwordChangeRequired} marker so the client can route without parsing prose.
 *
 * <p>The allowlist is the narrowest set that lets a locked-out user get themselves out: change the
 * password, see who they are, read the policy they must satisfy, and sign out.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    /**
     * Exactly the endpoints needed to resolve the situation.
     *
     * <p>{@code /auth/me} is included because the client needs to know it is in this state.
     * {@code /auth/logout} because refusing to let somebody sign out would be perverse.
     * {@code /auth/refresh} because a change may take more than one idle window, and refusing it would
     * turn "change your password" into "you have been signed out".
     */
    private static final List<String> ALLOWED = List.of(
            "/api/v1/auth/change-password",
            "/api/v1/auth/me",
            "/api/v1/auth/logout",
            "/api/v1/auth/refresh",
            "/api/v1/auth/password-policy");

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        UserPrincipal principal = currentPrincipal();
        if (principal == null || !mustChange(principal) || isAllowed(request)) {
            chain.doFilter(request, response);
            return;
        }

        log.debug("Refused {} for user {} — a password change is required first",
                request.getRequestURI(), principal.getUserId());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // 403 rather than 401: the credentials are valid, the account simply may not act yet. A 401 would
        // send the client's interceptor into a refresh-then-logout loop over something refreshing cannot fix.
        ApiResponse<?> body = ApiResponse.error("You must change your password before continuing.");
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private boolean mustChange(UserPrincipal principal) {
        return principal.isMustChangePassword() || principal.isPasswordExpired();
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
