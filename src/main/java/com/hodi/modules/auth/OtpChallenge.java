package com.hodi.modules.auth;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * A one-time code the holder must produce to finish something: the second half of a challenged login, or a
 * buyer proving they own the email address or phone number they registered with.
 *
 * <p>One table for both, because they are the same mechanism — issue a code, tie it to a purpose and a user,
 * count the wrong guesses, expire it — and two tables would mean two places to get the attempt counting
 * wrong. {@link #purpose} keeps them from being interchangeable: a code issued to verify an email address
 * cannot be presented to complete a login.
 *
 * <p>The {@link #challengeToken} is what the client holds between the two halves of a login. It is not a
 * session and grants nothing: it names a challenge, and the challenge is only useful with the code, which
 * went to a different channel.
 */
@Entity
@Table(name = "otp_challenges")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OtpChallenge {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Opaque handle returned to the client in place of a session. */
    @Column(name = "challenge_token", nullable = false, unique = true, length = 64)
    private String challengeToken;

    @Column(name = "user_id", nullable = false) private Long userId;

    /** {@code LOGIN}, {@code EMAIL_VERIFY} or {@code PHONE_VERIFY}. */
    @Column(nullable = false, length = 24) private String purpose;

    /** {@code TOTP}, {@code SMS} or {@code EMAIL} — where the code came from, so the UI can say so. */
    @Column(nullable = false, length = 16) private String channel;

    /**
     * SHA-256 of the code. Null for a TOTP challenge: there is nothing to store, because the code is derived
     * from the shared secret at the moment it is checked rather than issued by us.
     */
    @Column(name = "code_hash", length = 64) private String codeHash;

    /** Where the code was sent, masked for display ({@code j••@example.com}). */
    @Column(name = "sent_to_masked", length = 128) private String sentToMasked;

    @Column(nullable = false) @Builder.Default private Integer attempts = 0;

    @Column(name = "expires_at", nullable = false) private OffsetDateTime expiresAt;
    @Column(name = "consumed_at") private OffsetDateTime consumedAt;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    public boolean isUsable() {
        return consumedAt == null && expiresAt != null && expiresAt.isAfter(OffsetDateTime.now());
    }
}
