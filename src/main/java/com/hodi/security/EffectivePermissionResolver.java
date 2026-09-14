package com.hodi.security;

import com.hodi.common.AppConstant;
import com.hodi.modules.appmodules.AppModule;
import com.hodi.modules.appmodules.AppModuleRepository;
import com.hodi.modules.permissions.Permission;
import com.hodi.modules.tenantmodules.TenantModuleRepository;
import com.hodi.modules.usergroups.UserGroup;
import com.hodi.modules.usergroups.UserGroupRepository;
import com.hodi.modules.profiles.UserProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves what a user may actually do — the intersection of the first two axes in plan section 4.
 *
 * <pre>
 *   group permissions                what the organisation granted this role
 *     ∩ module enabled for tenant        tenant_modules
 *     ∩ module admits the user type      app_modules.allowed_user_types
 * </pre>
 *
 * <p>All of them must pass. The two module axes are what stop a permission from being effective merely
 * because it sits in a role: a seller owner can grant {@code PARTNERSHIPS_APPROVE} to a group, but if the
 * partnerships module is switched off for their organisation, or does not admit that user type at all, the
 * authority is not issued.
 *
 * <p>The third axis — which <em>rows</em> — is not here. That is {@code TenantScope}, and it is deliberately
 * a separate mechanism: permissions govern actions and scope governs data, and collapsing the two is how a
 * bank's officer ends up either unable to work at all or able to read every seller on the platform.
 *
 * <h2>Two populations skip the tenant-module axis, for different reasons</h2>
 *
 * <p><strong>Platform staff</strong> have no organisation, so there is no {@code tenant_modules} row to
 * consult — but they remain subject to {@code allowed_user_types}. That is how a Support Admin is kept out of
 * modules only a Super Admin should reach.
 *
 * <p><strong>The bank's staff</strong> belong to an institution, not a tenant, and institutions have no
 * per-organisation module gating in this phase (plan section 12, question 2). They are gated by user type
 * alone. When institutions gain packages, an {@code institution_modules} table mirrors the tenant one and
 * this method grows one symmetrical branch.
 *
 * <p>Not cached. The resolved set is stamped onto the principal, which is rebuilt on every request from the
 * database — so revoking a role takes effect on the next call. Caching an authority set under a key that
 * outlives the revocation it was supposed to enact is how stale authority survives; the query is cheap and
 * the alternative is not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EffectivePermissionResolver {

    /**
     * Permissions a profile cannot hold until Compliance has cleared it (BRD FR075, plan §3.3).
     *
     * <p>The gate belongs here rather than in a controller for the same reason the module matrix does: this
     * is the one place every permission passes through, so a screen that forgets to hide a button still
     * cannot reach the endpoint behind it.
     *
     * <p>Writing a listing is the first thing gated: FR075 says an unapproved seller may not list, and this is
     * where "may not" is decided. Reading is not gated — a seller waiting on KYC can still see what their
     * colleagues drafted, and hiding it would look like data loss rather than a hold.
     *
     * <p>Inert until M8 starts writing a KYC status other than {@code NOT_REQUIRED}, which is the point of
     * wiring it now: the rule is in place before the data that triggers it, rather than being retrofitted to
     * the hottest path in the application afterwards.
     */
    private static final Set<String> KYC_GATED = Set.of(
            "PROPERTIES_CREATE", "PROPERTIES_UPDATE", "PROPERTIES_SUBMIT", "PROPERTIES_MEDIA",
            /*
             * A vendor's catalogue (M10), gated for the same reason and by the same rule.
             *
             * An approved vendor's KYC standing is APPROVED, an applicant's and a suspended vendor's is
             * PENDING — so the one gate covers "not checked yet" and "no longer trusted" without a second
             * mechanism knowing about either.
             *
             * WITHDRAW and DELETE are deliberately absent. They only ever reduce what is public, and gating
             * them would leave a suspended vendor unable to take down their own prices — punishing somebody
             * for trying to do the right thing.
             */
            "CATALOGUE_CREATE", "CATALOGUE_UPDATE", "CATALOGUE_SUBMIT");

    private final UserGroupRepository userGroups;
    private final AppModuleRepository appModules;
    private final TenantModuleRepository tenantModules;

    /**
     * @return the action codes this profile may exercise, as Spring Security authorities
     */
    public Set<String> resolve(UserProfile profile) {
        if (profile.getUserGroupId() == null) {
            // No group means no permissions. A user with none can authenticate and see their own profile,
            // and nothing else — the correct state for a freshly created account awaiting a role.
            return Set.of();
        }
        UserGroup group = userGroups.findById(profile.getUserGroupId()).orElse(null);
        // isLive rather than status == 1 throughout. Every update stamps STATUS_EDITED as a
        // changed-since-activation marker, so an ACTIVE-only test would mean editing a group zeroed its
        // members' permissions, and editing a module switched off everything in it.
        if (group == null || !AppConstant.isLive(group.getStatus())) {
            log.debug("Profile {} has no active user group", profile.getId());
            return Set.of();
        }

        Map<String, AppModule> modulesByCode = appModules.findAll().stream()
                .filter(m -> AppConstant.isLive(m.getStatus()))
                .collect(Collectors.toMap(AppModule::getCode, Function.identity(), (a, b) -> a));

        // Null means "the tenant-module axis does not apply to this caller" — see the class comment.
        // An empty set would mean the opposite, and would deny everything.
        Set<String> tenantEnabled = profile.getTenantId() == null
                ? null
                : Set.copyOf(tenantModules.findEnabledModuleCodes(profile.getTenantId()));

        Set<String> granted = new LinkedHashSet<>();
        for (Permission p : group.getPermissions()) {
            if (!AppConstant.isLive(p.getStatus())) continue;

            AppModule module = modulesByCode.get(p.getModuleCode());
            if (module == null) {
                log.debug("Permission {} references unknown or inactive module {}",
                        p.getActionCode(), p.getModuleCode());
                continue;
            }
            if (tenantEnabled != null && !tenantEnabled.contains(module.getCode())) continue;
            if (!module.allows(profile.getUserTypeCode())) continue;
            if (!profile.isKycCleared() && KYC_GATED.contains(p.getActionCode())) continue;

            granted.add(p.getActionCode());
        }
        return granted;
    }
}
