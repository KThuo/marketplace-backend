package com.hodi.security.principal;

import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.partnerships.PartnershipRepository;
import com.hodi.modules.users.User;
import com.hodi.security.EffectivePermissionResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Builds a {@link UserPrincipal} from a user row: resolves the effective permission set, the set of
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
 *   <li><strong>Lender staff</strong> — the sellers their institution has an <strong>active</strong>
 *       partnership with, read fresh here on every principal build. A revoked partnership therefore stops
 *       granting access on the caller's next request rather than when their token happens to expire, which
 *       matters because revoking a partnership is exactly the moment somebody wants access gone.
 *   <li><strong>Buyers</strong> — empty. A buyer never reads rows by organisation; their own rows are found
 *       by filtering on their own user id (see {@link AuthContext#requireUserId()}).
 * </ul>
 *
 * <p>An institution with no approved partnerships yields an empty set, and that is correct rather than
 * broken: {@code TenantScope} turns an empty set into a predicate matching nothing, and
 * {@code TenantScope.isStranded()} lets the UI explain why. The failure mode being avoided is the opposite
 * one — an empty set read as "no restriction", which would hand a brand-new lender the whole platform.
 */
@Component
@RequiredArgsConstructor
public class PrincipalFactory {

    private final EffectivePermissionResolver permissions;
    private final PartnershipRepository partnerships;
    private final ConfigurationService configs;

    public UserPrincipal build(User user) {
        boolean unrestricted = user.isPlatformActor();
        List<Long> visible = resolveVisibleTenants(user, unrestricted);
        return UserPrincipal.of(user, permissions.resolve(user), visible, unrestricted, isVerified(user));
    }

    private List<Long> resolveVisibleTenants(User user, boolean unrestricted) {
        if (unrestricted) return List.of();
        if (user.getTenantId() != null) return List.of(user.getTenantId());
        if (user.getInstitutionId() != null) {
            return partnerships.findActiveTenantIdsForInstitution(user.getInstitutionId());
        }
        // Buyers, and any staff row not yet attached to an organisation.
        return List.of();
    }

    /**
     * Whether the account has cleared whatever verification the configuration demands.
     *
     * <p>Only buyers are ever unverified: staff accounts are created by somebody who already had to
     * authenticate, so there is nothing for a code sent to their own address to prove. The two requirements
     * are read live rather than baked into the row, so turning phone verification on tomorrow applies to
     * everybody who has not done it — which is the point of it being configuration.
     */
    private boolean isVerified(User user) {
        if (!user.isBuyerActor()) return true;
        if (configs.getBoolean(ConfigKey.BUYER_EMAIL_VERIFICATION_REQUIRED)
                && user.getEmailVerifiedAt() == null) {
            return false;
        }
        return !configs.getBoolean(ConfigKey.BUYER_PHONE_VERIFICATION_REQUIRED)
                || user.getPhoneVerifiedAt() != null;
    }
}
