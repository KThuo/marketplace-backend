package com.hodi.modules.auth;

import com.hodi.common.exception.UnauthorizedException;
import com.hodi.security.jwt.JwtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Issues, rotates and revokes refresh tokens — the server half of the sliding idle window
 * (plan section 5).
 *
 * <p>The whole mechanism rests on one choice: <strong>the refresh token's TTL is the idle window</strong>
 * (plus a small grace), not a fixed long constant. With a multi-day refresh token and a client that
 * silently refreshes on every 401, a session lasts as long as the refresh token regardless of whether
 * anybody touched the app, and the configured "session timeout" is decorative. Making the TTL equal the
 * window means an unused token dies on its own; rotating on every use means an active client keeps pushing
 * the deadline forward.
 *
 * <p>The grace exists so a refresh fired right at the boundary — during the client's "still there?"
 * warning — still succeeds. It does not extend the visible session: the client's own watchdog logs out at
 * exactly the window.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RefreshTokenRepository repository;
    private final JwtService jwt;
    private final TransactionTemplate newTransaction;

    @Value("${hodi.auth.refresh-reaper-cron:0 15 3 * * *}")
    private String reaperCron;

    /** A freshly issued token: the raw value goes to the cookie, the row keeps only its hash. */
    public record Issued(String raw, RefreshToken record, long maxAgeSeconds) {}

    @Transactional
    public Issued issue(Long userId, String sessionClass, String userAgent, String ip) {
        String raw = randomToken();
        int windowMinutes = jwt.windowMinutes(sessionClass);
        int grace = jwt.refreshGraceSeconds();
        OffsetDateTime expiresAt = OffsetDateTime.now().plusMinutes(windowMinutes).plusSeconds(grace);

        RefreshToken record = repository.save(RefreshToken.builder()
                .jti(newJti())
                .tokenHash(hash(raw))
                .userId(userId)
                .sessionClass(sessionClass)
                .expiresAt(expiresAt)
                .userAgent(truncate(userAgent, 512))
                .ipAddress(truncate(ip, 64))
                .build());
        return new Issued(raw, record, windowMinutes * 60L + grace);
    }

    /**
     * Validate a presented token and rotate it: the old row is revoked and a new token issued in the same
     * transaction, so a given refresh token is usable exactly once.
     *
     * @throws UnauthorizedException when the token is unknown, expired or already used
     */
    @Transactional
    public Rotation rotate(String presented, String userAgent, String ip) {
        RefreshToken existing = repository.findByTokenHash(hash(presented))
                .orElseThrow(() -> new UnauthorizedException("Session expired"));

        if (!existing.isUsable()) {
            /*
             * A revoked token presented again is either a stale tab or a stolen token. We cannot tell the
             * two apart, so we take the safe reading and kill every session for that user: a legitimate
             * user re-authenticates, a thief loses the foothold.
             *
             * In a SEPARATE transaction, because this method throws immediately afterwards. Revoking in the
             * current transaction would be rolled back by that throw — the caller would still get its 401,
             * and the sessions we meant to kill would quietly stay alive.
             */
            if (existing.getReplacedBy() != null) {
                log.warn("Refresh token reuse detected for user {} — revoking all sessions",
                        existing.getUserId());
                revokeAllInNewTransaction(existing.getUserId());
            }
            throw new UnauthorizedException("Session expired");
        }

        Issued replacement = issue(existing.getUserId(), existing.getSessionClass(), userAgent, ip);
        existing.setRevoked(true);
        existing.setRevokedAt(OffsetDateTime.now());
        existing.setReplacedBy(replacement.record().getJti());
        repository.save(existing);

        return new Rotation(existing.getUserId(), existing.getSessionClass(), replacement);
    }

    public record Rotation(Long userId, String sessionClass, Issued replacement) {}

    @Transactional
    public void revoke(String presented) {
        repository.findByTokenHash(hash(presented)).ifPresent(token -> {
            token.setRevoked(true);
            token.setRevokedAt(OffsetDateTime.now());
            repository.save(token);
        });
    }

    @Transactional
    public int revokeAllForUser(Long userId) {
        return repository.revokeAllForUser(userId, OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public List<RefreshToken> liveSessions(Long userId) {
        return repository.findLiveForUser(userId, OffsetDateTime.now());
    }

    /**
     * Revoke every session for a user in its own transaction, so the write survives a rollback of whatever
     * transaction is in progress. Used on the reuse-detection path, which throws.
     */
    private void revokeAllInNewTransaction(Long userId) {
        newTransaction.executeWithoutResult(status ->
                repository.revokeAllForUser(userId, OffsetDateTime.now()));
    }

    /**
     * Clears out tokens past their expiry. They are already unusable — {@code isUsable()} checks the clock —
     * so this is housekeeping, not a security control. Kept a day past expiry so a reuse-detection
     * investigation still has something to look at.
     */
    @Scheduled(cron = "${hodi.auth.refresh-reaper-cron:0 15 3 * * *}")
    @Transactional
    public void reapExpired() {
        int removed = repository.deleteExpired(OffsetDateTime.now().minusDays(1));
        if (removed > 0) log.info("Reaped {} expired refresh tokens", removed);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String randomToken() {
        byte[] bytes = new byte[48];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String newJti() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    /**
     * SHA-256, not bcrypt: this is a 48-byte high-entropy random token, not a user-chosen secret, and it is
     * looked up by equality on every refresh. A deliberately slow hash protects nothing here — there is no
     * low-entropy secret to brute-force — and would put a bcrypt round on the hot path.
     */
    static String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
