package com.hodi.security.principal;

import com.hodi.common.AppConstant;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.users.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The authenticated caller. Authorities are the bare action codes ({@code USERS_CREATE}) plus a
 * {@code ROLE_}-prefixed user type, so both {@code hasAuthority('USERS_CREATE')} and
 * {@code hasRole('SUPER_ADMIN')} work.
 *
 * <p><strong>Two rows, not one.</strong> The credential comes from {@code users} and everything
 * authorisation depends on — actor class, user type, group, organisation — from the caller's
 * <em>active profile</em>. The same person signed in on their buyer profile and on their seller-owner
 * profile is two different principals with the same {@link #userId}, which is the point of FR073.
 *
 * <p>Carries the resolved <strong>visible-tenant set</strong> as well as the permission set, because both
 * are needed on every request and both are expensive to work out. For a lender's staff the set is the
 * sellers their institution is partnered with — several organisations, none of them their own — which is why
 * this is a set rather than the single tenant id axis carries.
 */
@Getter
public class UserPrincipal implements UserDetails {

    private final Long userId;

    /**
     * The active profile. Named in the access token, verified against the user on every request, and the
     * row every field below it was resolved from.
     */
    private final Long profileId;

    private final String username;
    private final String password;
    private final String email;
    private final String fullName;
    private final String userTypeCode;
    private final String actorClass;

    /** The seller organisation this user belongs to. Null for platform staff, lender staff and buyers. */
    private final Long tenantId;
    private final String tenantName;

    /** The lending institution this user belongs to. Null for everyone but lender staff. */
    private final Long institutionId;
    private final String institutionName;

    /** True for platform staff only: no organisation filter applies to their reads. */
    private final boolean unrestrictedTenants;

    /**
     * The organisations whose rows this caller may read. Empty when {@link #unrestrictedTenants} is true
     * (callers must check that first — see {@code TenantScope}), and legitimately empty for a lender with no
     * approved partnership and for every buyer.
     */
    private final List<Long> visibleTenantIds;

    private final boolean accountLocked;
    private final boolean accountEnabled;
    private final boolean mustChangePassword;

    /**
     * When this credential stops being usable, carried so {@code PasswordChangeRequiredFilter} can enforce
     * it. {@code PasswordService} stamps the column from the configured maximum age; without something
     * reading it, expiry would be configured, recorded and never applied.
     */
    private final OffsetDateTime passwordExpiresAt;

    /** Whether the account has cleared whatever verification the configuration demands of it. */
    private final boolean verified;

    /** Access tokens issued before this instant are refused. Null means no cutoff. */
    private final OffsetDateTime sessionsValidFrom;

    private final Collection<GrantedAuthority> authorities;

    /** Whether Compliance has cleared this profile — the KYC gate reads it (plan §3.3). */
    private final boolean kycCleared;

    private UserPrincipal(User user, UserProfile profile, Set<String> permissions,
                          List<Long> visibleTenantIds, boolean unrestrictedTenants, boolean verified) {
        this.userId = user.getId();
        this.profileId = profile.getId();
        this.username = user.getUsername();
        this.password = user.getPassword();
        this.email = user.getEmail();
        this.fullName = user.fullName();
        this.userTypeCode = profile.getUserTypeCode();
        this.actorClass = profile.getProfileType();
        this.tenantId = profile.getTenantId();
        this.tenantName = profile.getTenantName();
        this.institutionId = profile.getInstitutionId();
        this.institutionName = profile.getInstitutionName();
        this.kycCleared = profile.isKycCleared();
        this.unrestrictedTenants = unrestrictedTenants;
        this.visibleTenantIds = visibleTenantIds == null ? List.of() : List.copyOf(visibleTenantIds);
        this.accountLocked = user.isCurrentlyLocked();
        // isLive, not status == 1: every update in this codebase stamps STATUS_EDITED as a
        // changed-since-activation marker, so testing for ACTIVE alone would mean editing somebody's phone
        // number locked them out.
        // The profile's own status counts too: somebody removed from an organisation has a live credential
        // and a dead profile, and a session on that profile must stop working.
        this.accountEnabled = user.isEnabled() && AppConstant.isLive(user.getStatus())
                && AppConstant.isLive(profile.getStatus());
        this.mustChangePassword = user.isMustChangePassword();
        this.passwordExpiresAt = user.getPasswordExpiresAt();
        this.verified = verified;
        this.sessionsValidFrom = user.getSessionsValidFrom();

        Collection<GrantedAuthority> list = new ArrayList<>();
        permissions.forEach(code -> list.add(new SimpleGrantedAuthority(code)));
        if (profile.getUserTypeCode() != null) {
            list.add(new SimpleGrantedAuthority("ROLE_" + profile.getUserTypeCode()));
        }
        this.authorities = List.copyOf(list);
    }

    /**
     * True when a token was minted before this account's session cutoff.
     *
     * <p>A straight millisecond comparison, which is only possible because the token carries its own
     * millisecond issue time ({@code JwtService.CLAIM_ISSUED_MS}). Getting here took two wrong turns worth
     * recording, because both are the obvious thing to write:
     *
     * <ul>
     *   <li>Comparing the second-granular {@code iat} with {@code isBefore} left a one-second window: a
     *       token issued at 10:00:05.1 and revoked at 10:00:05.9 has {@code iat == 10:00:05}, which is not
     *       before a cutoff truncated to 10:00:05, so the revoked token kept working.
     *   <li>Tightening that to {@code !isAfter} closed the window and broke the opposite case: the fresh
     *       login somebody performs immediately after revoking their sessions also lands in that second, and
     *       its brand-new token was refused too.
     * </ul>
     *
     * <p>Both failures are the same root cause — one second of ambiguity that no comparison operator can
     * resolve — so the fix was to remove the ambiguity rather than to choose which side to be wrong on.
     */
    public boolean wasIssuedBeforeCutoff(java.time.Instant issuedAt) {
        if (sessionsValidFrom == null) return false;
        // No issue time at all: cannot prove the token predates the cutoff, and cannot prove it does not.
        // Refused, because the cutoff exists precisely because somebody wanted outstanding tokens gone.
        if (issuedAt == null) return true;
        return issuedAt.isBefore(sessionsValidFrom.toInstant());
    }

    /** True when the password has an expiry and it has passed. No expiry set means it does not expire. */
    public boolean isPasswordExpired() {
        return passwordExpiresAt != null && passwordExpiresAt.isBefore(OffsetDateTime.now());
    }

    public boolean isBuyer() {
        return AppConstant.ACTOR_BUYER.equals(actorClass);
    }

    public boolean isPlatformStaff() {
        return AppConstant.ACTOR_PLATFORM.equals(actorClass);
    }

    /** A valuer on the panel. Sees the jobs assigned to them and nothing else — plan §3.5. */
    public boolean isValuer() {
        return AppConstant.ACTOR_VALUER.equals(actorClass);
    }

    public boolean isSellerStaff() {
        return AppConstant.ACTOR_SELLER.equals(actorClass);
    }

    public boolean isLenderStaff() {
        return AppConstant.ACTOR_LENDER.equals(actorClass);
    }

    /** The session class whose idle window governs this user (plan section 5). */
    public String sessionClass() {
        return isBuyer() ? AppConstant.SESSION_CLASS_BUYER : AppConstant.SESSION_CLASS_ADMIN;
    }

    public static UserPrincipal of(User user, UserProfile profile, Set<String> permissions,
                                   List<Long> visibleTenantIds, boolean unrestrictedTenants,
                                   boolean verified) {
        return new UserPrincipal(user, profile, permissions, visibleTenantIds, unrestrictedTenants,
                verified);
    }

    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return authorities; }
    @Override public String getPassword() { return password; }
    @Override public String getUsername() { return username; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return !accountLocked; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return accountEnabled; }
}
