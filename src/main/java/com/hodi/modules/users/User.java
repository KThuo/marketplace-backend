package com.hodi.modules.users;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * Every person who can sign in: platform staff, seller staff, the bank's staff and buyers alike.
 *
 * <p><strong>One identity table, four populations.</strong> A separate buyer table was the obvious
 * alternative and the wrong one: it would mean two login pipelines, two password policies, two session
 * models and two places to remember to check whether an account is locked. What differs between the
 * populations is not how they authenticate but what they may then reach.
 *
 * <p><strong>This row is a credential, not an actor.</strong> Which kind of user somebody is, which group
 * of permissions they hold and which organisation they belong to all live on {@link
 * com.hodi.modules.profiles.UserProfile} — one row per profile, so the same person can be a buyer and an
 * approved seller at once (BRD FR073). Nothing here answers an authorisation question; everything here
 * answers "is this really them, and may they log in at all".
 *
 * <p>What stays is therefore identity (name, email, phone, avatar), the credential (password, history,
 * expiry), the second factor, the lockout state, and the session cutoff — all of which are properties of
 * the person however many profiles they hold. A locked account is locked on every profile, which is the
 * behaviour anybody would expect and the reason lockout did not move.
 */
@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class User {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "first_name", nullable = false, length = 64) private String firstName;
    @Column(name = "last_name", nullable = false, length = 64)  private String lastName;

    /** Stored lower-case; a database CHECK enforces it so the service cannot forget. */
    @Column(nullable = false, unique = true, length = 128) private String email;

    @Column(nullable = false, unique = true, length = 64) private String username;
    @Column(length = 32) private String phone;

    /** BCrypt hash. Never logged: PayloadSanitizer redacts anything named like a password. */
    @Column(nullable = false, length = 128) private String password;

    /**
     * Profile picture, as a storage key rather than a URL.
     *
     * <p>{@code storage.s3.bucket} decides whether the file sits on local disk or in object storage, and
     * {@code StorageService.urlFor} recomputes the address on read — so a row holding a URL would freeze one
     * deployment's arrangement into the data.
     */
    @Column(name = "avatar_key", length = 512) private String avatarKey;

    @Column(name = "totp_enabled", nullable = false) @Builder.Default private boolean totpEnabled = false;
    @Column(name = "totp_secret", length = 255) private String totpSecret;
    @Column(name = "totp_confirmed_at") private OffsetDateTime totpConfirmedAt;
    @Column(name = "sms_otp_enabled", nullable = false) @Builder.Default private boolean smsOtpEnabled = false;

    @Column(nullable = false) @Builder.Default private boolean locked = false;
    @Column(name = "locked_until") private OffsetDateTime lockedUntil;
    @Column(name = "failed_attempts", nullable = false) @Builder.Default private Integer failedAttempts = 0;
    @Column(nullable = false) @Builder.Default private boolean enabled = true;
    @Column(name = "must_change_password", nullable = false) @Builder.Default private boolean mustChangePassword = false;
    @Column(name = "last_login") private OffsetDateTime lastLogin;

    /**
     * Whether this account may still choose its own username.
     *
     * <p>Set true by the one login that found {@code last_login} null, and false by every login after it — so
     * the window is the first session and nothing else. Cleared the moment a username is chosen.
     *
     * <p>Why so narrow: a username is what names this person in the audit log and in every
     * {@code created_by} and {@code updated_by} column in the schema. Somebody who can rename themselves
     * after acting can make their own trail hard to follow — act as one name, answer as another. Before the
     * first sign-in there is no trail to confuse.
     */
    @Column(name = "username_changeable", nullable = false)
    @Builder.Default private boolean usernameChangeable = false;

    @Column(name = "password_changed_at") private OffsetDateTime passwordChangedAt;
    @Column(name = "password_expires_at") private OffsetDateTime passwordExpiresAt;

    /**
     * Access tokens issued before this instant are refused.
     *
     * <p>Revoking refresh tokens only stops a session being renewed — the access token already in somebody's
     * hands stays valid for the rest of its window. This is the cutoff that invalidates those too, and it
     * reaches <em>every</em> outstanding token for this user rather than only the one a caller happened to
     * present, which is something blacklisting cannot do because we never held the others.
     *
     * <p>Bumped on password change, administrator reset, and revoke-all-sessions. Null means no cutoff.
     */
    @Column(name = "sessions_valid_from") private OffsetDateTime sessionsValidFrom;

    /**
     * Verification stamps, meaningful only for self-registered buyers.
     *
     * <p>Nullable rather than boolean-plus-timestamp: the fact worth keeping is <em>when</em> somebody proved
     * they own an address, and a boolean beside it is a second copy of the same fact that can disagree with
     * it.
     */
    @Column(name = "email_verified_at") private OffsetDateTime emailVerifiedAt;
    @Column(name = "phone_verified_at") private OffsetDateTime phoneVerifiedAt;

    @Column(nullable = false) @Builder.Default private Integer status = 1;
    @Column(name = "status_flag", nullable = false, length = 32) @Builder.Default private String statusFlag = "Active";
    @Column(name = "deactivation_reason", columnDefinition = "TEXT") private String deactivationReason;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    /**
     * Generated by Postgres ({@code GENERATED ALWAYS AS … STORED}) and backed by a pg_trgm GIN index.
     * Read-only from the application — the database owns it, so it cannot drift out of step with the row.
     *
     * <p>The person only: name, username, email, phone. The role and organisation labels somebody also
     * searches by moved to {@code user_profiles.search_text} with the columns they describe.
     */
    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;

    /**
     * The last nine digits of {@link #phone}, generated by Postgres and indexed.
     *
     * <p>Exists so password recovery can find somebody by the number they type without caring whether they
     * typed the country code, the trunk zero or a space every three digits. Read-only here: the database
     * derives it, so it cannot disagree with the number it came from.
     */
    @Column(name = "phone_local", insertable = false, updatable = false)
    private String phoneLocal;

    public String fullName() {
        return ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
    }

    /** A lock that has passed its expiry is no longer a lock. */
    public boolean isCurrentlyLocked() {
        if (!locked) return false;
        return lockedUntil == null || lockedUntil.isAfter(OffsetDateTime.now());
    }
}
