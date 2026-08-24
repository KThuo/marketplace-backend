package com.hodi.modules.users;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * Every person who can sign in: platform staff, seller staff, lender staff and buyers alike.
 *
 * <p><strong>One identity table, four populations.</strong> A separate buyer table was the obvious
 * alternative and the wrong one: it would mean two login pipelines, two password policies, two session
 * models and two places to remember to check whether an account is locked. What differs between the
 * populations is not how they authenticate but what they may then reach, and that is
 * {@code user_types.actor_class} plus permissions.
 *
 * <p><strong>At most one organisation.</strong> {@link #tenantId} is set for seller staff,
 * {@link #institutionId} for lender staff, and a database CHECK enforces that never both. Platform staff and
 * buyers carry neither — which is exactly why {@link #actorClass} is a stored label cache rather than
 * something inferred from these two columns: "both null" describes two completely different kinds of user,
 * and the inference that gets it wrong is the one that hands a buyer platform access.
 *
 * <p>The {@code *_code} / {@code *_name} columns are label caches (plan section 1): list screens render
 * without joining, and {@code userTypeCode} is also the key matched against a module's
 * {@code allowed_user_types}.
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

    @Column(name = "user_group_id")   private Long userGroupId;
    @Column(name = "user_group_name", length = 128) private String userGroupName;

    @Column(name = "user_type_id", nullable = false)   private Long userTypeId;
    @Column(name = "user_type_code", nullable = false, length = 32) private String userTypeCode;
    @Column(name = "user_type_name", length = 64) private String userTypeName;

    /**
     * Which population this user belongs to, copied from their user type.
     *
     * <p>A label cache with one unusual property: it is read on the hottest path in the application
     * ({@code PrincipalFactory} resolving the visible-tenant set) and it decides <em>structure</em> rather
     * than presentation. It is safe as a copy because a user type's actor class never changes — the seeder
     * treats it as part of the type's identity — and {@code UserService} re-stamps it whenever a user's type
     * is changed. It is still never the thing a permission check consults.
     */
    @Column(name = "actor_class", nullable = false, length = 16) private String actorClass;

    /** The seller organisation this user works for. Null for everyone else. */
    @Column(name = "tenant_id")   private Long tenantId;
    @Column(name = "tenant_name", length = 255) private String tenantName;

    /** The lending institution this user works for. Null for everyone else. */
    @Column(name = "institution_id") private Long institutionId;
    @Column(name = "institution_name", length = 255) private String institutionName;

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
     */
    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;

    public String fullName() {
        return ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
    }

    public boolean isPlatformActor() {
        return AppConstant.ACTOR_PLATFORM.equals(actorClass);
    }

    public boolean isSellerActor() {
        return AppConstant.ACTOR_SELLER.equals(actorClass);
    }

    public boolean isLenderActor() {
        return AppConstant.ACTOR_LENDER.equals(actorClass);
    }

    public boolean isBuyerActor() {
        return AppConstant.ACTOR_BUYER.equals(actorClass);
    }

    /** A lock that has passed its expiry is no longer a lock. */
    public boolean isCurrentlyLocked() {
        if (!locked) return false;
        return lockedUntil == null || lockedUntil.isAfter(OffsetDateTime.now());
    }
}
