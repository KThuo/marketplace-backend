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
     * Property listings — what the platform is for.
     *
     * <p>Admits the seller side that maintains them and the platform that oversees them. Lender staff are
     * deliberately <strong>not</strong> here: a partnered lender reads a seller's portfolio through the public
     * marketplace and their own mortgage screens, not through the seller's listing management — the module
     * that holds "create", "submit" and "withdraw" is the seller's own workspace.
     *
     * <p>Buyers are not here either. Everything a buyer sees is the public marketplace, which needs no module
     * because it needs no permission.
     */
    PROPERTIES("PROPERTIES", "Listings",
            "The properties a seller offers, from draft to live to sold",
            true, 30,
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,SELLER_OWNER,LISTING_MANAGER,SALES_AGENT,AGENT"),

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
    /**
     * A lender's own products (M3).
     *
     * <p>Lender staff and the platform. Sellers are deliberately absent: a seller sees which lenders they are
     * partnered with, and the rates those lenders offer are shown to <em>buyers</em> against listings — a
     * seller editing or even browsing another organisation's pricing sheet is not a thing the arrangement
     * between them implies.
     */
    MORTGAGE_PRODUCTS("MORTGAGE_PRODUCTS", "Mortgage Products",
            "What each lender offers — rates, terms, deposit and who qualifies",
            true, 35,
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,LENDER_ADMIN,MORTGAGE_OFFICER,CREDIT_ANALYST,AGENT"),

    /**
     * Affordability checks (M3), and <strong>platform staff only</strong>.
     *
     * <p>The narrowest module matrix in the catalogue, and the reason is the data rather than the feature: a
     * check is somebody's household income. A buyer sees their own through the buyer portal, resolved from
     * their identity and needing no permission. A lender learns a buyer's finances when that buyer applies to
     * them — which is M4 — and not by browsing a list. Even here the list carries the outcome and the derived
     * figures, never the raw inputs; the two response records are separate types, as they are for a listing.
     */
    AFFORDABILITY("AFFORDABILITY", "Affordability",
            "What buyers have worked out they can carry, and how the assessor answered",
            false, 36,
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR"),

    /**
     * The seller's inbox (M4).
     *
     * <p>Seller staff and the platform. A lender is not here: an enquiry is a conversation between a buyer
     * and the person selling the house, and a partnership does not make a bank a party to it.
     */
    ENQUIRIES("ENQUIRIES", "Enquiries",
            "Questions buyers have asked about your listings, and the replies",
            true, 40,
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,SELLER_OWNER,LISTING_MANAGER,SALES_AGENT,AGENT"),

    /**
     * Viewings (M4). Admits the same people as enquiries, plus nobody: showing somebody round a house is
     * the seller's own staff, and an agent is one of them until M9 gives agents their own standing.
     */
    SITE_VISITS("SITE_VISITS", "Viewings",
            "Requests to see a property, and the diary of what was agreed",
            true, 45,
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,SELLER_OWNER,LISTING_MANAGER,SALES_AGENT,AGENT"),

    /**
     * Offers (M4).
     *
     * <p>Narrower than the other two: a sales agent may answer a question and show somebody round, but an
     * offer is a commercial decision. The module admits the roles that could hold the decision, and the
     * permission decides which of them actually do.
     */
    PURCHASE_REQUESTS("PURCHASE_REQUESTS", "Offers",
            "Offers buyers have made on your listings",
            true, 50,
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,SELLER_OWNER,LISTING_MANAGER,AGENT"),

    /**
     * KYC (M8 slice 1).
     *
     * <p>Two audiences with opposite needs, and one module because they meet on one screen: a seller
     * assembling their own pack, and Compliance judging it. What separates them is the permission —
     * {@code KYC_SUBMIT} is the seller's, {@code KYC_REVIEW} is the platform's, and the review permission is
     * also the key that opens the documents in the vault.
     *
     * <p>Lender staff are absent. A partnership lets a bank see a seller's portfolio, not their directors'
     * identity documents.
     */
    KYC("KYC", "Compliance",
            "What a seller must produce to be allowed to list, and what Compliance made of it",
            true, 55,
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,SELLER_OWNER"),

    /**
     * Valuation jobs (M5).
     *
     * <p>Admits four populations that see four different things through one module — the platform runs the
     * panel, a seller sees what they commissioned, a lender sees what they commissioned, and a valuer sees
     * what was assigned to them. {@code ValuationScope} is what keeps those apart; the module matrix only
     * decides who reaches the screen.
     */
    VALUATIONS("VALUATIONS", "Valuations",
            "Valuation jobs, from request to signed report",
            true, 60,
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,SELLER_OWNER,LISTING_MANAGER,"
                    + "LENDER_ADMIN,MORTGAGE_OFFICER,CREDIT_ANALYST,VALUER,AGENT"),

    /**
     * The valuer's own workspace (M5).
     *
     * <p>Its own module, admitting only valuers and the platform, and the reason is a bug this caught: the
     * owner-group top-up grants every non-platform-only permission whose module admits the user type — so
     * putting {@code VALUATIONS_WORK} in the {@code VALUATIONS} module gave every seller owner the valuer's
     * verbs. The service refused them, but the screen offered them, which is a permission model saying one
     * thing and a service saying another.
     *
     * <p>The matrix is the right place to fix that, because it is the codebase's stated rule: a user type
     * absent from a module's CSV cannot reach it however the permissions are granted.
     */
    VALUATION_WORK("VALUATION_WORK", "Valuer Workspace",
            "Accepting, declining and reporting on assigned valuation jobs",
            true, 62,
            "SUPER_ADMIN,SUPPORT_ADMIN,VALUER"),

    /**
     * The panel itself (M5).
     *
     * <p>Platform-only, plus the valuer's own row. Who is on the panel, what their indemnity cover is worth
     * and when it lapses is the platform's business — a seller choosing their own valuer would defeat the
     * independence the whole module exists to provide.
     */
    VALUER_PANEL("VALUER_PANEL", "Valuation Panel",
            "The valuers the platform will assign work to",
            true, 61,
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,VALUER"),

    /**
     * Auction (M6, BRD UC006).
     *
     * <p>Lenders realising security, sellers consigning stock, and the platform that publishes the
     * catalogue. Buyers are absent for the usual reason: registering to bid is identity-scoped under
     * {@code /me} and needs no permission, and browsing the catalogue needs no account at all.
     */
    AUCTIONS("AUCTIONS", "Auctions",
            "Lots going to auction, the catalogue, and who has registered to bid",
            true, 65,
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,SELLER_OWNER,LISTING_MANAGER,"
                    + "LENDER_ADMIN,MORTGAGE_OFFICER,CREDIT_ANALYST"),

    /**
     * The auctioneers the platform will list a sale under.
     *
     * <p>Readable by anyone who brings a lot, maintained only by the platform. A lot cannot be published
     * without naming an auctioneer, so a principal has to be able to see who is on the register and whose
     * licence is current — but a licence that has lapsed makes a sale voidable, and whoever benefits from the
     * sale should not be the one confirming it. That line is held by {@code AUCTIONEERS_MANAGE} being
     * platform-only rather than by the module refusing the type: reading the register and writing it are
     * different things, and the first version conflated them into a picker no lender could fill.
     */
    AUCTIONEERS("AUCTIONEERS", "Auctioneers",
            "Licensed auctioneers the platform will publish a sale under",
            true, 66,
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
                    + "LENDER_ADMIN,MORTGAGE_OFFICER,CREDIT_ANALYST,AGENT"),

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
            "BUYER"),

    /**
     * The register of independent agents (M9).
     *
     * <p>Admits agents themselves so they can find each other in a directory, and platform staff who decide
     * applications. Approving is {@code platformOnly} on the permission, not on the module, so an agent can
     * read the register without any chance of deciding their own application.
     */
    AGENTS("AGENTS", "Agents",
            "Independent agents, their applications and the agreements they signed",
            true, 70,
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,AGENT"),

    /**
     * An agent's own application, agreement and profile.
     *
     * <p>Its own module for the reason {@code VALUATION_WORK} is: the owner-group top-up hands every
     * non-platform-only permission whose module admits a user type to every organisation owner of that type,
     * so an agent-only verb sitting in a shared module reaches everybody. This one admits {@code AGENT} and
     * nobody else — not even platform staff, who have {@code AGENTS_VIEW} for the same rows.
     */
    AGENT_SELF("AGENT_SELF", "My Agent Profile",
            "An agent's own registration, agreement and details",
            true, 71,
            "AGENT"),

    /**
     * The vendor register and the taxonomy behind it (M10).
     *
     * <p>Admits vendors so they can see the directory they are part of, and the platform staff who decide
     * applications and maintain the categories. Both of those verbs are {@code platformOnly} on the
     * permission — a vendor can read the register without deciding anybody's application, including their
     * own, and without editing the taxonomy their competitors are filed under.
     */
    VENDORS("VENDORS", "Vendors",
            "Businesses offering services to buyers and sellers, and the categories they are listed under",
            true, 75,
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,VENDOR"),

    /**
     * A vendor's own business and catalogue.
     *
     * <p>Its own module for the reason {@code AGENT_SELF} and {@code VALUATION_WORK} are: the owner-group
     * top-up hands every non-platform-only permission whose module admits a user type to every organisation
     * owner of that type, so a vendor-only verb in a shared module would reach people who are not vendors.
     */
    VENDOR_SELF("VENDOR_SELF", "My Business",
            "A vendor's own registration and the catalogue they publish",
            true, 76,
            "VENDOR"),

    /**
     * What people said, and the right of reply (M7).
     *
     * <p>Buyers are not here: they write ratings through their own portal, which {@code BUYER_PORTAL}
     * already admits them to. What this module admits is the people who are <em>rated</em> — a seller, an
     * agent or a vendor reading and answering what was said about them.
     */
    RATINGS("RATINGS", "Ratings",
            "What buyers said about a listing, an organisation or a service, and the replies",
            true, 80,
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,SELLER_OWNER,LISTING_MANAGER,AGENT,VENDOR"),

    /**
     * The moderation queue (M7).
     *
     * <p>Platform-only as a module, not merely as a permission. Deciding what the public reads about
     * somebody else is not a thing a party to the dispute should be admitted to at all — and unlike the
     * approval queue there is no legitimate second side here.
     */
    MODERATION("MODERATION", "Moderation",
            "Reviews somebody has objected to, or that were held on the way in",
            true, 81,
            "SUPER_ADMIN,SUPPORT_ADMIN"),

    /**
     * Where new work lands (M12).
     *
     * <p>Both sides: the platform routes what reaches it, and a seller routes their own leads among their
     * own staff. A seller's rules can only ever point inside their own organisation, which the service
     * enforces — routing to somebody else's staff would be a data leak dressed as a workflow setting.
     */
    ASSIGNMENT("ASSIGNMENT", "Routing",
            "Rules deciding whose desk a new enquiry, viewing or offer lands on",
            true, 85,
            "SUPER_ADMIN,SUPPORT_ADMIN,SELLER_OWNER,LENDER_ADMIN,AGENT"),

    /**
     * The diary (M12).
     *
     * <p>Admits everybody whose week has appointments in it. What each of them sees is scoped the ordinary
     * way — a seller their organisation's, the platform everybody's.
     */
    CALENDAR("CALENDAR", "Diary",
            "Viewings, auctions and whatever else somebody put in the week",
            true, 86,
            "SUPER_ADMIN,SUPPORT_ADMIN,PLATFORM_AUDITOR,SELLER_OWNER,LISTING_MANAGER,SALES_AGENT,"
                    + "AGENT,VENDOR,LENDER_ADMIN");

    private final String code;
    private final String displayName;
    private final String description;
    private final boolean core;
    private final int sortOrder;
    private final String allowedUserTypes;
}
