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

    /*
     * The INSTITUTIONS_*, INSTITUTION_SELF_* and PARTNERSHIPS_* codes used to sit here and just below.
     *
     * They gated a directory of lending institutions and the negotiation by which one of them was granted
     * sight of a seller's portfolio. Both modules are gone from AppModuleEnum: there is one bank, it runs
     * the platform, and its people are platform staff — so there is no list of rivals to administer and
     * nothing to request, approve or revoke.
     *
     * Removing them from this enum is not enough on its own. SeederService only ever adds and reconciles;
     * it never retires a row whose enum entry has disappeared, so the permissions would stay on every group
     * that holds them and the workspace navigation — which filters on the effective permission set — would
     * go on showing "Banks" and "Partnerships". V20260914140000 is what actually removes them.
     */
    // ── PROPERTY LISTINGS ─────────────────────────────────────────────────────
    PROPERTIES_VIEW("See the organisation's listings", AppModuleEnum.PROPERTIES),
    PROPERTIES_CREATE("Draft a new listing", AppModuleEnum.PROPERTIES),
    PROPERTIES_UPDATE("Change a listing", AppModuleEnum.PROPERTIES),
    /**
     * Sending a listing for approval, separate from changing one.
     *
     * <p>This is the Maker half of Maker/Checker: an agent who may draft and edit need not be somebody who
     * can put the organisation's name behind a listing, and the two being one permission would make that
     * distinction unexpressible.
     */
    PROPERTIES_SUBMIT("Submit a listing for approval", AppModuleEnum.PROPERTIES),
    /** The Checker half. Held by whoever answers for what the organisation publishes. */
    PROPERTIES_APPROVE("Approve and publish a listing", AppModuleEnum.PROPERTIES),
    PROPERTIES_WITHDRAW("Take a live listing down", AppModuleEnum.PROPERTIES),
    PROPERTIES_MARK_SOLD("Mark a listing sold", AppModuleEnum.PROPERTIES),
    PROPERTIES_MEDIA("Add and remove photographs", AppModuleEnum.PROPERTIES),
    PROPERTIES_DELETE("Archive a listing", AppModuleEnum.PROPERTIES),

    // ── APPROVALS (Maker/Checker) ─────────────────────────────────────────────
    /**
     * Sight of the queue, and only that.
     *
     * <p>There is deliberately no {@code APPROVALS_DECIDE}. Deciding requires the permission of the module
     * the request belongs to — {@code PARTNERSHIPS_APPROVE} for a partnership — so a single approve-anything
     * code would be a way around every module's own gate, granted from one screen. Somebody can hold this and
     * watch what is waiting without being able to move any of it.
     */
    APPROVALS_VIEW("See what is waiting for approval", AppModuleEnum.APPROVALS),

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
    /**
     * Letting a newly created account sign in.
     *
     * <p>Not part of {@code USERS_ACTIVATE}, which switches a working account back on. This one decides
     * whether a person exists on the platform at all, and the seller sells through the bank — so it is the
     * bank's decision, held by whoever the bank chooses, and in no role template by default.
     */
    USERS_APPROVE("Approve a new user account", AppModuleEnum.USERS),

    // ── SELLER APPLICATIONS ───────────────────────────────────────────────────
    /*
     * The bank's side of seller onboarding. Platform-only throughout: deciding who may sell through Co-op
     * is Co-op's decision, and there is no organisation to delegate it to — the applicant has none yet.
     */
    SELLERS_VIEW("See seller applications", AppModuleEnum.TENANTS, true),
    SELLERS_DECIDE("Approve or refuse a seller application", AppModuleEnum.TENANTS, true),

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

    // ── Developments ─────────────────────────────────────────────────────────
    DEVELOPMENTS_VIEW("See developments", AppModuleEnum.DEVELOPMENTS),
    DEVELOPMENTS_CREATE("Draft a development", AppModuleEnum.DEVELOPMENTS),
    DEVELOPMENTS_UPDATE("Change a development", AppModuleEnum.DEVELOPMENTS),
    DEVELOPMENTS_SUBMIT("Send a development for approval", AppModuleEnum.DEVELOPMENTS),
    DEVELOPMENTS_APPROVE("Approve a development for the marketplace", AppModuleEnum.DEVELOPMENTS),
    DEVELOPMENTS_WITHDRAW("Take a development off the marketplace", AppModuleEnum.DEVELOPMENTS),
    DEVELOPMENTS_DELETE("Archive a development", AppModuleEnum.DEVELOPMENTS),
    DEVELOPMENTS_MEDIA("Add and remove a development's photographs", AppModuleEnum.DEVELOPMENTS),
    DEVELOPMENTS_PHASES("Manage a development's phases", AppModuleEnum.DEVELOPMENTS),
    DEVELOPMENTS_PROGRESS("Post build progress", AppModuleEnum.DEVELOPMENTS),
    /**
     * Granting another organisation rights on your development.
     *
     * <p>Its own code rather than riding on UPDATE, because it is the only action in this module that widens
     * somebody else's visibility — and a permission that hands out access should be grantable separately from
     * one that edits a name.
     */
    DEVELOPMENTS_GRANT("Give a developer rights on a project", AppModuleEnum.DEVELOPMENTS),

    /**
     * The money on a development: budget, facility, the cost ledger, drawdowns and the figures derived from
     * them. Apart from {@code DEVELOPMENTS_VIEW}, because a contractor granted progress rights on a bank's
     * project can read the project without reading the bank's facility.
     */
    DEVELOPMENTS_FINANCE_VIEW("See a development's budget, spend, facility and receivables",
            AppModuleEnum.DEVELOPMENTS),
    /** Writing a cost line or a drawdown. Anyone with this and access to the development may record. */
    DEVELOPMENTS_FINANCE_RECORD("Record costs and facility drawdowns on a development",
            AppModuleEnum.DEVELOPMENTS),
    /**
     * Who collects a development's money and who manages its spending. The bank's decision alone: it sells on
     * the owner's behalf and holds the buyers' money, and it may finance or run the project.
     */
    DEVELOPMENT_FINANCE_SETTINGS("Set who collects and who manages spending on a development",
            AppModuleEnum.DEVELOPMENTS, true),
    /** The cost categories every development's ledger is filed under — a platform taxonomy. */
    COST_CATEGORIES_MANAGE("Add, rename and suspend development cost categories",
            AppModuleEnum.DEVELOPMENTS, true),

    UNITS_VIEW("See the unit inventory", AppModuleEnum.DEVELOPMENTS),
    UNITS_MANAGE("Add, generate and change units", AppModuleEnum.DEVELOPMENTS),
    /** Reserving and selling. Separate from MANAGE: one arranges the inventory, the other commits a unit. */
    UNITS_SELL("Reserve, sell and release units", AppModuleEnum.DEVELOPMENTS),

    BOOKINGS_VIEW("See bookings and their balances", AppModuleEnum.DEVELOPMENTS),
    BOOKINGS_MANAGE("Book a unit, agree it, cancel it", AppModuleEnum.DEVELOPMENTS),

    // ── PAYMENTS ──────────────────────────────────────────────────────────────
    PAYMENTS_VIEW("See payments and receipts", AppModuleEnum.PAYMENTS),
    /*
     * Recording money is its own permission, apart from managing the booking.
     *
     * A sales agent books units all day and should not be able to write down that money arrived; the person
     * who reconciles the bank statement does exactly that and books nothing. Folding the two together would
     * mean everybody who can take a name can also move a balance.
     *
     * Platform-only, because the bank collects. A seller sells through Co-op and money never reaches them
     * directly, so writing down that it arrived is not theirs to do — they read the receipts like everybody
     * else. This was not platform-only, and the blanket grant in TenantService.createOwnerGroup therefore
     * handed every seller owner the ability to record and reverse money against their own bookings.
     */
    PAYMENTS_RECEIVE("Record money received against a booking", AppModuleEnum.PAYMENTS, true),
    /** Reversing money already receipted. The bank's, for the same reason as recording it. */
    PAYMENTS_VOID("Void a payment, with a reason", AppModuleEnum.PAYMENTS, true),
    PAYMENT_TYPES_VIEW("See payment methods and the accounts money is collected into",
            AppModuleEnum.PAYMENTS),
    /**
     * Where the money lands. The highest-consequence configuration change in the product, which is why
     * every write under it also takes a one-time code sent to the organisation itself.
     *
     * <p>Platform-only for the same reason as receiving it, and more sharply: the account money is
     * collected into is the bank's, and a seller who could change it could redirect funds that are not
     * theirs to redirect. The one-time code was protecting the wrong perimeter on its own.
     */
    PAYMENT_TYPES_MANAGE("Set up, change and withdraw payment accounts", AppModuleEnum.PAYMENTS, true),
    /**
     * The second pair of eyes on where money lands.
     *
     * <p>Separate from {@code PAYMENT_TYPES_MANAGE} on purpose, and the separation <em>is</em> the control:
     * one permission to propose an account, another to let it start collecting. Held by one person, they
     * are not Maker/Checker at all — and the database CHECK still bars the submitter from deciding their
     * own, so even that person cannot wave their own change through.
     */
    PAYMENTS_ACCOUNT_APPROVE("Approve a payment account before it collects", AppModuleEnum.PAYMENTS, true),
    /**
     * The catalogue is shared by every organisation on the platform, so switching a channel on or off is
     * the platform's decision and nobody else's.
     */
    PAYMENT_CATALOGUE_MANAGE("Switch payment methods on or off for the whole platform",
            AppModuleEnum.PAYMENTS, true),
    /**
     * The bank's side of the ledger: every notification that arrived, placed or not.
     *
     * <p>Reading it is scoped like payments are — an organisation sees the money that landed in its own
     * accounts — because "what has the bank told us" is a question a seller is entitled to ask.
     */
    STATEMENTS_VIEW("See bank notifications, matched and unmatched", AppModuleEnum.PAYMENTS),
    /**
     * Deciding whose money an unmatched credit is.
     *
     * <p>Separate from recording a payment, and the separation is the point: "money arrived, write it
     * down" and "that credit belongs to <em>this</em> booking" are different authorities, and the second
     * is the one a mistyped reference turns into somebody else's balance. Platform-only, as recording is,
     * because the bank collects and the bank decides.
     */
    STATEMENTS_RECONCILE("Apply an unmatched bank credit to a booking, or set it aside",
            AppModuleEnum.PAYMENTS, true),

    // ── DISBURSEMENTS: the bank sends money out ───────────────────────────────
    /**
     * Money leaving the bank's own account. Not a payment — nothing here credits a booking — and the one
     * flow on the platform with no undo, which is why it is three permissions and all three the platform's.
     */
    DISBURSEMENTS_VIEW("See disbursements and what became of them", AppModuleEnum.PAYMENTS),
    /**
     * Proposing a transfer: naming the payee, validating the account, stating the purpose.
     *
     * <p>No longer the platform's alone: a development pays its beneficiaries from the owner's own account, and
     * the owner's makers propose that. What stays the bank's is deciding <em>which</em> money — a development's
     * payment is decided by whichever side manages its spending, the bank's own payouts by the bank.
     */
    DISBURSEMENTS_MAKE("Propose a disbursement", AppModuleEnum.PAYMENTS),
    /**
     * Releasing it. Separate from proposing, and the separation is the control: the maker names the account,
     * the checker reads the name Co-op resolved it to, and only then does money move.
     */
    DISBURSEMENTS_APPROVE("Approve a disbursement before it is sent", AppModuleEnum.PAYMENTS),

    // ── BENEFICIARIES ─────────────────────────────────────────────────────────
    /**
     * The people and companies an organisation pays. An owner's staff hold these for their own; the bank's
     * staff hold them for everybody, because on a project the bank manages it registers who is paid.
     */
    BENEFICIARIES_VIEW("See the people and companies a development pays", AppModuleEnum.PAYMENTS),
    BENEFICIARIES_MANAGE("Register beneficiaries and change where they are paid", AppModuleEnum.PAYMENTS),
    /** Its own permission: held by the person who registered the payee, it is not a second pair of eyes. */
    BENEFICIARIES_APPROVE("Approve a beneficiary before it can be paid", AppModuleEnum.PAYMENTS),
    /**
     * The accounts a development pays from — the owner's own money. Nothing to do with collecting, which the
     * bank decides per development; an organisation whose buyers pay the bank still pays its contractors from
     * an account of its own, and this is the permission to set one up.
     */
    DEBIT_ACCOUNTS_MANAGE("Set up the accounts a development pays from", AppModuleEnum.PAYMENTS),
    /** The kinds of payee — a platform list, like cost categories. */
    BENEFICIARY_TYPES_MANAGE("Add, rename and suspend beneficiary types", AppModuleEnum.PAYMENTS, true),

    // ── MORTGAGE PRODUCTS ─────────────────────────────────────────────────────
    MORTGAGE_PRODUCTS_VIEW("See mortgage products", AppModuleEnum.MORTGAGE_PRODUCTS),
    MORTGAGE_PRODUCTS_CREATE("Create a mortgage product", AppModuleEnum.MORTGAGE_PRODUCTS),
    MORTGAGE_PRODUCTS_UPDATE("Change a mortgage product", AppModuleEnum.MORTGAGE_PRODUCTS),
    /**
     * Its own code rather than part of {@code UPDATE}, and the reason is the same one that separates issuing
     * a temporary password from editing a phone number: drafting a rate and putting it in front of the public
     * are different acts, and an institution may well want them done by different people.
     */
    MORTGAGE_PRODUCTS_PUBLISH("Publish or withdraw a mortgage product",
            AppModuleEnum.MORTGAGE_PRODUCTS),
    MORTGAGE_PRODUCTS_DEACTIVATE("Deactivate a mortgage product", AppModuleEnum.MORTGAGE_PRODUCTS),
    MORTGAGE_PRODUCTS_ACTIVATE("Activate a mortgage product", AppModuleEnum.MORTGAGE_PRODUCTS),
    MORTGAGE_PRODUCTS_DELETE("Archive a mortgage product", AppModuleEnum.MORTGAGE_PRODUCTS),

    // ── AFFORDABILITY ─────────────────────────────────────────────────────────
    /**
     * Reading the platform's list of affordability checks. Platform-only, like the module that holds it —
     * and even with it, the response carries outcomes rather than anybody's income.
     */
    AFFORDABILITY_VIEW("See affordability checks", AppModuleEnum.AFFORDABILITY, true),

    // ── ENQUIRIES ─────────────────────────────────────────────────────────────
    ENQUIRIES_VIEW("See enquiries", AppModuleEnum.ENQUIRIES),
    ENQUIRIES_REPLY("Reply to an enquiry", AppModuleEnum.ENQUIRIES),
    /** Handing a conversation to a colleague. Separate because it changes whose work it is. */
    ENQUIRIES_ASSIGN("Assign an enquiry to somebody", AppModuleEnum.ENQUIRIES),
    ENQUIRIES_CLOSE("Close an enquiry", AppModuleEnum.ENQUIRIES),

    // ── VIEWINGS ──────────────────────────────────────────────────────────────
    SITE_VISITS_VIEW("See viewing requests", AppModuleEnum.SITE_VISITS),
    /** Confirming a time, offering another, or declining. All one act from the buyer's side. */
    SITE_VISITS_DECIDE("Confirm, move or decline a viewing", AppModuleEnum.SITE_VISITS),
    SITE_VISITS_COMPLETE("Record what happened at a viewing", AppModuleEnum.SITE_VISITS),

    // ── OFFERS ────────────────────────────────────────────────────────────────
    PURCHASE_REQUESTS_VIEW("See offers", AppModuleEnum.PURCHASE_REQUESTS),
    /**
     * Accepting or declining an offer. The most consequential permission a seller organisation grants:
     * whoever holds it can tell a buyer their offer on a house has been accepted.
     */
    PURCHASE_REQUESTS_DECIDE("Accept or decline an offer", AppModuleEnum.PURCHASE_REQUESTS),

    // ── KYC ───────────────────────────────────────────────────────────────────
    /** Seeing your own organisation's pack, or — with the review permission — everybody's. */
    KYC_VIEW("See KYC packs", AppModuleEnum.KYC),
    /** Assembling and submitting your own organisation's pack. The seller's half of the module. */
    KYC_SUBMIT("Upload documents and submit for review", AppModuleEnum.KYC),
    /**
     * Compliance's half, and the most powerful permission on the platform.
     *
     * <p>It decides whether an organisation may list at all, and it is the ACL key that opens every document
     * in a pack — identity documents, company registers, tax certificates. Platform-only, and it should be
     * held by the smallest number of people the work allows.
     */
    KYC_REVIEW("Review and decide KYC packs", AppModuleEnum.KYC, true),

    // ── VALUATIONS ────────────────────────────────────────────────────────────
    VALUATIONS_VIEW("See valuations", AppModuleEnum.VALUATIONS),
    /** Commissioning one. Held by whoever needs the figure, never by the platform. */
    VALUATIONS_REQUEST("Request a valuation", AppModuleEnum.VALUATIONS),
    /**
     * Putting a valuer on a job.
     *
     * <p>Platform-only, and the reason the panel is independent: a seller who could choose their own valuer
     * would be choosing the figure, which is the thing the bank is relying on not being true.
     */
    VALUATIONS_ASSIGN("Assign a valuer to a job", AppModuleEnum.VALUATIONS, true),
    VALUATIONS_CANCEL("Cancel a valuation", AppModuleEnum.VALUATIONS),
    /**
     * The valuer's own verbs: take it, hand it back, answer it.
     *
     * <p>In {@code VALUATION_WORK} rather than {@code VALUATIONS}, because that module admits only valuers
     * and the platform. Left in {@code VALUATIONS} it reached every seller owner through the owner-group
     * top-up — see the module's own note.
     */
    VALUATIONS_WORK("Accept, decline and report on assigned jobs", AppModuleEnum.VALUATION_WORK),

    // ── VALUATION PANEL ───────────────────────────────────────────────────────
    VALUER_PANEL_VIEW("See the valuation panel", AppModuleEnum.VALUER_PANEL),
    VALUER_PANEL_MANAGE("Onboard valuers and manage panel membership",
            AppModuleEnum.VALUER_PANEL, true),

    // ── AUCTIONS ──────────────────────────────────────────────────────────────
    AUCTIONS_VIEW("See auction lots", AppModuleEnum.AUCTIONS),
    AUCTIONS_CREATE("Create an auction lot", AppModuleEnum.AUCTIONS),
    AUCTIONS_UPDATE("Change an auction lot", AppModuleEnum.AUCTIONS),
    /**
     * Putting a lot in the public catalogue.
     *
     * <p>Its own code, like publishing a mortgage product: a published lot is a public notice of a sale, and
     * drafting one is not the same act as announcing it.
     */
    AUCTIONS_PUBLISH("Publish or withdraw an auction lot", AppModuleEnum.AUCTIONS),
    /** Recording what the lot fetched, or that it did not sell. */
    AUCTIONS_RESULT("Record an auction result", AppModuleEnum.AUCTIONS),
    /** Approving or refusing somebody who has asked to bid. */
    AUCTIONS_BIDDERS("Decide bidder registrations", AppModuleEnum.AUCTIONS),

    // ── AUCTIONEERS ───────────────────────────────────────────────────────────
    AUCTIONEERS_VIEW("See the auctioneers", AppModuleEnum.AUCTIONEERS),
    AUCTIONEERS_MANAGE("Add and maintain auctioneers", AppModuleEnum.AUCTIONEERS, true),

    // ── AUDIT ─────────────────────────────────────────────────────────────────
    AUDIT_VIEW("See the audit trail", AppModuleEnum.AUDIT),

    // ── DASHBOARD ─────────────────────────────────────────────────────────────
    DASHBOARD_VIEW("See the dashboard", AppModuleEnum.DASHBOARD),

    // ── BUYER PORTAL ──────────────────────────────────────────────────────────
    /**
     * The one permission a buyer holds. It admits them to their own area; what they see inside it is
     * resolved from their own user id, so there is nothing further to grant and nothing that widens it.
     */
    BUYER_PORTAL_ACCESS("Use the buyer portal", AppModuleEnum.BUYER_PORTAL),

    // ── AGENTS ────────────────────────────────────────────────────────────────
    AGENTS_VIEW("See the agent register", AppModuleEnum.AGENTS),
    /** Deciding an application: approve, reject, suspend, reinstate. Never the agent's own. */
    AGENTS_DECIDE("Decide agent applications", AppModuleEnum.AGENTS, true),
    /** Opening the signature and the executed agreement behind an application. */
    AGENTS_EVIDENCE("Open an agent's signature and agreement", AppModuleEnum.AGENTS, true),

    // ── AGENT_SELF ────────────────────────────────────────────────────────────
    AGENT_SELF_VIEW("See my own agent profile and agreement", AppModuleEnum.AGENT_SELF),
    AGENT_SELF_UPDATE("Update my own agent details", AppModuleEnum.AGENT_SELF),

    // ── VENDORS ───────────────────────────────────────────────────────────────
    VENDORS_VIEW("See the vendor register", AppModuleEnum.VENDORS),
    /** Deciding an application: approve, reject, suspend, reinstate. Never the vendor's own. */
    VENDORS_DECIDE("Decide vendor applications", AppModuleEnum.VENDORS, true),
    /** The taxonomy every vendor and every catalogue item is filed under. */
    VENDOR_CATEGORIES_MANAGE("Add and maintain vendor categories", AppModuleEnum.VENDORS, true),
    /** Approving a catalogue item for publication — the Maker/Checker verb for M10. */
    CATALOGUE_APPROVE("Approve a catalogue item for publication", AppModuleEnum.VENDORS, true),

    // ── VENDOR_SELF ───────────────────────────────────────────────────────────
    VENDOR_SELF_VIEW("See my own business and catalogue", AppModuleEnum.VENDOR_SELF),
    VENDOR_SELF_UPDATE("Update my own business details", AppModuleEnum.VENDOR_SELF),
    CATALOGUE_CREATE("Add something to my catalogue", AppModuleEnum.VENDOR_SELF),
    CATALOGUE_UPDATE("Change something in my catalogue", AppModuleEnum.VENDOR_SELF),
    CATALOGUE_SUBMIT("Send a catalogue item for approval", AppModuleEnum.VENDOR_SELF),
    CATALOGUE_WITHDRAW("Take a published catalogue item down", AppModuleEnum.VENDOR_SELF),
    CATALOGUE_DELETE("Archive a catalogue item", AppModuleEnum.VENDOR_SELF),

    // ── RATINGS ───────────────────────────────────────────────────────────────
    RATINGS_VIEW("See what people said about us", AppModuleEnum.RATINGS),
    RATINGS_REPLY("Reply to a rating about us", AppModuleEnum.RATINGS),

    // ── MODERATION ────────────────────────────────────────────────────────────
    MODERATION_VIEW("See the moderation queue", AppModuleEnum.MODERATION, true),
    MODERATION_DECIDE("Publish or hide a review", AppModuleEnum.MODERATION, true),

    // ── ASSIGNMENT ────────────────────────────────────────────────────────────
    ASSIGNMENT_VIEW("See the routing rules", AppModuleEnum.ASSIGNMENT),
    ASSIGNMENT_MANAGE("Write and change routing rules", AppModuleEnum.ASSIGNMENT),

    // ── CALENDAR ──────────────────────────────────────────────────────────────
    CALENDAR_VIEW("See the diary", AppModuleEnum.CALENDAR),
    CALENDAR_MANAGE("Add and change diary entries", AppModuleEnum.CALENDAR),

    // ── PROPERTY_CONFIG ───────────────────────────────────────────────────────
    PROPERTY_TYPES_VIEW("See the property types", AppModuleEnum.PROPERTY_CONFIG),
    PROPERTY_TYPES_MANAGE("Add and maintain property types", AppModuleEnum.PROPERTY_CONFIG, true),

    // ── PROMOTIONS ────────────────────────────────────────────────────────────
    PROMOTIONS_VIEW("See placements", AppModuleEnum.PROMOTIONS),
    /** A seller asking for one. */
    PROMOTIONS_REQUEST("Ask for a placement on a listing", AppModuleEnum.PROMOTIONS),
    /** Starting or stopping one, and maintaining what is on sale. Platform-only. */
    PROMOTIONS_MANAGE("Start, stop and price placements", AppModuleEnum.PROMOTIONS, true),

    // ── COMMISSIONS ───────────────────────────────────────────────────────────
    COMMISSIONS_VIEW("See what is owed", AppModuleEnum.COMMISSIONS),
    COMMISSIONS_SETTLE("Invoice, mark paid or write off", AppModuleEnum.COMMISSIONS, true),
    SETTLEMENTS_VIEW("See how a sale the bank collected is settled", AppModuleEnum.COMMISSIONS),
    SETTLEMENTS_MAKE("Propose the settlement of a sale: proceeds to the owner, fees to whom they are due",
            AppModuleEnum.COMMISSIONS, true),

    // ── REPORTS ───────────────────────────────────────────────────────────────
    REPORTS_VIEW("Run reports", AppModuleEnum.REPORTS),
    /**
     * Separate from viewing, deliberately. Reading a figure on a screen and walking out with the rows
     * behind it are different acts, and only one of them leaves the building.
     */
    REPORTS_EXPORT("Export a report", AppModuleEnum.REPORTS);

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
