package com.hodi.security.jwt;

import com.hodi.modules.users.UserRepository;
import com.hodi.security.principal.PrincipalFactory;
import com.hodi.security.principal.UserPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates a bearer token per request.
 *
 * <p><strong>Reloads the user on every request rather than trusting claims for authorisation.</strong> That
 * costs a query and buys the property the whole access model depends on: a deactivated account, a revoked
 * role, a module switched off for an organisation, or — the one unique to Hodi — a <em>revoked
 * partnership</em> takes effect on the caller's very next request instead of whenever their token happens to
 * expire. Since a partnership is the only thing granting a lender's staff sight of a seller's rows, revoking
 * one has to bite immediately, and that is only true if the visible-tenant set is rebuilt per request.
 *
 * <p>A blacklisted token is rejected even if still cryptographically valid — that is how logout invalidates
 * an access token that has not yet expired.
 *
 * <p>The username claim is checked against the loaded row. It catches an id that has been reused — a
 * restored backup, a re-seeded database — where the row at that id is now somebody else.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final TokenBlacklistService blacklist;
    private final UserRepository users;
    private final PrincipalFactory principals;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = bearer(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(request, token);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        try {
            if (blacklist.isBlacklisted(token)) {
                log.debug("Rejected a blacklisted token");
                return;
            }

            // Parsed once. This verifies signature, expiry and issuer.
            Claims claims = jwt.parse(token);

            Long userId = jwt.userId(claims);
            UserPrincipal principal = users.findById(userId).map(principals::build).orElse(null);
            if (principal == null) {
                log.debug("Token subject {} does not exist", userId);
                return;
            }

            String claimedUsername = jwt.username(claims);
            if (claimedUsername == null || !claimedUsername.equalsIgnoreCase(principal.getUsername())) {
                log.warn("Token subject {} no longer names the same user — refused", userId);
                return;
            }

            if (!principal.isEnabled() || !principal.isAccountNonLocked()) {
                log.debug("Token subject {} is disabled or locked", userId);
                return;
            }

            /*
             * A token minted before this account's cutoff is refused.
             *
             * Revoking refresh tokens stops renewal but leaves the access token in somebody's hands valid for
             * the rest of its window — so a password change or a revoke-all left a usable credential alive
             * for up to twenty minutes, which is the window that matters when either action is a response to
             * a suspected compromise. Blacklisting only reaches the token we were handed; this reaches all of
             * them.
             */
            if (principal.wasIssuedBeforeCutoff(jwt.issuedAt(claims))) {
                log.debug("Token for user {} predates the session cutoff — refused", userId);
                return;
            }

            /*
             * Buyer verification is deliberately NOT checked here.
             *
             * It used to be, and the effect was that an unverified buyer authenticated nothing at all — every
             * request came back 401, including /auth/me, so the client could not discover why it was refused
             * and could not render the prompt that resolves it. BuyerVerificationRequiredFilter now handles
             * it with an allowlist, which is the same shape as the forced-password-change rule and for the
             * same reason.
             */

            var auth = new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities());
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
        } catch (JwtException | IllegalArgumentException e) {
            // Expired, malformed, wrong issuer, or missing a required claim: leave the context
            // unauthenticated and let the entry point answer 401. The client's refresh flow takes it
            // from there.
            log.debug("Token rejected: {}", e.getMessage());
        }
    }

    private String bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }
}
