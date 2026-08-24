package com.hodi.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Catalogue of feature modules. Seeded into {@code app_modules}.
 *
 * <p>{@code allowedUserTypes} is a CSV of {@link UserTypeEnum} codes and is <strong>enforced</strong>,
 * not advisory: it is axis (1) of the three access-control axes in plan section 4. A user type absent from a
 * module's CSV cannot reach that module no matter what permissions an organisation grants it. This is what
 * keeps a mortgage officer out of listing management and a listing manager out of credit decisioning — one
 * editable field rather than two parallel permission catalogues. The super admin edits it per module at
 * runtime; the values below are only the seeded defaults.
 *
 * <p>Matching is a set-membership check over the split CSV, never a SQL {@code LIKE}: {@code '%ADMIN%'} would
 * also match {@code SUPER_ADMIN} and {@code LENDER_ADMIN}, and all three codes exist.
 *
 * <p>{@code core = true} modules are enabled for every seller organisation at onboarding and cannot be
 * switched off; the rest are opt-in per tenant via {@code tenant_modules}.
 *
 * <p>Only the access-management modules are declared here. The functional domain — listings, valuations,
 * mortgage products, applications — appends its own constants as those slices land.
 */
@Getter
@RequiredArgsConstructor
public enum AppModuleEnum {

