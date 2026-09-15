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
import com.hodi.modules.auth.dto.AuthDtos.ProfileSummary;
import com.hodi.modules.auth.dto.AuthDtos.SessionResponse;
import com.hodi.modules.auth.dto.AuthDtos.VerifyOtpRequest;
import com.hodi.modules.auth.dto.AuthDtos.VisibleTenant;
import com.hodi.modules.buyers.BuyerRegistrationService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.profiles.UserProfileService;
import com.hodi.modules.usergroups.UserGroup;
import com.hodi.modules.usergroups.UserGroupRepository;
import com.hodi.modules.usertypes.UserType;
import com.hodi.modules.usertypes.UserTypeRepository;
import com.hodi.modules.tenants.Tenant;
import com.hodi.modules.tenants.TenantRepository;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.jwt.JwtService;
import com.hodi.security.jwt.TokenBlacklistService;
import com.hodi.security.password.PasswordService;
import com.hodi.security.principal.AuthContext;
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
    private final UserProfileRepository profiles;
    private final UserProfileService userProfiles;
    private final UserTypeRepository userTypes;
    private final UserGroupRepository userGroups;
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
            // The identifier, not the password. A trail of attempted addresses is how a credential-stuffing
            // run is spotted; a trail of attempted passwords is a password list.
            audit.recordAuth(AppConstant.AUDIT_LOGIN_FAILED, null, identifier, null,
                    AppConstant.OUTCOME_UNAUTHORIZED, "no account for that identifier", ip, userAgent);
            throw new UnauthorizedException("Invalid credentials");
        }

        UserProfile landing = landingProfile(user, request.sessionClass());

        /*
         * Policy is read under the user's own organisation, not the request's absent one.
         *
         * Password rules, lockout thresholds and session windows all resolve through ConfigurationService,
         * which layers a tenant override over the global value. At this point in the request nothing is bound
         * yet — TenantBindingFilter needs a principal, and there isn't one — so without this, every seller's
         * staff would authenticate under global policy and any override would apply from their second request
         * onwards but not their first.
         */
        return TenantContext.runAs(landing.getTenantId(), landing.getTenantName(),
                () -> authenticate(user, landing, request, userAgent, ip));
    }

    /**
     * Which profile a sign-in lands on.
     *
     * <p>The requested session class picks it when it can: somebody who is both a buyer and a seller's owner
     * signing in on the marketplace means the buyer, and on the workspace means the seller. That is a
     * <em>selection among profiles they already hold</em>, never a widening — a buyer asking for ADMIN gets
     * their buyer profile, because nothing else matches and the default is theirs too.
     */
    private UserProfile landingProfile(User user, String requestedSessionClass) {
        List<UserProfile> live = profiles.findLiveForUser(user.getId()).stream()
                .filter(p -> AppConstant.isLive(p.getStatus()))
                .toList();
        if (live.isEmpty()) {
            throw new UnauthorizedException("This account has no active profile");
        }
        if (requestedSessionClass != null) {
            for (UserProfile candidate : live) {
                if (candidate.sessionClass().equalsIgnoreCase(requestedSessionClass)) return candidate;
            }
        }
        return live.stream().filter(UserProfile::isDefaultProfile).findFirst().orElse(live.get(0));
    }

    private SessionIssued authenticate(User user, UserProfile profile, LoginRequest request,
                                       String userAgent, String ip) {
        assertUsable(user, profile);

        if (!passwords.matches(request.password(), user.getPassword())) {
            registerFailure(user);
            audit.recordAuth(AppConstant.AUDIT_LOGIN_FAILED, user.getId(), user.getUsername(),
                    profile.getId(), AppConstant.OUTCOME_UNAUTHORIZED, "wrong password", ip, userAgent);
            throw new UnauthorizedException("Invalid credentials");
        }

        clearFailures(user);
        users.save(user);

        String sessionClass = profile.sessionClass();

        // A second factor means the password alone must not produce a session. Return a challenge and stop;
        // nothing is issued until the code is verified.
        if (requiresSecondFactor(user, profile)) {
            var challenge = otpChallenges.issueLogin(user);
            audit.recordAuth(AppConstant.AUDIT_LOGIN, user.getId(), user.getUsername(),
                    profile.getId(), AppConstant.OUTCOME_SUCCESS,
                    "password accepted — second factor challenged", ip, userAgent);
            return SessionIssued.challengeOnly(LoginResponse.challenge(
                    jwt.windowMinutes(sessionClass) * 60L,
                    challenge.token(), challenge.channel(), challenge.sentToMasked()));
        }
        audit.recordAuth(AppConstant.AUDIT_LOGIN, user.getId(), user.getUsername(), profile.getId(),
                AppConstant.OUTCOME_SUCCESS, "signed in", ip, userAgent);
        return issue(user, profile, sessionClass, userAgent, ip, true);
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

        UserProfile landing = landingProfile(user, null);
        return TenantContext.runAs(landing.getTenantId(), landing.getTenantName(), () -> {
            otpChallenges.consume(request.otpToken(), request.code(),
                    OtpChallengeService.PURPOSE_LOGIN, user);
            assertUsable(user, landing);
            audit.recordAuth(AppConstant.AUDIT_LOGIN, user.getId(), user.getUsername(),
                    landing.getId(), AppConstant.OUTCOME_SUCCESS, "second factor accepted", ip,
                    userAgent);
            return issue(user, landing, landing.sessionClass(), userAgent, ip, true);
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

        // The profile the session was issued on, not the default — a rotation that quietly moved somebody
        // back to their default profile would change what their session can see once per idle window.
        UserProfile profile = rotation.profileId() == null
                ? principals.requireDefaultProfile(user.getId())
                : profiles.findByIdAndUserId(rotation.profileId(), user.getId())
                        .orElseThrow(() -> new UnauthorizedException("Session expired"));

        return TenantContext.runAs(profile.getTenantId(), profile.getTenantName(), () -> {
            assertUsable(user, profile);
            // The replacement token is already issued and persisted by rotate(); reuse it rather than
            // issuing a second one, or every refresh would leave an orphaned live session behind.
            return buildSession(user, profile, rotation.sessionClass(), rotation.replacement(), false);
        });
    }

    @Transactional
    public void logout(String accessToken, String refreshToken, String ip, String userAgent) {
        AuthContext.current().ifPresent(actor -> audit.recordAuth(AppConstant.AUDIT_LOGOUT,
                actor.getUserId(), actor.getUsername(), actor.getProfileId(),
                AppConstant.OUTCOME_SUCCESS, "signed out", ip, userAgent));
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

    private SessionIssued issue(User user, UserProfile profile, String sessionClass, String userAgent,
                                String ip, boolean stampLogin) {
        var issued = refreshTokens.issue(user.getId(), profile.getId(), sessionClass, userAgent, ip);
        return buildSession(user, profile, sessionClass, issued, stampLogin);
    }

    private SessionIssued buildSession(User user, UserProfile profile, String sessionClass,
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

        String accessToken = jwt.generateAccess(user, profile, sessionClass);
        long windowSeconds = jwt.windowMinutes(sessionClass) * 60L;
        boolean mustSetupTotp = requiresTotpEnrolment(user, profile);

        LoginResponse response = new LoginResponse(
                accessToken,
                windowSeconds,
                windowSeconds,
                false,
                user.isMustChangePassword(),
                mustSetupTotp,
                user.isUsernameChangeable(),
                null, null, null,
                me(user, profile));
        return new SessionIssued(response, issued.raw(), sessionClass, issued.maxAgeSeconds());
    }

    // ── identity ──────────────────────────────────────────────────────────────

    /**
     * The caller as they are right now, on the profile their session is on.
     *
     * <p>The profile comes from the security context rather than from the default, because "who am I" has to
     * agree with what the rest of the request will be authorised as. Falling back to the default here would
     * make {@code /me} describe a different actor than the one the next call is made by.
     */
    @Transactional(readOnly = true)
    public MeResponse me(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Not authenticated"));
        UserProfile profile = AuthContext.current()
                .map(UserPrincipal::getProfileId)
                .flatMap(id -> profiles.findByIdAndUserId(id, userId))
                .orElseGet(() -> principals.requireDefaultProfile(userId));
        return TenantContext.runAs(profile.getTenantId(), profile.getTenantName(),
                () -> me(user, profile));
    }

    /**
     * Moves the session onto another of the caller's profiles.
     *
     * <p>A new token pair, not a mutation: nothing about the old session's rows is rewritten — it is revoked
     * and a fresh session is issued on the chosen profile. That is what makes the profile claim safe to trust after
     * verification — there is no path that widens a session in place, so a buyer token cannot become a seller
     * token by any route other than proving you hold the seller profile.
     *
     * <p>The old session is revoked as it goes. Leaving it live would mean one sign-in accumulating a session
     * per profile, each with its own idle window, which is not what anybody means by switching.
     */
    @Transactional
    public SessionIssued switchProfile(Long userId, String profileHashId, String currentRefreshToken,
                                       String currentAccessToken, String userAgent, String ip) {
        User user = users.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Not authenticated"));
        Long profileId = HashIdUtil.decodeId(profileHashId, user.getUsername());
        UserProfile profile = profileId == null
                ? null
                : profiles.findByIdAndUserId(profileId, userId).orElse(null);
        if (profile == null || !AppConstant.isLive(profile.getStatus())) {
            // Not-found rather than forbidden, and the same answer for "not yours" as for "not a profile":
            // the id space is shared, and confirming that a profile exists tells a caller about somebody else.
            throw new HodiException("That profile is not available.", HttpStatus.NOT_FOUND);
        }

        return TenantContext.runAs(profile.getTenantId(), profile.getTenantName(), () -> {
            assertUsable(user, profile);
            if (currentRefreshToken != null && !currentRefreshToken.isBlank()) {
                refreshTokens.revoke(currentRefreshToken);
            }
            /*
             * The access token goes too.
             *
             * Revoking the refresh row alone would leave the previous profile's access token usable for the
             * rest of its window — no escalation, since the caller held that profile anyway, but two live
             * sessions from one switch is not what "switch" means, and a client that kept the old token would
             * keep acting as the old actor without anything being wrong.
             */
            if (currentAccessToken != null && !currentAccessToken.isBlank()) {
                try {
                    blacklist.blacklist(currentAccessToken, jwt.remainingTtlMs(currentAccessToken));
                } catch (RuntimeException e) {
                    log.debug("Switch presented an unparseable access token — nothing to blacklist");
                }
            }
            audit.recordAuth(AppConstant.AUDIT_PROFILE_SWITCH, userId, user.getUsername(),
                    profile.getId(), AppConstant.OUTCOME_SUCCESS,
                    "switched to " + profile.getProfileType() + " / " + profile.organisationLabel(),
                    ip, userAgent);
            return issue(user, profile, profile.sessionClass(), userAgent, ip, false);
        });
    }

    /**
     * Adds a buyer profile to somebody who does not have one (BRD FR073).
     *
     * <p>The seller's owner who also wants to browse listings, and the buyer who has since joined a seller —
     * both end up here. Idempotent: asking twice returns the same identity rather than failing, because the
     * caller's intent ("I want to be able to browse") is already satisfied.
     *
     * <p>Never becomes the default. Where a session lands is the holder's own choice, and quietly moving
     * somebody's landing profile because they added a second one would change what they see at sign-in.
     */
    @Transactional
    public MeResponse addBuyerProfile(Long userId) {
        User user = users.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Not authenticated"));
        UserProfile active = AuthContext.current()
                .map(UserPrincipal::getProfileId)
                .flatMap(id -> profiles.findByIdAndUserId(id, userId))
                .orElseGet(() -> principals.requireDefaultProfile(userId));

        boolean already = profiles.findLiveForUser(userId).stream().anyMatch(UserProfile::isBuyerActor);
        if (already) {
            return TenantContext.runAs(active.getTenantId(), active.getTenantName(),
                    () -> me(user, active));
        }

        UserType buyerType = userTypes.findByCode("BUYER")
                .orElseThrow(() -> new HodiException(
                        "The BUYER user type is missing — the seeder has not run.",
                        HttpStatus.INTERNAL_SERVER_ERROR));
        UserGroup buyerGroup = userGroups.findGlobalByName(BuyerRegistrationService.BUYER_GROUP_NAME)
                .orElseThrow(() -> new HodiException(
                        "The Buyer group is missing — the seeder has not run.",
                        HttpStatus.INTERNAL_SERVER_ERROR));

        userProfiles.addProfile(userId, buyerType, buyerGroup, null, null, null, null);
        audit.record(AppConstant.ACTION_CREATE, "UserProfile", userId, null,
                "added a BUYER profile to " + user.getUsername());
        return TenantContext.runAs(active.getTenantId(), active.getTenantName(),
                () -> me(user, active));
    }

    private MeResponse me(User user, UserProfile profile) {
        UserPrincipal principal = principals.build(user, profile);
        List<VisibleTenant> visible = principal.isUnrestrictedTenants()
                ? List.of()
                : tenants.findAllById(principal.getVisibleTenantIds()).stream()
                        .map(t -> new VisibleTenant(
                                HashIdUtil.encodeId(t.getId(), user.getUsername()), t.getName(),
                                t.getTenantRef()))
                        .toList();

        /*
         * Encoded against this user's own salt, explicitly.
         *
         * HashIds are salted per user from the security context, and on the login path there is no security
         * context yet — so the ids in a login response were being encoded as "system" while the very next
         * authenticated request decoded them as this user. Anything the client reads and sends back (the
         * profile it wants to switch to, above all) therefore failed to decode. Passing the username makes
         * the two ends agree on every path, authenticated or not.
         */
        String salt = user.getUsername();

        return new MeResponse(
                HashIdUtil.encodeId(user.getId(), salt),
                HashIdUtil.encodeId(profile.getId(), salt),
                user.getFirstName(),
                user.getLastName(),
                user.fullName(),
                user.getEmail(),
                user.getUsername(),
                user.getPhone(),
                storage.urlFor(user.getAvatarKey()),
                profile.getUserTypeCode(),
                profile.getUserTypeName(),
                profile.getProfileType(),
                profile.getUserGroupName(),
                HashIdUtil.encodeId(profile.getTenantId(), salt),
                profile.getTenantName(),
                HashIdUtil.encodeId(profile.getInstitutionId(), salt),
                profile.getInstitutionName(),
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
                // The server's own answer, from the principal it just built — see the DTO comment.
                !principal.isVerified(),
                user.getPasswordExpiresAt(),
                user.getLastLogin(),
                principal.getAuthorities().stream()
                        .map(a -> a.getAuthority())
                        .filter(a -> !a.startsWith("ROLE_"))
                        .sorted()
                        .toList(),
                profileSummaries(user, profile.getId()));
    }

    private List<ProfileSummary> profileSummaries(User user, Long activeProfileId) {
        return profiles.findLiveForUser(user.getId()).stream()
                .filter(p -> AppConstant.isLive(p.getStatus()))
                .map(p -> new ProfileSummary(
                        HashIdUtil.encodeId(p.getId(), user.getUsername()),
                        p.getProfileType(),
                        p.getUserTypeCode(),
                        p.getUserTypeName(),
                        p.getUserGroupName(),
                        p.organisationLabel(),
                        p.getKycStatus(),
                        p.getId().equals(activeProfileId),
                        p.isDefaultProfile()))
                .toList();
    }

    // ── password ──────────────────────────────────────────────────────────────

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = users.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Not authenticated"));

        UserProfile profile = principals.requireDefaultProfile(userId);
        TenantContext.runAs(profile.getTenantId(), profile.getTenantName(), () -> {
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
        return me(user, principals.requireDefaultProfile(userId));
    }

    // ── guards ────────────────────────────────────────────────────────────────

    /**
     * Everything that stops an otherwise-valid credential from producing a session.
     *
     * <p>Ordered cheapest-first, and each message is deliberately different from "invalid credentials":
     * these are all states the account holder can do something about, and telling somebody their account is
     * locked is not a disclosure — they already proved they know the password.
     */
    private void assertUsable(User user, UserProfile profile) {
        /*
         * Its own message, and before the generic one.
         *
         * Somebody holding a temporary password their administrator has just handed them has a right to the
         * truth: the account exists, the password is right, and the bank has not approved it yet. "This
         * account is not active" would send them back to the administrator, who would reissue a password
         * that works no better.
         */
        if (!user.isEnabled() && user.getStatus() != null
                && user.getStatus() == AppConstant.STATUS_NEW) {
            throw new UnauthorizedException(
                    "This account is waiting for the bank to approve it. You will be able to sign in "
                            + "once they have.");
        }
        if (!AppConstant.isLive(user.getStatus()) || !user.isEnabled()) {
            throw new UnauthorizedException("This account is not active");
        }
        if (!AppConstant.isLive(profile.getStatus())) {
            // Distinct from the above: the credential is fine, this particular role in this particular
            // organisation is not. Somebody who also holds another profile can still sign in on that one.
            throw new UnauthorizedException("That profile is no longer active");
        }
        if (user.isCurrentlyLocked()) {
            throw new UnauthorizedException(
                    "This account is locked. Try again later or ask an administrator to unlock it.");
        }
        assertOrganisationTradeable(profile);
    }

    /**
     * A seller's staff cannot sign in while the organisation is suspended or terminated.
     *
     * <p>Enforced at login rather than by deactivating every staff row, because suspension is reversible and
     * rewriting every user on the way in and out would lose the distinction between "this person was
     * deactivated" and "their employer was". Checked on refresh too, so suspending an organisation ends its
     * live sessions within one window rather than at the end of the day.
     */
    private void assertOrganisationTradeable(UserProfile profile) {
        if (profile.getTenantId() == null) return;
        Tenant tenant = tenants.findById(profile.getTenantId()).orElse(null);
        if (tenant == null) {
            log.warn("Profile {} references tenant {} which does not exist", profile.getId(),
                    profile.getTenantId());
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

    private boolean requiresSecondFactor(User user, UserProfile profile) {
        if (!configs.getBoolean(ConfigKey.AUTH_TOTP_ENABLED)) return false;
        // Buyers are deliberately never challenged: nothing behind a buyer session is worth the drop-off,
        // and a house-hunter who cannot receive an SMS should not be locked out of their own enquiries.
        // Per profile, not per person: somebody who is also a seller's owner is challenged on that profile
        // and not on their buyer one, which is the right answer for both.
        if (profile.isBuyerActor()) return false;
        boolean totpReady = user.isTotpEnabled() && user.getTotpConfirmedAt() != null;
        return totpReady || (user.isSmsOtpEnabled() && user.getPhone() != null);
    }

    /** True when policy demands TOTP and this user has not finished enrolling. Staff only. */
    private boolean requiresTotpEnrolment(User user, UserProfile profile) {
        if (profile.isBuyerActor()) return false;
        if (!configs.getBoolean(ConfigKey.AUTH_TOTP_ENABLED)) return false;
        if (!configs.getBoolean(ConfigKey.AUTH_TOTP_REQUIRED)) return false;
        return user.getTotpConfirmedAt() == null;
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
