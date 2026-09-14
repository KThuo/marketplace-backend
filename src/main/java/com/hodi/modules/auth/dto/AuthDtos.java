package com.hodi.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Request and response shapes for the auth module.
 *
 * <p>Grouped in one file rather than one record per file: they are read together, they change together, and
 * fourteen single-record files is navigation cost with no benefit.
 */
public final class AuthDtos {

    private AuthDtos() {}

    // ── login ─────────────────────────────────────────────────────────────────

    /**
     * @param username either a username or an email — the form accepts both, so the field cannot be named
     *                 for only one of them without lying to whoever reads the API docs
     * @param sessionClass {@code ADMIN} or {@code BUYER}; decides the idle window and which refresh cookie is
     *                     set. Absent means ADMIN, the stricter of the two.
     */
    public record LoginRequest(
            @NotBlank(message = "Username or email is required") String username,
            @NotBlank(message = "Password is required") String password,
            String sessionClass) {}

    /**
     * The one shape both login and refresh return, so the client has a single thing to handle.
     *
     * @param sessionTimeoutSeconds the idle window W, so the client never hardcodes it. This is what makes the
     *                              window admin-tunable at runtime rather than a constant compiled into two
     *                              codebases that then disagree.
     * @param requiresOtp when true, everything else is empty except {@code otpToken} — a challenge was issued
     *                    and no session exists yet
     */
    public record LoginResponse(
            String accessToken,
            long accessTokenExpiresIn,
            long sessionTimeoutSeconds,
            boolean requiresOtp,
            boolean mustChangePassword,
            boolean mustSetupTotp,
            boolean firstSignIn,
            String otpToken,
            String otpChannel,
            String otpSentTo,
            MeResponse user) {

        /** A challenge rather than a session: no token, and nothing for the client to store. */
        public static LoginResponse challenge(long sessionTimeoutSeconds, String otpToken,
                                              String otpChannel, String otpSentTo) {
            return new LoginResponse(null, 0, sessionTimeoutSeconds, true, false, false, false,
                    otpToken, otpChannel, otpSentTo, null);
        }
    }

    public record VerifyOtpRequest(
            @NotBlank(message = "The challenge has expired — sign in again") String otpToken,
            @NotBlank(message = "Enter the code") String code) {}

    // ── identity ──────────────────────────────────────────────────────────────

    /**
     * Who the caller is, and everything the client needs to decide what to render.
     *
     * <p>{@code permissions} is the resolved effective set — already the intersection of the group's grants,
     * the modules enabled for the organisation, and the modules that admit this user type. The client gates
     * nav and routes on it, and does not attempt to re-derive it.
     *
     * <p>{@code visibleTenants} is the other half of the access model, and it is here so the UI can be honest
     * about it: a bank's officer sees which sellers they may work, and the bank admin with none sees a
     * "no partnerships yet" state rather than empty tables that look like a bug.
     */
    public record MeResponse(
            String id,
            /** The active profile. Everything below that describes an actor was resolved from it. */
            String profileId,
            String firstName,
            String lastName,
            String fullName,
            String email,
            String username,
            String phone,
            String avatarUrl,
            String userTypeCode,
            String userTypeName,
            String actorClass,
            String userGroupName,
            String tenantId,
            String tenantName,
            String institutionId,
            String institutionName,
            boolean unrestrictedTenants,
            List<VisibleTenant> visibleTenants,
            boolean totpEnabled,
            boolean totpConfirmed,
            boolean smsOtpEnabled,
            boolean orgRequiresTotp,
            boolean mustChangePassword,
            boolean usernameChangeable,
            boolean emailVerified,
            boolean phoneVerified,
            /**
             * Whether this profile is actually being held back pending verification.
             *
             * <p>Not the same as {@code !emailVerified}, and the difference is the whole reason it is sent:
             * whether verification is <em>demanded</em> depends on two configuration keys and on whether this
             * person also holds a staff profile (a staff account was vouched for by whoever created it, and
             * never goes through the buyer verification flow at all). The client used to derive it from
             * {@code emailVerified} and trapped a staff member who had added a buyer profile on the "confirm
             * your email" screen, waiting for a code that was never going to be sent.
             */
            boolean verificationRequired,
            OffsetDateTime passwordExpiresAt,
            OffsetDateTime lastLogin,
            List<String> permissions,
            /**
             * Every profile this person holds, the active one included.
             *
             * <p>Sent on every {@code /me} rather than fetched on demand, because the switcher has to know
             * whether to render at all — and a person with one profile (almost everybody) must not pay a
             * request to discover they have nothing to switch to.
             */
            List<ProfileSummary> profiles) {}

    /**
     * One profile in the switcher.
     *
     * @param label what to show: the organisation, or "Platform"/"Buyer" for the two that have none
     */
    public record ProfileSummary(
            String id,
            String profileType,
            String userTypeCode,
            String userTypeName,
            String userGroupName,
            String label,
            String kycStatus,
            boolean active,
            boolean isDefault) {}

    /** Switching profile issues a new session on that profile; nothing about the old one is mutated. */
    public record SwitchProfileRequest(
            @NotBlank(message = "Choose a profile") String profileId) {}

    /** One organisation in the caller's view, named rather than numbered so the UI can say who it is. */
    public record VisibleTenant(String id, String name, String tenantRef) {}

    // ── password ──────────────────────────────────────────────────────────────

    public record ChangePasswordRequest(
            @NotBlank(message = "Enter your current password") String currentPassword,
            @NotBlank(message = "Enter a new password")
            @Size(max = 128, message = "That password is too long") String newPassword) {}

    /**
     * @param identifier an email address or a phone number (BRD FR007). Deliberately not validated as an
     *                   email: the field accepts both, and an {@code @Email} constraint here would reject
     *                   every phone number with a validation message that names the wrong thing.
     */
    public record ForgotPasswordRequest(
            @NotBlank(message = "Enter your email address or phone number") String identifier) {}

    public record ResetPasswordRequest(
            @NotBlank(message = "The reset link is incomplete") String code,
            @NotBlank(message = "Enter a new password")
            @Size(max = 128, message = "That password is too long") String newPassword) {}

    /**
     * The policy the client validates against, so its rules cannot drift from the server's.
     *
     * <p>Public and unauthenticated, because the reset-password screen needs it and whoever is on that screen
     * by definition cannot sign in. It reveals only what the password rules are, which every attempt would
     * reveal anyway.
     */
    public record PasswordPolicyResponse(
            int minLength,
            boolean requireUpper,
            boolean requireNumber,
            boolean requireSymbol,
            int historyCount,
            int expiryDays) {}

    public record ChooseUsernameRequest(
            @NotBlank(message = "Enter a username")
            @Size(min = 3, max = 64, message = "A username is between 3 and 64 characters")
            String username) {}

    // ── sessions ──────────────────────────────────────────────────────────────

    /** One live session, for the profile's device list. */
    public record SessionResponse(
            String id,
            String sessionClass,
            String userAgent,
            String ipAddress,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            boolean current) {}
}
