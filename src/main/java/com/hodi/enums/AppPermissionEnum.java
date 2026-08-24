package com.hodi.enums;

import lombok.Getter;

/**
 * Granular action codes per module. Seeded into {@code permissions} — each constant becomes one row with
 * {@code action_code} = the constant name. Spring Security authorities are these bare codes, e.g.
 * {@code @PreAuthorize("hasAuthority('USERS_CREATE')")}.
 *
 * <p>This is axis (2) of plan section 4: an organisation composes a user group from <em>any</em> subset of
 * this catalogue, of any size. There are no hardcoded role-to-permission bundles anywhere in the codebase;
 * the seeded global templates are ordinary rows an organisation can clone and edit.
 *
 * <p>Lifecycle verbs follow the house convention {@code _VIEW / _VIEW_OWN / _CREATE / _UPDATE /
 * _DEACTIVATE / _ACTIVATE / _DELETE}, where {@code _DELETE} is a soft archive to {@code status = 5}, never
 * a hard delete. Sensitive capabilities get their own code rather than riding on {@code _UPDATE}, so they can
 * be granted and audited individually.
 *
 * <p>{@code platformOnly} permissions are never handed out by any automatic grant — not by a role template,
 * and not by the organisation system-group top-up that gives an owner "everything". It is a column on
 * {@code permissions} rather than a check in the seeder because <strong>both</strong> things that hand out
 * permissions have to honour it, and a rule living in one of them is a rule the other will break.
 */
@Getter
public enum AppPermissionEnum {

    // ── SELLER ORGANISATIONS (platform-side lifecycle) ────────────────────────
    TENANTS_VIEW("See seller organisations", AppModuleEnum.TENANTS, true),
    TENANTS_CREATE("Onboard a seller organisation", AppModuleEnum.TENANTS, true),
    TENANTS_UPDATE("Change a seller organisation's details", AppModuleEnum.TENANTS, true),
    TENANTS_DEACTIVATE("Deactivate a seller organisation", AppModuleEnum.TENANTS, true),
    TENANTS_ACTIVATE("Activate a seller organisation", AppModuleEnum.TENANTS, true),
    /**
     * Suspension and deactivation are not the same act and do not share a code. Deactivating is
     * administrative tidying; suspending stops a live business trading, which is the kind of thing that
     * should be grantable to someone who cannot do the other.
     */
    TENANTS_SUSPEND("Suspend a seller organisation", AppModuleEnum.TENANTS, true),
    TENANTS_REINSTATE("Reinstate a suspended seller organisation", AppModuleEnum.TENANTS, true),
    TENANTS_TERMINATE("Terminate a seller organisation", AppModuleEnum.TENANTS, true),
    /**
     * Cross-organisation totals, and platform-only for the reason the tenant list is not: reusing
     * {@code TENANTS_VIEW} would have quietly made "may see the list of sellers" mean "may see every
     * seller's numbers".
     */
    PLATFORM_ANALYTICS_VIEW("See platform-wide analytics", AppModuleEnum.TENANTS, true),
    /** A seller reading their own organisation record — their business, and not their agents'. */
    TENANT_SELF_VIEW("See this organisation's own profile and settings", AppModuleEnum.TENANTS),
    TENANT_SELF_UPDATE("Change this organisation's own profile", AppModuleEnum.TENANTS),

    // ── LENDING INSTITUTIONS ──────────────────────────────────────────────────
    INSTITUTIONS_VIEW("See lending institutions", AppModuleEnum.INSTITUTIONS, true),
    INSTITUTIONS_CREATE("Register a lending institution", AppModuleEnum.INSTITUTIONS, true),
    INSTITUTIONS_UPDATE("Change a lending institution's details", AppModuleEnum.INSTITUTIONS, true),
    INSTITUTIONS_DEACTIVATE("Deactivate a lending institution", AppModuleEnum.INSTITUTIONS, true),
    INSTITUTIONS_ACTIVATE("Activate a lending institution", AppModuleEnum.INSTITUTIONS, true),
    /** A lender reading their own institution record. Tenant-side equivalent of TENANT_SELF_VIEW. */
    INSTITUTION_SELF_VIEW("See this institution's own profile", AppModuleEnum.INSTITUTIONS),
    INSTITUTION_SELF_UPDATE("Change this institution's own profile", AppModuleEnum.INSTITUTIONS),

    // ── PARTNERSHIPS ──────────────────────────────────────────────────────────
    /**
     * Reading the partnership list. Held by all three parties, and the row set each of them sees differs —
     * the platform sees every partnership, a seller sees their own, a lender sees theirs. That narrowing is
     * {@code TenantScope}'s job, not this permission's.
     */
    PARTNERSHIPS_VIEW("See partnerships", AppModuleEnum.PARTNERSHIPS),
    PARTNERSHIPS_REQUEST("Propose a partnership", AppModuleEnum.PARTNERSHIPS),
    /**
     * Approving is separated from requesting on purpose, and it is the permission that actually widens who
     * can read a seller's portfolio. Whoever holds this is deciding that another organisation's staff may
     * see this one's data.
     */
    PARTNERSHIPS_APPROVE("Approve a proposed partnership", AppModuleEnum.PARTNERSHIPS),
    PARTNERSHIPS_REVOKE("Revoke an active partnership", AppModuleEnum.PARTNERSHIPS),

