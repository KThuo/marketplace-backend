package com.hodi.modules.auth;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * One refresh token, and therefore one session. The server half of the sliding idle window
 * (plan section 5).
 *
 * <p>Opaque random string rather than a JWT, which is the whole point: a JWT is valid because it verifies,
 * so it cannot be taken back, whereas this is valid because a row says so. That is what makes rotation,
 * revocation and reuse detection possible at all.
 *
 * <p>Only the SHA-256 hash is stored. A stolen database therefore yields no usable sessions, and SHA-256
 * rather than bcrypt because this is a 48-byte random value looked up by equality on every refresh — a
 * deliberately slow hash protects nothing here (there is no low-entropy secret to brute-force) and would put
 * a bcrypt round on the hot path.
 *
 * <p>{@link #expiresAt} is the idle window plus a small grace, <strong>not</strong> a long fixed lifetime.
 * That inversion is what makes the window real: an unused token dies on its own, and rotation on every use
 * is what lets an active client keep pushing the deadline forward.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RefreshToken {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable public identifier for this session, safe to log and to name in {@link #replacedBy}. */
    @Column(nullable = false, unique = true, length = 32) private String jti;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64) private String tokenHash;

    @Column(name = "user_id", nullable = false) private Long userId;

    /** {@code ADMIN} or {@code BUYER} — decides which idle window governs the rotation. */
    @Column(name = "session_class", nullable = false, length = 16) private String sessionClass;

    @Column(name = "expires_at", nullable = false) private OffsetDateTime expiresAt;

    @Column(nullable = false) @Builder.Default private boolean revoked = false;
    @Column(name = "revoked_at") private OffsetDateTime revokedAt;

    /**
     * The {@code jti} of the token that replaced this one.
     *
     * <p>Set on every rotation, and it is what makes reuse detectable: a revoked token that names a
     * successor was rotated normally, so presenting it again means two parties hold the same token — a stale
     * tab, or a theft. We cannot tell which, so the safe reading is taken.
     */
    @Column(name = "replaced_by", length = 32) private String replacedBy;

    @Column(name = "user_agent", length = 512) private String userAgent;
    @Column(name = "ip_address", length = 64) private String ipAddress;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Not revoked and not yet expired. Both halves are checked against the clock on every refresh. */
    public boolean isUsable() {
        return !revoked && expiresAt != null && expiresAt.isAfter(OffsetDateTime.now());
    }
}
