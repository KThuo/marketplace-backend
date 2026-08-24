package com.hodi.modules.auth;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * A single-use password-reset grant.
 *
 * <p>Only the hash of the emailed code is stored, for the same reason as a refresh token: the row must not be
 * usable by whoever can read the table. {@link #usedAt} rather than deletion, so a reset link presented twice
 * can be distinguished from one that never existed — the first is worth a message telling somebody the link
 * has already been used, the second is not worth confirming at all.
 */
@Entity
@Table(name = "password_reset_tokens")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PasswordResetToken {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false) private Long userId;

    @Column(name = "code_hash", nullable = false, unique = true, length = 64) private String codeHash;

    @Column(name = "expires_at", nullable = false) private OffsetDateTime expiresAt;

    @Column(name = "used_at") private OffsetDateTime usedAt;

    @Column(name = "requested_ip", length = 64) private String requestedIp;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public boolean isUsable() {
        return usedAt == null && expiresAt != null && expiresAt.isAfter(OffsetDateTime.now());
    }
}
