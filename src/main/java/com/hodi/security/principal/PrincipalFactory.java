package com.hodi.security.principal;

import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.common.exception.UnauthorizedException;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.users.User;
import com.hodi.security.EffectivePermissionResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds a {@link UserPrincipal} from a person and one of their profiles: resolves the effective permission set, the set of
 * organisations whose rows the user may read, and whether the account has cleared verification.
 *
 * <h2>Resolving the visible-tenant set</h2>
 *
 * <p>This is where the difference between Hodi's access model and axis's actually lands. Four populations,
 * four answers, and the interesting one is the lender:
 *
 * <ul>
 *   <li><strong>Platform staff</strong> — unrestricted. No filter is applied to their reads at all.
 *   <li><strong>Seller staff</strong> — exactly their own organisation. Not "their own plus anything they
 *       were granted": there is no mechanism to widen it, which is the property that makes seller isolation
 *       true by construction.
 *   <li><strong>The bank's staff</strong> — unrestricted, because they are platform staff. They used to be
 *       scoped to the sellers their institution had an active partnership with; there is one bank and it
 *       runs the platform, so there is nobody for it to partner with.
 *   <li><strong>Buyers</strong> — empty. A buyer never reads rows by organisation; their own rows are found
 *       by filtering on their own user id (see {@link AuthContext#requireUserId()}).
 * </ul>
 *
 * <p>A profile that resolves to no organisation at all yields an empty set, and that is correct rather than
 * broken: {@code TenantScope} turns an empty set into a predicate matching nothing. The failure mode being
 * avoided is the opposite one — an empty set read as "no restriction", which would hand the whole platform
 * to whoever fell through.
 */
@Component
@RequiredArgsConstructor
public class PrincipalFactory {

    private final EffectivePermissionResolver permissions;
    private final UserProfileRepository profiles;
    private final ConfigurationService configs;

    public UserPrincipal build(User user, UserProfile profile) {
        boolean unrestricted = profile.isPlatformActor();
        List<Long> visible = resolveVisibleTenants(profile, unrestricted);
        return UserPrincipal.of(user, profile, permissions.resolve(profile), visible, unrestricted,
                isVerified(user, profile));
    }

    /**
     * The same, on the profile a fresh login lands on.
     *
     * <p>A person with no live profile cannot be a principal at all: there is no actor class to resolve, no
     * user type to match against a module, and no organisation. That is a broken account rather than an
     * unauthorised one, but it has to fail here — building a principal with the fields left null would give
     * somebody the "both organisation columns are empty" shape, which is what a platform administrator looks
     * like.
     */
    public UserPrincipal buildDefault(User user) {
        return build(user, requireDefaultProfile(user.getId()));
    }

    public UserProfile requireDefaultProfile(Long userId) {
        return profiles.findDefaultForUser(userId)
                .or(() -> profiles.findLiveForUser(userId).stream().findFirst())
                .orElseThrow(() -> new UnauthorizedException("This account has no active profile"));
    }

    private List<Long> resolveVisibleTenants(UserProfile profile, boolean unrestricted) {
        if (unrestricted) return List.of();
        if (profile.getTenantId() != null) return List.of(profile.getTenantId());
        /*
         * No institution branch any more, and its absence is the safe direction.
         *
         * It used to return the sellers an institution had an active partnership with. The bank's staff are
         * platform actors now, so they take the `unrestricted` return above and never reach here; a profile
         * that still carries an institution id and is NOT a platform actor is a leftover, and it falls
         * through to the empty set — seeing nothing — rather than to a set somebody has to justify.
         */
        // Buyers, and any staff profile not attached to an organisation.
        return List.of();
    }

    /**
     * Whether the account has cleared whatever verification the configuration demands.
     *
     * <p>Only buyers are ever unverified: staff accounts are created by somebody who already had to
     * authenticate, so there is nothing for a code sent to their own address to prove. The two requirements
     * are read live rather than baked into the row, so turning phone verification on tomorrow applies to
     * everybody who has not done it — which is the point of it being configuration.
     *
     * <p>"Only buyers" is about the <em>person</em>, not the profile. Somebody who also holds a staff profile
     * was provisioned by an administrator who was already authenticated, and their address has been vouched
     * for by whoever created the account — so their buyer profile is verified too. Reading it per profile
     * instead produced a trap: a staff member who added a buyer profile switched onto it and found every
     * request refused pending a verification code that had never been sent, because staff accounts do not go
     * through the buyer verification flow at all.
     */
    private boolean isVerified(User user, UserProfile profile) {
        if (!profile.isBuyerActor()) return true;
        boolean staffElsewhere = profiles.findLiveForUser(user.getId()).stream()
                .anyMatch(other -> !other.isBuyerActor());
        if (staffElsewhere) return true;
        if (configs.getBoolean(ConfigKey.BUYER_EMAIL_VERIFICATION_REQUIRED)
                && user.getEmailVerifiedAt() == null) {
            return false;
        }
        return !configs.getBoolean(ConfigKey.BUYER_PHONE_VERIFICATION_REQUIRED)
                || user.getPhoneVerifiedAt() != null;
    }
}
