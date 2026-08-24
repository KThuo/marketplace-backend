package com.hodi.modules.auth;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.UnauthorizedException;
import com.hodi.enums.ConfigKey;
import com.hodi.infra.storage.StorageService;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.auth.dto.AuthDtos.ChangePasswordRequest;
import com.hodi.modules.auth.dto.AuthDtos.LoginRequest;
import com.hodi.modules.auth.dto.AuthDtos.LoginResponse;
import com.hodi.modules.auth.dto.AuthDtos.MeResponse;
import com.hodi.modules.auth.dto.AuthDtos.PasswordPolicyResponse;
import com.hodi.modules.auth.dto.AuthDtos.SessionResponse;
import com.hodi.modules.auth.dto.AuthDtos.VerifyOtpRequest;
import com.hodi.modules.auth.dto.AuthDtos.VisibleTenant;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.tenants.Tenant;
import com.hodi.modules.tenants.TenantRepository;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.jwt.JwtService;
import com.hodi.security.jwt.TokenBlacklistService;
import com.hodi.security.password.PasswordService;
import com.hodi.security.principal.PrincipalFactory;
import com.hodi.security.principal.UserPrincipal;
import com.hodi.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Login, refresh, logout, password change and identity.
 *
 * <p>Every path that issues a session goes through {@link #issue}, so the sliding-window rules — access TTL
 * equals the window, refresh TTL equals the window plus grace, and {@code sessionTimeoutSeconds} returned to
 * the client — hold identically for a fresh login and a rotation. Two code paths that each build a session is
 * how the two drift apart, and the drift is invisible until somebody's session outlives its window.
 *
 * <p>When a second factor applies, login stops after the password check and returns a challenge token instead
 * of a session. The password is therefore never enough on its own, and the challenge is one-shot and
 * short-lived.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository users;
    private final TenantRepository tenants;
    private final StorageService storage;
    private final RefreshTokenService refreshTokens;
    private final JwtService jwt;
    private final PasswordService passwords;
    private final TokenBlacklistService blacklist;
    private final ConfigurationService configs;
    private final PrincipalFactory principals;
    private final OtpChallengeService otpChallenges;
    private final AuditService audit;

    /** What the controller needs to set the cookie alongside the JSON body. */
    public record SessionIssued(LoginResponse response, String refreshToken, String sessionClass,
                                long refreshMaxAgeSeconds) {

        /** A challenge sets no cookie: there is no session yet to carry. */
        static SessionIssued challengeOnly(LoginResponse response) {
            return new SessionIssued(response, null, null, 0);
        }
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Transactional
    public SessionIssued login(LoginRequest request, String userAgent, String ip) {
        String identifier = request.username() == null ? "" : request.username().trim();
        User user = users.findByUsernameIgnoreCaseOrEmail(identifier, identifier.toLowerCase())
                .orElse(null);

        if (user == null) {
            /*
             * Same message AND the same work. Bcrypt is deliberately slow, so a login that skips it answers
             * measurably faster than one that does not — which turns response time into an oracle for whether
             * an account exists. Burning one comparison against a fixed hash closes that.
             */
            log.debug("Login attempt for unknown identifier");
            passwords.wasteComparison(request.password());
            throw new UnauthorizedException("Invalid credentials");
        }

        /*
         * Policy is read under the user's own organisation, not the request's absent one.
         *
         * Password rules, lockout thresholds and session windows all resolve through ConfigurationService,
         * which layers a tenant override over the global value. At this point in the request nothing is bound
         * yet — TenantBindingFilter needs a principal, and there isn't one — so without this, every seller's
         * staff would authenticate under global policy and any override would apply from their second request
         * onwards but not their first.
         */
        return TenantContext.runAs(user.getTenantId(), user.getTenantName(),
                () -> authenticate(user, request, userAgent, ip));
    }

    private SessionIssued authenticate(User user, LoginRequest request, String userAgent, String ip) {
        assertUsable(user);

        if (!passwords.matches(request.password(), user.getPassword())) {
            registerFailure(user);
            throw new UnauthorizedException("Invalid credentials");
        }

        clearFailures(user);
        users.save(user);

        String sessionClass = resolveSessionClass(user, request.sessionClass());

        // A second factor means the password alone must not produce a session. Return a challenge and stop;
        // nothing is issued until the code is verified.
        if (requiresSecondFactor(user)) {
            var challenge = otpChallenges.issueLogin(user);
            return SessionIssued.challengeOnly(LoginResponse.challenge(
                    jwt.windowMinutes(sessionClass) * 60L,
                    challenge.token(), challenge.channel(), challenge.sentToMasked()));
        }
        return issue(user, sessionClass, userAgent, ip, true);
    }

    /**
     * Completes a challenged login.
     *
     * <p>The user is loaded from the challenge's binding, never from the request — a request that could name
     * its own user id alongside a challenge token would be a way to authenticate as somebody else with a code
     * meant for them.
     */
    @Transactional
    public SessionIssued verifyOtp(VerifyOtpRequest request, String userAgent, String ip) {
        Long userId = otpChallenges.boundUserId(request.otpToken());
        User user = users.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Session expired"));

        return TenantContext.runAs(user.getTenantId(), user.getTenantName(), () -> {
            otpChallenges.consume(request.otpToken(), request.code(),
                    OtpChallengeService.PURPOSE_LOGIN, user);
            assertUsable(user);
            return issue(user, resolveSessionClass(user, null), userAgent, ip, true);
        });
    }

    // ── refresh / logout ──────────────────────────────────────────────────────

    @Transactional
    public SessionIssued refresh(String presentedRefreshToken, String userAgent, String ip) {
        if (presentedRefreshToken == null || presentedRefreshToken.isBlank()) {
            throw new UnauthorizedException("Session expired");
        }
        var rotation = refreshTokens.rotate(presentedRefreshToken, userAgent, ip);
        User user = users.findById(rotation.userId())
                .orElseThrow(() -> new UnauthorizedException("Session expired"));

        return TenantContext.runAs(user.getTenantId(), user.getTenantName(), () -> {
            assertUsable(user);
            // The replacement token is already issued and persisted by rotate(); reuse it rather than
            // issuing a second one, or every refresh would leave an orphaned live session behind.
            return buildSession(user, rotation.sessionClass(), rotation.replacement(), false);
        });
    }

    @Transactional
    public void logout(String accessToken, String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokens.revoke(refreshToken);
        }
        if (accessToken != null && !accessToken.isBlank()) {
            try {
                // Blacklisted for exactly its remaining life: an access token that has not expired is still
                // cryptographically valid, and this is what makes "sign out" mean it immediately.
                blacklist.blacklist(accessToken, jwt.remainingTtlMs(accessToken));
            } catch (RuntimeException e) {
                // A token we cannot parse cannot be replayed either. Nothing to do, and no reason to fail a
                // logout over it — refusing to let somebody sign out would be perverse.
                log.debug("Logout presented an unparseable access token");
            }
        }
    }

    /** Ends every session for the signed-in user, including the current one. */
    @Transactional
    public int revokeAllSessions(Long userId) {
        int revoked = refreshTokens.revokeAllForUser(userId);
        // The cutoff as well as the rows: "sign me out everywhere" has to mean the access tokens too, or the
        // other devices keep working until their windows lapse.
        users.findById(userId).ifPresent(user -> {
            user.setSessionsValidFrom(OffsetDateTime.now());
            users.save(user);
        });
        audit.record(AppConstant.AUDIT_SESSION_REVOKED, "User", userId, null,
                "revoked " + revoked + " session(s)");
        return revoked;
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> sessions(Long userId) {
        return refreshTokens.liveSessions(userId).stream()
                .map(t -> new SessionResponse(
                        HashIdUtil.encodeId(t.getId()),
                        t.getSessionClass(),
                        t.getUserAgent(),
                        t.getIpAddress(),
                        t.getCreatedAt(),
                        t.getExpiresAt(),
                        false))
                .toList();
    }

    // ── session issue, the one place it happens ───────────────────────────────

    private SessionIssued issue(User user, String sessionClass, String userAgent, String ip,
                                boolean stampLogin) {
        var issued = refreshTokens.issue(user.getId(), sessionClass, userAgent, ip);
        return buildSession(user, sessionClass, issued, stampLogin);
    }

    private SessionIssued buildSession(User user, String sessionClass,
                                       RefreshTokenService.Issued issued, boolean stampLogin) {
        if (stampLogin) {
            boolean firstEver = user.getLastLogin() == null;
            user.setLastLogin(OffsetDateTime.now());
            /*
             * The username window opens on the first sign-in and closes on the second. A username names this
             * person in every created_by, updated_by and audit row; somebody who can rename themselves after
             * acting can make their own trail hard to follow. Before the first sign-in there is no trail yet.
             */
            user.setUsernameChangeable(firstEver);
            users.save(user);
        }

        String accessToken = jwt.generateAccess(user, sessionClass);
        long windowSeconds = jwt.windowMinutes(sessionClass) * 60L;
        boolean mustSetupTotp = requiresTotpEnrolment(user);

        LoginResponse response = new LoginResponse(
                accessToken,
                windowSeconds,
                windowSeconds,
                false,
                user.isMustChangePassword(),
                mustSetupTotp,
                user.isUsernameChangeable(),
                null, null, null,
                me(user));
        return new SessionIssued(response, issued.raw(), sessionClass, issued.maxAgeSeconds());
    }

    // ── identity ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public MeResponse me(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Not authenticated"));
        return TenantContext.runAs(user.getTenantId(), user.getTenantName(), () -> me(user));
    }

    private MeResponse me(User user) {
        UserPrincipal principal = principals.build(user);
        List<VisibleTenant> visible = principal.isUnrestrictedTenants()
                ? List.of()
                : tenants.findAllById(principal.getVisibleTenantIds()).stream()
                        .map(t -> new VisibleTenant(
                                HashIdUtil.encodeId(t.getId()), t.getName(), t.getTenantRef()))
                        .toList();

        return new MeResponse(
                HashIdUtil.encodeId(user.getId()),
                user.getFirstName(),
                user.getLastName(),
                user.fullName(),
                user.getEmail(),
                user.getUsername(),
                user.getPhone(),
                storage.urlFor(user.getAvatarKey()),
                user.getUserTypeCode(),
                user.getUserTypeName(),
                user.getActorClass(),
                user.getUserGroupName(),
                HashIdUtil.encodeId(user.getTenantId()),
                user.getTenantName(),
                HashIdUtil.encodeId(user.getInstitutionId()),
                user.getInstitutionName(),
                principal.isUnrestrictedTenants(),
                visible,
                user.isTotpEnabled(),
                user.getTotpConfirmedAt() != null,
                user.isSmsOtpEnabled(),
                configs.getBoolean(ConfigKey.AUTH_TOTP_REQUIRED),
                user.isMustChangePassword(),
                user.isUsernameChangeable(),
                user.getEmailVerifiedAt() != null,
                user.getPhoneVerifiedAt() != null,
                user.getPasswordExpiresAt(),
                user.getLastLogin(),
                principal.getAuthorities().stream()
                        .map(a -> a.getAuthority())
                        .filter(a -> !a.startsWith("ROLE_"))
                        .sorted()
                        .toList());
    }

    // ── password ──────────────────────────────────────────────────────────────

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = users.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Not authenticated"));

        TenantContext.runAs(user.getTenantId(), user.getTenantName(), () -> {
            if (!passwords.matches(request.currentPassword(), user.getPassword())) {
                // Deliberately not counted as a failed login attempt: this caller is already
                // authenticated, and locking somebody out of an account they are holding for mistyping
                // their old password helps nobody.
                throw new HodiException("That is not your current password", HttpStatus.BAD_REQUEST);
            }
            passwords.applyTo(user, request.newPassword());
            /*
             * Every session ends, access tokens included.
             *
             * A password change is the standard response to believing somebody else has your credentials, and
             * it means nothing if their session survives it. Revoking refresh tokens alone is not enough —
             * that stops renewal, while the access token already in their hands stays valid for the rest of
             * its window. The cutoff is what invalidates those, and it reaches every outstanding token rather
             * than only the one the caller presented.
             *
             * The caller's own session goes too. That is a second of friction for the person who asked,
             * against a foothold that would otherwise last a full idle window.
             */
            user.setSessionsValidFrom(OffsetDateTime.now());
            users.save(user);
            refreshTokens.revokeAllForUser(userId);
            audit.record(AppConstant.AUDIT_PASSWORD_CHANGE, "User", userId, null, "self-service");
            return null;
        });
    }

    @Transactional(readOnly = true)
    public PasswordPolicyResponse passwordPolicy() {
        return new PasswordPolicyResponse(
                configs.getInt(ConfigKey.AUTH_PASSWORD_MIN_LENGTH),
                configs.getBoolean(ConfigKey.AUTH_PASSWORD_REQUIRE_UPPER),
                configs.getBoolean(ConfigKey.AUTH_PASSWORD_REQUIRE_NUMBER),
                configs.getBoolean(ConfigKey.AUTH_PASSWORD_REQUIRE_SYMBOL),
                configs.getInt(ConfigKey.AUTH_PASSWORD_HISTORY_COUNT),
                configs.getInt(ConfigKey.AUTH_PASSWORD_EXPIRY_DAYS));
    }

    /** Claims the one-time username window. Refused once the window has closed. */
    @Transactional
    public MeResponse chooseUsername(Long userId, String username) {
        User user = users.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Not authenticated"));
        if (!user.isUsernameChangeable()) {
            throw new HodiException("Your username can no longer be changed", HttpStatus.CONFLICT);
        }
        String candidate = username.trim();
        if (users.existsByUsernameIgnoreCase(candidate)
                && !candidate.equalsIgnoreCase(user.getUsername())) {
            throw new HodiException("That username is taken", HttpStatus.CONFLICT);
        }
        String previous = user.getUsername();
        user.setUsername(candidate);
        user.setUsernameChangeable(false);
        users.save(user);
        audit.record(AppConstant.ACTION_UPDATE, "User", userId, previous, candidate);
        return me(user);
    }

    // ── guards ────────────────────────────────────────────────────────────────

    /**
     * Everything that stops an otherwise-valid credential from producing a session.
     *
     * <p>Ordered cheapest-first, and each message is deliberately different from "invalid credentials":
     * these are all states the account holder can do something about, and telling somebody their account is
     * locked is not a disclosure — they already proved they know the password.
     */
    private void assertUsable(User user) {
        if (!AppConstant.isLive(user.getStatus()) || !user.isEnabled()) {
            throw new UnauthorizedException("This account is not active");
        }
        if (user.isCurrentlyLocked()) {
            throw new UnauthorizedException(
                    "This account is locked. Try again later or ask an administrator to unlock it.");
        }
        assertOrganisationTradeable(user);
    }

    /**
     * A seller's staff cannot sign in while the organisation is suspended or terminated.
     *
     * <p>Enforced at login rather than by deactivating every staff row, because suspension is reversible and
     * rewriting every user on the way in and out would lose the distinction between "this person was
     * deactivated" and "their employer was". Checked on refresh too, so suspending an organisation ends its
     * live sessions within one window rather than at the end of the day.
     */
    private void assertOrganisationTradeable(User user) {
        if (user.getTenantId() == null) return;
        Tenant tenant = tenants.findById(user.getTenantId()).orElse(null);
        if (tenant == null) {
            log.warn("User {} references tenant {} which does not exist", user.getId(), user.getTenantId());
            throw new UnauthorizedException("This account is not active");
        }
        if (tenant.isSuspended()) {
            throw new UnauthorizedException(
                    "This organisation's account is suspended. Contact Hodi support.");
        }
        if (tenant.isTerminated() || !AppConstant.isLive(tenant.getStatus())) {
            throw new UnauthorizedException("This organisation's account is closed.");
        }
    }

    private boolean requiresSecondFactor(User user) {
        if (!configs.getBoolean(ConfigKey.AUTH_TOTP_ENABLED)) return false;
        // Buyers are deliberately never challenged: nothing behind a buyer session is worth the drop-off,
        // and a house-hunter who cannot receive an SMS should not be locked out of their own enquiries.
        if (user.isBuyerActor()) return false;
        boolean totpReady = user.isTotpEnabled() && user.getTotpConfirmedAt() != null;
        return totpReady || (user.isSmsOtpEnabled() && user.getPhone() != null);
    }

    /** True when policy demands TOTP and this user has not finished enrolling. Staff only. */
    private boolean requiresTotpEnrolment(User user) {
        if (user.isBuyerActor()) return false;
        if (!configs.getBoolean(ConfigKey.AUTH_TOTP_ENABLED)) return false;
        if (!configs.getBoolean(ConfigKey.AUTH_TOTP_REQUIRED)) return false;
        return user.getTotpConfirmedAt() == null;
    }

    /**
     * Which idle window governs this session.
     *
     * <p>Derived from the user, not taken from the request. A requested class is honoured only when it agrees
     * with what the user is: otherwise a buyer could ask for the ADMIN window, or — more usefully to an
     * attacker — a staff member could ask for the BUYER window and turn a 20-minute idle timeout into 24
     * hours.
     */
    private String resolveSessionClass(User user, String requested) {
        String natural = user.isBuyerActor()
                ? AppConstant.SESSION_CLASS_BUYER
                : AppConstant.SESSION_CLASS_ADMIN;
        if (requested != null && !requested.equalsIgnoreCase(natural)) {
            log.debug("Ignoring requested session class {} for a {} user", requested,
                    user.getActorClass());
        }
        return natural;
    }

    private void registerFailure(User user) {
        int max = Math.max(1, configs.getInt(ConfigKey.AUTH_LOGIN_MAX_ATTEMPTS));
        int lockMinutes = Math.max(1, configs.getInt(ConfigKey.AUTH_LOGIN_LOCKOUT_MINUTES));
        int attempts = (user.getFailedAttempts() == null ? 0 : user.getFailedAttempts()) + 1;
        user.setFailedAttempts(attempts);
        if (attempts >= max) {
            user.setLocked(true);
            user.setLockedUntil(OffsetDateTime.now().plusMinutes(lockMinutes));
            log.warn("Account {} locked after {} failed attempts", user.getId(), attempts);
        }
        users.save(user);
    }

    private void clearFailures(User user) {
        user.setFailedAttempts(0);
        // Clearing the lock as well as the counter: a successful password check means whoever is here knows
        // the credential, and an expired lock that is never cleared would count the next single failure as
        // the max-th one.
        user.setLocked(false);
        user.setLockedUntil(null);
    }
}