    // ── Platform administration ───────────────────────────────────────────────
    /**
     * Admits SELLER_OWNER as well as the platform types, which looks wrong at a glance and is not.
     *
     * <p>Almost every permission in this module is {@code platformOnly}, so a seller owner reaching the
     * module still cannot onboard, suspend or terminate anybody — those codes are refused by the permission
     * layer regardless of the CSV. What the CSV admits them to is {@code TENANT_SELF_VIEW} and
     * {@code TENANT_SELF_UPDATE}: their own organisation's profile.
     *
     * <p>Leaving SELLER_OWNER out was the first version, and it made the seeded owner group internally
     * inconsistent — it was granted self-view for a module that did not admit it, so
     * {@code EffectivePermissionResolver} dropped the authority at login and the seller could not open their
     * own settings page. The module matrix and the permission catalogue have to agree, and this is one of the
     * two places that agreement is expressed.
     */
    TENANTS("TENANTS", "Seller Organisations",
            "Seller lifecycle — onboarding, suspension, reinstatement, termination",
            true, 10,
            // PLATFORM_AUDITOR reads only: their template holds TENANTS_VIEW and nothing else here. An
            // auditor who cannot resolve an organisation's name is reading a trail of ids.
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,SELLER_OWNER"),
    /**
     * Admits lender staff for the same reason TENANTS admits sellers — their own institution's profile —
     * and admits sellers because choosing a finance partner means reading the directory of lenders.
     * The lifecycle codes are all {@code platformOnly}, so nobody but the platform can register or
     * deactivate an institution.
     */
    INSTITUTIONS("INSTITUTIONS", "Lending Institutions",
            "Banks, SACCOs and other lenders whose officers work seller portfolios",
            true, 20,
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,SELLER_OWNER,LENDER_ADMIN,MORTGAGE_OFFICER,"
                    + "CREDIT_ANALYST"),
    /**
     * Reached from all three sides, because a partnership has three legitimate parties: the seller who
     * wants finance offered on their portfolio, the lender who wants the portfolio, and the platform that
     * arbitrates. Approval permissions are what separate them, not module access.
     */
    PARTNERSHIPS("PARTNERSHIPS", "Partnerships",
            "Which lenders may see which seller's portfolio — the only thing that widens a lender's visibility",
            true, 30,
            // Officers and analysts are admitted to *read*: a mortgage officer needs to know which sellers
            // they may work, and that list is this module. Proposing, approving and revoking are separate
            // permissions, so admitting them here does not let them create or end an arrangement.
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,SELLER_OWNER,LENDER_ADMIN,MORTGAGE_OFFICER,"
                    + "CREDIT_ANALYST"),
    USER_TYPES("USER_TYPES", "User Types",
            "Global user-type catalogue",
            true, 40,
            "SUPER_ADMIN"),
    APP_MODULES("APP_MODULES", "App Modules",
            "Module catalogue and the user types each module admits",
            true, 50,
            "SUPER_ADMIN"),
    PERMISSIONS("PERMISSIONS", "Permissions",
            "Permission catalogue",
            true, 60,
            "SUPER_ADMIN"),

    // ── Identity, shared by the platform and both kinds of organisation ───────
    USER_GROUPS("USER_GROUPS", "User Groups",
            "Roles as permission bundles — global templates plus organisation-owned groups",
            true, 70,
            "SUPER_ADMIN,SELLER_OWNER,LENDER_ADMIN"),
    USERS("USERS", "Users",
            "Platform staff, seller staff and lender staff",
            true, 80,
            "SUPER_ADMIN,SUPPORT_ADMIN,SELLER_OWNER,LENDER_ADMIN"),

    // ── Cross-cutting ─────────────────────────────────────────────────────────
    APP_SETTINGS("APP_SETTINGS", "Settings",
            "Global system configuration, with by-exception per-tenant overrides",
            true, 90,
            "SUPER_ADMIN,SELLER_OWNER,LENDER_ADMIN"),
    /**
     * The one module in this phase that is <strong>not</strong> core, and the only one a seller can be
     * switched off from.
     *
     * <p>Everything else here administers the platform or the organisation itself — switching one off would
     * break the screens needed to switch it back on, which is what {@code core} means. An audit trail is
     * different in kind: it is a compliance and oversight feature that a small seller may not want and that
     * a larger one certainly does, so it is the natural first thing to gate per organisation.
     *
     * <p>It also means access axis (1) has something real to act on. While every module was core, the
     * per-tenant gate was code with nothing to gate — {@code TenantModuleService} refuses to disable a core
     * module, so the whole path was unreachable and therefore unverified.
     */
    /**
     * The Maker/Checker queue (plan §3.2).
     *
     * <p>Admits every user type that can hold an approve permission in any module, because the queue is one
     * screen serving all of them — a lender administrator deciding a partnership and a listing manager
     * deciding a listing are looking at the same list, filtered to their own organisation.
     *
     * <p>Core, unlike the audit trail. Maker/Checker is a control the BRD requires across the platform, and
     * this is the only screen that shows what is waiting for one — an organisation switched off from it would
     * still be subject to the rule, with no way to see what it was holding up. What can be granted or withheld
     * is the permission, and that is per user group where it belongs.
     */
    APPROVALS("APPROVALS", "Approvals",
            "What is waiting for a second person to agree with it",
            true, 95,
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,SELLER_OWNER,LISTING_MANAGER,"
                    + "LENDER_ADMIN,MORTGAGE_OFFICER,CREDIT_ANALYST"),
    AUDIT("AUDIT", "Audit Trail",
            "Who changed what, when, and what it looked like before",
            false, 100,
            "SUPER_ADMIN,PLATFORM_AUDITOR,SELLER_OWNER,LENDER_ADMIN"),
    DASHBOARD("DASHBOARD", "Dashboard",
            "The landing figures for whichever kind of user is signed in",
            true, 110,
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,SELLER_OWNER,LISTING_MANAGER,SALES_AGENT,"
                    + "LENDER_ADMIN,MORTGAGE_OFFICER,CREDIT_ANALYST"),

    /**
     * Admits a buyer to their own area, and nothing else.
     *
     * <p>Deliberately the only module a {@code BUYER} appears in. Everything a buyer can see is scoped by
     * identity inside that surface, so there is no second module to grant and no permission that would widen
     * it — which is the property that makes "a buyer cannot reach the back office" true by construction
     * rather than by remembering to check.
     */
    BUYER_PORTAL("BUYER_PORTAL", "Buyer Portal",
            "A buyer's own saved properties, enquiries and finance applications",
            true, 120,
            "BUYER");

    private final String code;
    private final String displayName;
    private final String description;
    private final boolean core;
    private final int sortOrder;
    private final String allowedUserTypes;
}
