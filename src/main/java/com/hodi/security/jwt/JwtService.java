package com.hodi.security.jwt;

import com.hodi.common.AppConstant;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.users.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * HS256 access tokens. The lifetime <em>is</em> the idle window W for the session's client class
 * (plan section 5), read from configuration at issue time so a change in Settings applies to the next
 * login without a redeploy.
 *
 * <p>Refresh tokens are not JWTs — they are opaque random strings tracked in {@code refresh_tokens},
 * which is what lets them be revoked and rotated. See {@code RefreshTokenService}.
 *
 * <h2>What the token carries, and what it deliberately does not</h2>
 *
 * <p>Ids here are platform-wide: one schema, one sequence, so {@code sub} identifies a user outright and
 * none of the tenant/schema cross-checks axis needs are meaningful (plan section 11, deviation 2). What the
 * token still carries is the {@code sessionClass}, because the two classes have different idle windows and
 * different cookies, and the refresh path has to know which it is rotating.
 *
 * <p><strong>No authorisation claims.</strong> No permissions, no user type, no tenant. Authorities are
 * resolved from the database on every request by {@code JwtAuthenticationFilter} — a claim nobody verifies is
 * an invitation to start trusting it, and the whole point of rebuilding the principal per request is that
 * revoking a role or a partnership takes effect immediately rather than at token expiry.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    /** Claim names. Only the ones this service writes and the filter reads. */
    public static final String CLAIM_USERNAME = "username";
    public static final String CLAIM_SESSION_CLASS = "sessionClass";

    /**
     * Issued-at in epoch milliseconds.
     *
     * <p>A duplicate of {@code iat}, and necessary rather than redundant: the registered {@code iat} claim is
     * defined in whole seconds, so a token issued at 10:00:05.900 and one issued at 10:00:05.100 are
     * indistinguishable. That ambiguity is unresolvable for the session cutoff — a cutoff landing inside that
     * second either has to refuse a token that was actually issued after it (breaking the login somebody just
     * performed) or admit one issued before it (leaving a one-second window in which a revoked token still
     * works). Neither is acceptable, so the token carries the precision needed to tell them apart.
     */
    public static final String CLAIM_ISSUED_MS = "imt";

    private final ConfigurationService configs;

    @Value("${hodi.jwt.secret}")
    private String secret;

    @Value("${hodi.jwt.issuer:hodi-core}")
    private String issuer;

    /**
     * The idle window in minutes for a client class, from configuration.
     *
     * <p>Two classes, because one window is wrong across both: cash-free but account-sensitive back-office
     * work argues for a short window, and somebody comparing properties over an evening argues for a long
     * one. Anything unrecognised falls to {@code ADMIN}, which is the stricter of the two — an unknown class
     * should get less rope, not more.
     */
    public int windowMinutes(String sessionClass) {
        ConfigKey key = AppConstant.SESSION_CLASS_BUYER.equals(sessionClass)
                ? ConfigKey.SESSION_TIMEOUT_MINUTES_BUYER
                : ConfigKey.SESSION_TIMEOUT_MINUTES_ADMIN;
        return Math.max(1, configs.getInt(key));
    }

    public int refreshGraceSeconds() {
        return Math.max(0, configs.getInt(ConfigKey.SESSION_REFRESH_GRACE_SECONDS));
    }

    /** Access-token TTL equals the window: an idle client's token dies with the window. */
    public String generateAccess(User user, String sessionClass) {
        long ttlMs = windowMinutes(sessionClass) * 60_000L;
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .issuer(issuer)
                .subject(String.valueOf(user.getId()))
                .claim(CLAIM_USERNAME, user.getUsername())
                .claim(CLAIM_SESSION_CLASS, sessionClass)
                .claim(CLAIM_ISSUED_MS, now)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMs))
                .signWith(key())
                .compact();
    }

    /**
     * Verifies the signature, the expiry <em>and the issuer</em>.
     *
     * <p>The issuer check matters more than it looks: without it, any token signed with this secret parses
     * cleanly — including one minted by a different service that happens to share it.
     */
    public Claims parse(String token) throws JwtException {
        return Jwts.parser()
                .verifyWith(key())
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /** The subject as a user id. Throws {@link JwtException} rather than NumberFormatException on rubbish. */
    public Long userId(Claims claims) {
        try {
            return Long.valueOf(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new MalformedJwtException("Subject is not a user id");
        }
    }

    public String username(Claims claims) {
        return claims.get(CLAIM_USERNAME, String.class);
    }

    public String sessionClass(Claims claims) {
        String claimed = claims.get(CLAIM_SESSION_CLASS, String.class);
        return claimed == null ? AppConstant.SESSION_CLASS_ADMIN : claimed;
    }

    /**
     * When the token was minted, to the millisecond.
     *
     * <p>Falls back to the second-granular {@code iat} for a token issued before this claim existed, and to
     * null when neither is present. A caller comparing against a cutoff must treat null as "cannot tell" and
     * decide accordingly — {@code JwtAuthenticationFilter} treats it as refused.
     */
    public java.time.Instant issuedAt(Claims claims) {
        Long millis = claims.get(CLAIM_ISSUED_MS, Long.class);
        if (millis != null) return java.time.Instant.ofEpochMilli(millis);
        return claims.getIssuedAt() == null ? null : claims.getIssuedAt().toInstant();
    }

    public long remainingTtlMs(String token) {
        return Math.max(parse(token).getExpiration().getTime() - System.currentTimeMillis(), 0);
    }

    private SecretKey key() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("hodi.jwt.secret is not configured");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }
}