    // ── USER TYPES ────────────────────────────────────────────────────────────
    USER_TYPES_VIEW("See user types", AppModuleEnum.USER_TYPES, true),
    USER_TYPES_CREATE("Create a user type", AppModuleEnum.USER_TYPES, true),
    USER_TYPES_UPDATE("Change a user type", AppModuleEnum.USER_TYPES, true),
    USER_TYPES_DEACTIVATE("Deactivate a user type", AppModuleEnum.USER_TYPES, true),
    USER_TYPES_ACTIVATE("Activate a user type", AppModuleEnum.USER_TYPES, true),
    USER_TYPES_DELETE("Archive a user type", AppModuleEnum.USER_TYPES, true),

    // ── APP MODULES ───────────────────────────────────────────────────────────
    APP_MODULES_VIEW("See the module catalogue", AppModuleEnum.APP_MODULES, true),
    APP_MODULES_UPDATE("Change a module and the user types it admits", AppModuleEnum.APP_MODULES, true),
    /** Switching a module on or off for one seller organisation. */
    TENANT_MODULES_MANAGE("Enable and disable modules per seller organisation",
            AppModuleEnum.APP_MODULES, true),

    // ── PERMISSIONS ───────────────────────────────────────────────────────────
    PERMISSIONS_VIEW("See the permission catalogue", AppModuleEnum.PERMISSIONS, true),

    // ── USER GROUPS ───────────────────────────────────────────────────────────
    USER_GROUPS_VIEW("See user groups", AppModuleEnum.USER_GROUPS),
    USER_GROUPS_CREATE("Create a user group", AppModuleEnum.USER_GROUPS),
    USER_GROUPS_UPDATE("Change a user group and its permissions", AppModuleEnum.USER_GROUPS),
    USER_GROUPS_CLONE("Clone a global role template", AppModuleEnum.USER_GROUPS),
    USER_GROUPS_DEACTIVATE("Deactivate a user group", AppModuleEnum.USER_GROUPS),
    USER_GROUPS_ACTIVATE("Activate a user group", AppModuleEnum.USER_GROUPS),
    USER_GROUPS_DELETE("Archive a user group", AppModuleEnum.USER_GROUPS),

    // ── USERS ─────────────────────────────────────────────────────────────────
    USERS_VIEW("See users", AppModuleEnum.USERS),
    USERS_CREATE("Create a user", AppModuleEnum.USERS),
    USERS_UPDATE("Change a user", AppModuleEnum.USERS),
    USERS_DEACTIVATE("Deactivate a user", AppModuleEnum.USERS),
    USERS_ACTIVATE("Activate a user", AppModuleEnum.USERS),
    USERS_DELETE("Archive a user", AppModuleEnum.USERS),
    /**
     * Its own code rather than part of {@code USERS_UPDATE}. Issuing somebody a temporary password is a way
     * to take over their account, so it is the sort of thing a supervisor who can edit a phone number should
     * not automatically also be able to do.
     */
    USERS_RESET_PASSWORD("Issue a user a temporary password", AppModuleEnum.USERS),
    /** Ending somebody else's live sessions. Separate for the same reason as the above. */
    USERS_REVOKE_SESSIONS("Sign a user out of every device", AppModuleEnum.USERS),

    // ── SETTINGS ──────────────────────────────────────────────────────────────
    APP_SETTINGS_VIEW("See settings", AppModuleEnum.APP_SETTINGS),
    APP_SETTINGS_UPDATE("Change global settings", AppModuleEnum.APP_SETTINGS, true),
    /** Shadowing an overridable global key with an organisation's own value. */
    APP_SETTINGS_OVERRIDE("Override an overridable setting for this organisation",
            AppModuleEnum.APP_SETTINGS),
    /**
     * Reading a secret back in the clear. The most sensitive read in this module: everything else shows a
     * masked value, and this is what un-masks a gateway credential.
     */
    APP_SETTINGS_VIEW_SECRET("Reveal a stored secret", AppModuleEnum.APP_SETTINGS),

    // ── AUDIT ─────────────────────────────────────────────────────────────────
    AUDIT_VIEW("See the audit trail", AppModuleEnum.AUDIT),

    // ── DASHBOARD ─────────────────────────────────────────────────────────────
    DASHBOARD_VIEW("See the dashboard", AppModuleEnum.DASHBOARD),

    // ── BUYER PORTAL ──────────────────────────────────────────────────────────
    /**
     * The one permission a buyer holds. It admits them to their own area; what they see inside it is
     * resolved from their own user id, so there is nothing further to grant and nothing that widens it.
     */
    BUYER_PORTAL_ACCESS("Use the buyer portal", AppModuleEnum.BUYER_PORTAL);

    private final String actionName;
    private final AppModuleEnum module;
    private final boolean platformOnly;

    AppPermissionEnum(String actionName, AppModuleEnum module) {
        this(actionName, module, false);
    }

    AppPermissionEnum(String actionName, AppModuleEnum module, boolean platformOnly) {
        this.actionName = actionName;
        this.module = module;
        this.platformOnly = platformOnly;
    }

    /** The action code as it appears in {@code @PreAuthorize} and in {@code permissions.action_code}. */
    public String getActionCode() {
        return name();
    }
}
