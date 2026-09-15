package com.hodi.common;

/**
 * Single source of truth for numeric codes, string flags and action-type identifiers used
 * system-wide. No magic numbers should appear elsewhere.
 *
 * <p>Runtime configuration <em>keys</em> deliberately do <strong>not</strong> live here: they are
 * declared once in {@code com.hodi.enums.ConfigKey}, which is both the seed source and the lookup
 * constant. Two parallel lists of config keys is exactly the drift the configuration layer
 * (plan section 6) exists to avoid.
 */
public final class AppConstant {

    private AppConstant() {}

    // ── Entity status (status column, INTEGER) ────────────────────────────────
    public static final int STATUS_NEW          = 0;
    public static final int STATUS_ACTIVE       = 1;
    public static final int STATUS_EDITED       = 2;
    public static final int STATUS_DEACTIVATING = 3;
    public static final int STATUS_INACTIVE     = 4;
    public static final int STATUS_DELETED      = 5;

    // ── Status flag (status_flag column, VARCHAR — human-readable companion) ──
    public static final String FLAG_NEW          = "New";
    public static final String FLAG_ACTIVE       = "Active";
    public static final String FLAG_EDITED       = "Edited";
    public static final String FLAG_DEACTIVATING = "Deactivating";
    public static final String FLAG_INACTIVE     = "Inactive";
    public static final String FLAG_DELETED      = "Deleted";

    // ── Actor classes (user_types.actor_class — plan section 2) ───────────────
    // Which population a user type belongs to. Platform staff and buyers both carry no
    // organisation, so this is what tells them apart — never inference from null columns.
    public static final String ACTOR_PLATFORM = "PLATFORM";
    public static final String ACTOR_SELLER   = "SELLER";
    public static final String ACTOR_BUYER    = "BUYER";
    /**
     * A valuer on the platform's panel (M5, plan §3.5).
     *
     * <p>The first actor class whose visibility is neither "my organisation" nor "everything": they see the
     * jobs assigned to them and nothing else. Like a buyer they carry no organisation, and like a buyer the
     * difference from a platform administrator is this column rather than an inference from two nulls.
     */
    public static final String ACTOR_VALUER   = "VALUER";
    /**
     * An independent property agent (M9, BRD FR160–FR161).
     *
     * <p>Unlike a valuer, an agent <em>does</em> carry an organisation — their own, created when the platform
     * approves them. So their visibility needed no new mechanism: a one-person seller organisation is what
     * an independent agent is, and {@code PrincipalFactory} already resolves anybody with a tenant to that
     * tenant's rows.
     *
     * <p>Distinct from the {@code SALES_AGENT} user type, which is a seller organisation's employee. The two
     * are easy to confuse and are not the same person: one works for a seller, the other <em>is</em> the
     * business.
     */
    public static final String ACTOR_AGENT    = "AGENT";
    /**
     * A vendor: a business selling services around a house purchase (M10, BRD FR170).
     *
     * <p>Like an agent, they carry their own organisation and so need no new visibility rule. Unlike an
     * agent, what they publish is a catalogue rather than property, and they never touch a listing.
     */
    public static final String ACTOR_VENDOR   = "VENDOR";

    // ── KYC state (user_profiles.kyc_status — plan §3.3) ─────────────────────
    // NOT_REQUIRED is not the same as APPROVED even though both clear the gate: a platform
    // administrator was never asked, an approved seller was asked and passed, and the difference is
    // the thing Compliance is looking for when they read the row.
    public static final String KYC_NOT_REQUIRED = "NOT_REQUIRED";
    public static final String KYC_PENDING      = "PENDING";
    public static final String KYC_SUBMITTED    = "SUBMITTED";
    public static final String KYC_APPROVED     = "APPROVED";
    public static final String KYC_REJECTED     = "REJECTED";

    // ── Organisation kind (tenants.organisation_kind) ────────────────────────
    // One table, three populations, one visibility rule. The kind is what stops the seller administration
    // screen from offering staff management and partnerships to a one-person agency.
    public static final String ORG_KIND_SELLER = "SELLER";
    public static final String ORG_KIND_AGENT  = "AGENT";
    public static final String ORG_KIND_VENDOR = "VENDOR";

    // ── Seller-tenant lifecycle (tenants.onboarding_status) ──────────────────
    public static final String ONBOARDING_PENDING    = "PENDING";
    public static final String ONBOARDING_ACTIVE     = "ACTIVE";
    public static final String ONBOARDING_SUSPENDED  = "SUSPENDED";
    public static final String ONBOARDING_TERMINATED = "TERMINATED";

    /**
     * Who owns a development, on the wire in both directions.
     *
     * <p>The same two words the response sends back as {@code ownerKind}, so a client can round-trip what
     * it was told. Two constants rather than four literals scattered over a service, a DTO and a client.
     */
    public static final String DEV_OWNER_SELLER = "SELLER";
    public static final String DEV_OWNER_BANK   = "BANK";

    /*
     * The portfolio-scope constants used to sit here — FULL and SELECTED, the two widths a partnership
     * could grant a bank over a seller's listings. Nothing has read them since the partnership module was
     * retired, and a constant nothing reads is a claim about the system that is no longer true.
     */

    // ── Property listings (M2) ───────────────────────────────────────────────
    // Where a listing stands in the world. Separate from `status`, which is the soft-delete lifecycle every
    // row carries: a WITHDRAWN listing is a live row somebody may publish again, an archived one is not.
    public static final String LISTING_DRAFT     = "DRAFT";
    public static final String LISTING_PENDING   = "PENDING";
    public static final String LISTING_LIVE      = "LIVE";
    public static final String LISTING_SOLD      = "SOLD";
    public static final String LISTING_WITHDRAWN = "WITHDRAWN";

    /** What the listing is for. RENT exists in the CHECK so the eventual lettings slice is additive. */
    public static final String LISTING_TYPE_SALE = "SALE";
    public static final String LISTING_TYPE_RENT = "RENT";

    /** Entity type and actions for the approval queue. */
    public static final String APPROVAL_ENTITY_PROPERTY = "PROPERTY";
    public static final String APPROVAL_ACTION_PUBLISH  = "PUBLISH";

    // ── Approval workflow (Maker/Checker — plan §3.2) ────────────────────────
    // The state of a request, and — where a decision has been made — what it was. PENDING never appears as a
    // decision: a request that has not been decided has no decider, which the table's own CHECK enforces.
    public static final String APPROVAL_PENDING   = "PENDING";
    public static final String APPROVAL_APPROVED  = "APPROVED";
    public static final String APPROVAL_REJECTED  = "REJECTED";
    /** Refused, but invited back: the submitter is expected to fix something and submit again. */
    public static final String APPROVAL_SENT_BACK = "SENT_BACK";

    /** Entity types the approval queue knows about. Each needs an ApprovalHandler to be decidable. */
    public static final String APPROVAL_ENTITY_PARTNERSHIP = "PARTNERSHIP";

    /** The decision being asked for. One entity can need several over its life. */
    public static final String APPROVAL_ACTION_ACTIVATE = "ACTIVATE";

    // ── Developments (a project with many units) ─────────────────────────────
    /**
     * Why a development exists. FOR_SALE is the marketplace case; the rest are the tracking case, which is
     * what a bank financing a developer's block needs and what no listing state can express.
     */
    public static final String DEV_PURPOSE_FOR_SALE         = "FOR_SALE";
    public static final String DEV_PURPOSE_FOR_RENT         = "FOR_RENT";
    public static final String DEV_PURPOSE_OWNER_OCCUPIED   = "OWNER_OCCUPIED";
    public static final String DEV_PURPOSE_COMMERCIAL_RENTAL = "COMMERCIAL_RENTAL";
    public static final String DEV_PURPOSE_MIXED            = "MIXED";

    /**
     * The sixth listing state, and only a development has it: tracked, never marketed.
     *
     * <p>A state rather than a boolean beside the other five, because a project cannot be both private and
     * live and there is no reading of the data where it is — two columns could say it was.
     */
    public static final String DEV_STATE_PRIVATE = "PRIVATE";

    /**
     * How far the building has got. Deliberately coarse: it is set on two hundred rows by one person, and a
     * scale fine enough to be accurate is a scale nobody keeps up to date. The fine-grained stage lives on
     * the phase, where the photographs are.
     *
     * <p>Not a listing state. "Off-plan" is PLANNED or UNDER_CONSTRUCTION, derived rather than stored as a
     * flag that can disagree with the status beside it.
     */
    public static final String BUILD_PLANNED            = "PLANNED";
    public static final String BUILD_UNDER_CONSTRUCTION = "UNDER_CONSTRUCTION";
    public static final String BUILD_COMPLETE           = "COMPLETE";
    public static final String BUILD_HANDED_OVER        = "HANDED_OVER";

    /**
     * Which rule produced a development's percentage. Recorded so a screen can say how, because a derived
     * figure that cannot explain itself gets read as somebody's opinion.
     */
    public static final String PERCENT_BASIS_WEIGHT = "WEIGHT";
    public static final String PERCENT_BASIS_BUDGET = "BUDGET";
    public static final String PERCENT_BASIS_UNITS  = "UNITS";
    public static final String PERCENT_BASIS_EQUAL  = "EQUAL";
    /** No phases at all: the figure was typed, and the basis says so. */
    public static final String PERCENT_BASIS_STATED = "STATED";

    /**
     * Where one unit stands. HELD and RESERVED are both holds and differ in commitment, NOT_FOR_SALE is a
     * unit that was never inventory, RETAINED is one the developer kept — the last two count in the total
     * and in none of the available/reserved/sold figures, which is why those three do not sum to it.
     */
    /** Which kind of row a property is. See Property.listingKind. */
    public static final String LISTING_KIND_HOUSE    = "HOUSE";
    public static final String LISTING_KIND_TYPOLOGY = "TYPOLOGY";
    public static final String LISTING_KIND_UNIT     = "UNIT";

    public static final String UNIT_AVAILABLE    = "AVAILABLE";
    public static final String UNIT_HELD         = "HELD";
    public static final String UNIT_RESERVED     = "RESERVED";
    public static final String UNIT_SOLD         = "SOLD";
    public static final String UNIT_NOT_FOR_SALE = "NOT_FOR_SALE";
    public static final String UNIT_RETAINED     = "RETAINED";

    /** What an owner has granted a collaborating organisation on their development. */
    public static final String COLLAB_PROGRESS_WRITE = "PROGRESS_WRITE";
    public static final String COLLAB_UNITS_WRITE    = "UNITS_WRITE";
    public static final String COLLAB_FULL           = "FULL";

    /**
     * A cost line's kind. COMMITTED is a contract or certificate signed — money promised; SPENT is money out.
     * Both are lines in the same ledger so a phase can say how much of its budget is spoken for as well as
     * how much has actually gone.
     */
    public static final String COST_COMMITTED = "COMMITTED";
    public static final String COST_SPENT     = "SPENT";

    /** The approval queue's name for a development. Needs a handler to be decidable. */
    public static final String APPROVAL_ENTITY_DEVELOPMENT = "DEVELOPMENT";

    // ── Bookings ─────────────────────────────────────────────────────────────

    /*
     * Where a booking stands.
     *
     * RESERVED and AGREED are the two that hold the unit, and the partial unique index keys on exactly those
     * two. LAPSED and CANCELLED are kept apart because they are different facts — a clock and a decision — and
     * one merged "closed" state would make "how many bookings do we lose to expiry" unanswerable, which is the
     * first question anybody asks about a hold policy.
     */
    public static final String BOOKING_RESERVED  = "RESERVED";
    public static final String BOOKING_AGREED    = "AGREED";
    public static final String BOOKING_COMPLETED = "COMPLETED";
    public static final String BOOKING_CANCELLED = "CANCELLED";
    public static final String BOOKING_LAPSED    = "LAPSED";

    public static final String PLAN_LUMP_SUM    = "LUMP_SUM";
    public static final String PLAN_INSTALMENTS = "INSTALMENTS";

    /*
     * Where a payment came from. MANUAL is the cheque and the transfer a bank actually reconciles, and it is
     * not a placeholder for a gateway — it stays in production permanently.
     */
    public static final String PAY_MANUAL  = "MANUAL";
    public static final String PAY_GATEWAY = "GATEWAY";

    /*
     * Who a progress post was written for.
     *
     * PUBLIC is a blog or newsletter post — words and photographs, written to interest somebody in a project.
     * STAKEHOLDERS is a detailed update — the percentage, the stage, the phase — for the people running and
     * financing the build. Different audiences, different tone, and different consequences if they leak.
     */
    public static final String AUDIENCE_PUBLIC       = "PUBLIC";
    public static final String AUDIENCE_STAKEHOLDERS = "STAKEHOLDERS";

    /*
     * Where an inbound Pesi notification stands.
     *
     * UNMAPPED is not a failure — it is a payment that arrived and is waiting for a person, which is the
     * normal outcome for a mistyped reference or a walk-in payer. IGNORED is a person's decision that it is
     * not ours; keeping it apart from UNMAPPED stops a dismissed row reappearing on the queue every day.
     */
    public static final String STATEMENT_MAPPED   = "MAPPED";
    public static final String STATEMENT_UNMAPPED = "UNMAPPED";
    public static final String STATEMENT_IGNORED  = "IGNORED";

    /*
     * Where a payment stands. Received or voided, and nothing in between.
     *
     * VOIDED is 4, the same number as STATUS_INACTIVE, on purpose: every `status <> 5` guard in the codebase
     * keeps meaning what it did, and a reader comparing a payment row to any other row is in one numbering.
     */
    public static final int PAYMENT_RECEIVED = STATUS_ACTIVE;
    public static final int PAYMENT_VOIDED   = STATUS_INACTIVE;

    /*
     * What a payment channel is, and therefore how it behaves. The category decides, never a catalogue id.
     * CASH and CHEQUE are recorded by staff and carry no account; STK_PUSH prompts a phone; TRANSFER is money
     * going out; VALIDATE is an inbound credit reconciled by reference.
     */
    public static final String CHANNEL_CASH     = "CASH";
    public static final String CHANNEL_CHEQUE   = "CHEQUE";
    public static final String CHANNEL_STK_PUSH = "STK_PUSH";
    public static final String CHANNEL_TRANSFER = "TRANSFER";
    public static final String CHANNEL_VALIDATE = "VALIDATE";

    public static final String PAY_CASH          = "CASH";
    public static final String PAY_CHEQUE        = "CHEQUE";
    public static final String PAY_BANK_TRANSFER = "BANK_TRANSFER";
    public static final String PAY_MOBILE_MONEY  = "MOBILE_MONEY";
    public static final String PAY_CARD          = "CARD";
    public static final String PAY_OTHER         = "OTHER";

    // ── Media assets (photographs of something that is not a listing) ────────
    // PROPERTY is deliberately absent: a listing's gallery is property_media, and one table per question
    // means nobody has to work out which to read.
    public static final String MEDIA_OWNER_DEVELOPMENT       = "DEVELOPMENT";
    public static final String MEDIA_OWNER_DEVELOPMENT_PHASE = "DEVELOPMENT_PHASE";
    public static final String MEDIA_OWNER_UNIT_TYPE         = "UNIT_TYPE";
    public static final String MEDIA_OWNER_DEVELOPMENT_UNIT  = "DEVELOPMENT_UNIT";
    public static final String MEDIA_OWNER_PROGRESS_UPDATE   = "PROGRESS_UPDATE";

    public static final String MEDIA_KIND_PHOTO      = "PHOTO";
    public static final String MEDIA_KIND_FLOOR_PLAN = "FLOOR_PLAN";
    public static final String MEDIA_KIND_SITE_PLAN  = "SITE_PLAN";
    public static final String MEDIA_KIND_BROCHURE   = "BROCHURE";
    public static final String MEDIA_KIND_DRONE      = "DRONE";

    // ── Consent (plan §3.8, BRD FR004–FR005) ────────────────────────────────
    // Channels somebody can be reached on, and the three reasons they might be. The purposes are not a
    // taxonomy of messages — they are the granularity at which a person is asked to agree, which is why
    // there are three of them and not thirty.
    public static final String CONSENT_CHANNEL_EMAIL = "EMAIL";
    public static final String CONSENT_CHANNEL_SMS   = "SMS";

    /**
     * Messages that carry out something the person asked for: a reset link, a viewing confirmation, a
     * receipt. Not opt-out-able, and the consent table's own CHECK refuses a row that says otherwise.
     */
    public static final String CONSENT_TRANSACTIONAL   = "TRANSACTIONAL";
    /** New listings matching a saved search. The only purpose the alert dispatcher will send under. */
    public static final String CONSENT_PROPERTY_ALERTS = "PROPERTY_ALERTS";
    public static final String CONSENT_PROMOTIONAL     = "PROMOTIONAL";

    /** How an answer was obtained. Evidence about the evidence. */
    public static final String CONSENT_SOURCE_REGISTRATION = "REGISTRATION";
    public static final String CONSENT_SOURCE_PREFERENCES  = "PREFERENCES";

    // ── Saved searches (M2, BRD FR022–FR024) ─────────────────────────────────
    /** As soon as the dispatcher next polls — a poll a buyer cannot tell apart from a push. */
    public static final String ALERT_INSTANT = "INSTANT";
    public static final String ALERT_DAILY   = "DAILY";
    public static final String ALERT_WEEKLY  = "WEEKLY";

    /** Why a due alert sent nothing. Recorded on the row so the buyer's own screen can explain itself. */
    public static final String ALERT_OUTCOME_SENT       = "SENT";
    public static final String ALERT_OUTCOME_NO_MATCHES = "NO_MATCHES";
    /** Matches found, nowhere to send them: every channel is switched off in the consent store. */
    public static final String ALERT_OUTCOME_NO_CONSENT = "NO_CONSENT";
    public static final String ALERT_OUTCOME_FAILED     = "FAILED";

    // ── Mortgage products (M3, BRD FR025–FR030) ──────────────────────────────
    public static final String PRODUCT_MORTGAGE      = "MORTGAGE";
    public static final String PRODUCT_CONSTRUCTION  = "CONSTRUCTION";
    public static final String PRODUCT_PLOT_PURCHASE = "PLOT_PURCHASE";
    public static final String PRODUCT_EQUITY_RELEASE = "EQUITY_RELEASE";
    public static final String PRODUCT_REFINANCE     = "REFINANCE";

    public static final String RATE_FIXED    = "FIXED";
    public static final String RATE_VARIABLE = "VARIABLE";
    public static final String RATE_REDUCING = "REDUCING";

    // ── Affordability (M3, BRD FR031–FR034) ──────────────────────────────────
    // Three answers, not two. A household a shilling past the ceiling is not in the same position as one at
    // twice it, and telling them the same thing is how a calculator loses the person it was built for.
    public static final String AFFORDABILITY_ELIGIBLE     = "ELIGIBLE";
    public static final String AFFORDABILITY_MARGINAL     = "MARGINAL";
    public static final String AFFORDABILITY_NOT_ELIGIBLE = "NOT_ELIGIBLE";

    /** The assessors. MOCK is this platform's own documented rules; OCP is the credit microservice. */
    public static final String PROVIDER_MOCK = "MOCK";
    public static final String PROVIDER_OCP  = "OCP";

    // ── Leads (M4, BRD FR035–FR048) ──────────────────────────────────────────
    /** An enquiry's life: open, answered, done. Not a workflow — a conversation with a lid. */
    public static final String ENQUIRY_OPEN     = "OPEN";
    public static final String ENQUIRY_ANSWERED = "ANSWERED";
    public static final String ENQUIRY_CLOSED   = "CLOSED";

    /** Which side of the conversation said something. Stored, never inferred from the author's profile. */
    public static final String SIDE_BUYER    = "BUYER";
    public static final String SIDE_SELLER   = "SELLER";
    public static final String SIDE_PLATFORM = "PLATFORM";

    public static final String VISIT_REQUESTED = "REQUESTED";
    public static final String VISIT_CONFIRMED = "CONFIRMED";
    public static final String VISIT_DECLINED  = "DECLINED";
    public static final String VISIT_COMPLETED = "COMPLETED";
    /** Called off by the buyer. Distinct from DECLINED, which is the seller's answer. */
    public static final String VISIT_CANCELLED = "CANCELLED";

    public static final String PURCHASE_SUBMITTED    = "SUBMITTED";
    public static final String PURCHASE_UNDER_REVIEW = "UNDER_REVIEW";
    public static final String PURCHASE_ACCEPTED     = "ACCEPTED";
    public static final String PURCHASE_DECLINED     = "DECLINED";
    public static final String PURCHASE_WITHDRAWN    = "WITHDRAWN";

    public static final String FINANCING_CASH          = "CASH";
    public static final String FINANCING_MORTGAGE      = "MORTGAGE";
    public static final String FINANCING_PART_EXCHANGE = "PART_EXCHANGE";

    // ── KYC and the document vault (M8 slice 1, plan §3.3 and §3.9) ──────────
    /**
     * The kinds of seller the platform onboards, and the key into the requirement catalogue.
     *
     * <p>One list, used by the onboarding form and by {@code kyc_requirement_configs} alike — a requirement
     * list for a type nobody can choose is dead data, and a type with no requirement list is a seller who
     * can never clear KYC.
     */
    public static final String SELLER_INDIVIDUAL = "INDIVIDUAL";
    public static final String SELLER_COMPANY    = "COMPANY";
    public static final String SELLER_SACCO      = "SACCO";
    public static final String SELLER_DEVELOPER  = "DEVELOPER";
    public static final String SELLER_AGENCY     = "AGENCY";
    public static final String SELLER_GOVERNMENT = "GOVERNMENT";

    /** Where a pack stands. MORE_INFO is a refusal that invites the seller back, not a rejection. */
    public static final String KYC_SUB_DRAFT     = "DRAFT";
    public static final String KYC_SUB_SUBMITTED = "SUBMITTED";
    public static final String KYC_SUB_APPROVED  = "APPROVED";
    public static final String KYC_SUB_REJECTED  = "REJECTED";
    public static final String KYC_SUB_MORE_INFO = "MORE_INFO";

    /** Compliance's verdict on one document within a pack. */
    public static final String VERDICT_PENDING  = "PENDING";
    public static final String VERDICT_ACCEPTED = "ACCEPTED";
    public static final String VERDICT_REJECTED = "REJECTED";

    /** What the storage layer declared when it wrote an object. Recorded per document, not per deployment. */
    public static final String ENCRYPTION_NONE    = "NONE";
    public static final String ENCRYPTION_SSE_S3  = "SSE-S3";
    public static final String ENCRYPTION_SSE_KMS = "SSE-KMS";

    // ── Valuation (M5, plan §3.5) ────────────────────────────────────────────
    public static final String VALUATION_REQUESTED   = "REQUESTED";
    public static final String VALUATION_ASSIGNED    = "ASSIGNED";
    /** The valuer said no. The job returns to the queue for somebody else. */
    public static final String VALUATION_DECLINED    = "DECLINED";
    public static final String VALUATION_IN_PROGRESS = "IN_PROGRESS";
    public static final String VALUATION_SUBMITTED   = "SUBMITTED";
    public static final String VALUATION_COMPLETED   = "COMPLETED";
    public static final String VALUATION_CANCELLED   = "CANCELLED";

    public static final String VALUATION_FOR_SALE      = "SALE";
    public static final String VALUATION_FOR_MORTGAGE  = "MORTGAGE";
    public static final String VALUATION_FOR_INSURANCE = "INSURANCE";
    public static final String VALUATION_FOR_PROBATE   = "PROBATE";
    public static final String VALUATION_FOR_AUCTION   = "AUCTION";

    /** How a valuer was picked. A manual override of the panel is the thing somebody asks about later. */
    public static final String ASSIGN_ROUND_ROBIN = "ROUND_ROBIN";
    public static final String ASSIGN_MANUAL      = "MANUAL";

    // ── Auction (M6, BRD UC006) ──────────────────────────────────────────────
    // A lot's life. SCHEDULED is the only state a member of the public ever sees — and it is a state on a
    // table the marketplace does not read, which is what UC006's isolation actually means here.
    public static final String LOT_DRAFT      = "DRAFT";
    public static final String LOT_SCHEDULED  = "SCHEDULED";
    public static final String LOT_SOLD       = "SOLD";
    /** Went to auction and did not meet its reserve. */
    public static final String LOT_UNSOLD     = "UNSOLD";
    public static final String LOT_WITHDRAWN  = "WITHDRAWN";
    public static final String LOT_POSTPONED  = "POSTPONED";

    public static final String BIDDER_REGISTERED = "REGISTERED";
    public static final String BIDDER_APPROVED   = "APPROVED";
    public static final String BIDDER_REJECTED   = "REJECTED";
    public static final String BIDDER_WITHDRAWN  = "WITHDRAWN";

    // ── Session client classes (plan section 5 — one idle window per class) ───
    public static final String SESSION_CLASS_ADMIN = "ADMIN";
    public static final String SESSION_CLASS_BUYER = "BUYER";

    // ── Action types (audit_logs.action) ─────────────────────────────────────
    public static final String ACTION_CREATE     = "CREATE";
    public static final String ACTION_UPDATE     = "UPDATE";
    public static final String ACTION_DEACTIVATE = "DEACTIVATE";
    public static final String ACTION_ACTIVATE   = "ACTIVATE";
    public static final String ACTION_DELETE     = "DELETE";
    public static final String ACTION_SUSPEND    = "SUSPEND";
    public static final String ACTION_REINSTATE  = "REINSTATE";
    public static final String ACTION_TERMINATE  = "TERMINATE";
    public static final String ACTION_REQUEST    = "REQUEST";
    public static final String ACTION_APPROVE    = "APPROVE";
    public static final String ACTION_REVOKE     = "REVOKE";
    public static final String ACTION_CLONE      = "CLONE";

    // ── Audit operations ─────────────────────────────────────────────────────
    public static final String AUDIT_LOGIN            = "LOGIN";
    public static final String AUDIT_LOGIN_FAILED     = "LOGIN_FAILED";
    public static final String AUDIT_LOGOUT           = "LOGOUT";
    public static final String AUDIT_PROFILE_SWITCH   = "PROFILE_SWITCH";
    public static final String AUDIT_APPROVAL_SUBMIT  = "APPROVAL_SUBMITTED";
    public static final String AUDIT_APPROVAL_DECIDE  = "APPROVAL_DECIDED";
    /** A refresh token presented twice: either a stale tab or a stolen session. */
    public static final String AUDIT_TOKEN_REUSE      = "TOKEN_REUSE_DETECTED";
    public static final String AUDIT_REFRESH          = "REFRESH";
    public static final String AUDIT_PASSWORD_CHANGE  = "PASSWORD_CHANGE";
    public static final String AUDIT_PASSWORD_RESET   = "PASSWORD_RESET";
    public static final String AUDIT_TOTP_SETUP       = "TOTP_SETUP";
    public static final String AUDIT_TOTP_DISABLE     = "TOTP_DISABLE";
    public static final String AUDIT_BUYER_REGISTER   = "BUYER_REGISTER";
    public static final String AUDIT_BUYER_VERIFY     = "BUYER_VERIFY";
    public static final String AUDIT_CONFIG_UPDATE    = "CONFIG_UPDATE";
    /** A person changed what they may be contacted about. The proof-of-consent trail's second copy. */
    public static final String AUDIT_CONSENT_UPDATE   = "CONSENT_UPDATE";
    /** A saved search was delivered, or was not, and why. */
    public static final String AUDIT_ALERT_RUN        = "SEARCH_ALERT_RUN";
    /** A product went in front of the public, or came back off it. */
    public static final String AUDIT_PRODUCT_PUBLISH  = "PRODUCT_PUBLISH";
    public static final String AUDIT_PRODUCT_WITHDRAW = "PRODUCT_WITHDRAW";
    /** A buyer opened a conversation with a seller. */
    public static final String AUDIT_ENQUIRY_RAISED   = "ENQUIRY_RAISED";
    public static final String AUDIT_VISIT_REQUESTED  = "VISIT_REQUESTED";
    public static final String AUDIT_VISIT_DECIDED    = "VISIT_DECIDED";
    public static final String AUDIT_OFFER_SUBMITTED  = "OFFER_SUBMITTED";
    public static final String AUDIT_OFFER_DECIDED    = "OFFER_DECIDED";
    /** Somebody read a document out of the vault. Written on every fetch, never sampled. */
    public static final String AUDIT_DOCUMENT_READ    = "DOCUMENT_READ";
    public static final String AUDIT_DOCUMENT_UPLOAD  = "DOCUMENT_UPLOAD";
    public static final String AUDIT_KYC_SUBMITTED    = "KYC_SUBMITTED";
    public static final String AUDIT_KYC_DECIDED      = "KYC_DECIDED";
    public static final String AUDIT_VALUATION_ASSIGN = "VALUATION_ASSIGNED";
    public static final String AUDIT_VALUATION_REPORT = "VALUATION_REPORTED";
    public static final String AUDIT_LOT_PUBLISHED    = "LOT_PUBLISHED";
    public static final String AUDIT_LOT_RESULT       = "LOT_RESULT";
    public static final String AUDIT_BIDDER_DECIDED   = "BIDDER_DECIDED";
    public static final String AUDIT_SESSION_REVOKED  = "SESSION_REVOKED";
    public static final String AUDIT_AGENT_REGISTER   = "AGENT_REGISTERED";
    public static final String AUDIT_AGENT_DECIDED    = "AGENT_DECIDED";
    /** Opening the signature or the agreement behind an application. Read events, recorded like vault ones. */
    public static final String AUDIT_SIGNATURE_READ   = "SIGNATURE_READ";
    public static final String AUDIT_VENDOR_REGISTER  = "VENDOR_REGISTERED";
    public static final String AUDIT_VENDOR_DECIDED   = "VENDOR_DECIDED";
    public static final String AUDIT_RATING_MODERATED = "RATING_MODERATED";
    /** Money arrived and was receipted, or a receipt was voided. The most disputed events in the product. */
    public static final String AUDIT_PAYMENT_RECEIVED = "PAYMENT_RECEIVED";
    public static final String AUDIT_PAYMENT_VOIDED   = "PAYMENT_VOIDED";
    /** A cost line or a facility drawdown written or voided on a development. */
    public static final String AUDIT_COST_RECORDED    = "COST_RECORDED";
    public static final String AUDIT_COST_VOIDED      = "COST_VOIDED";
    public static final String AUDIT_DRAWDOWN_RECORDED = "DRAWDOWN_RECORDED";
    public static final String AUDIT_DRAWDOWN_VOIDED  = "DRAWDOWN_VOIDED";

    // ── Audit outcomes ───────────────────────────────────────────────────────
    public static final String OUTCOME_SUCCESS      = "SUCCESS";
    public static final String OUTCOME_FAILED       = "FAILED";
    public static final String OUTCOME_UNAUTHORIZED = "UNAUTHORIZED";

    // ── System / fallback usernames ──────────────────────────────────────────
    public static final String USERNAME_SYSTEM = "system";
    public static final String USERNAME_SEEDER = "seeder";

    // ── MDC keys (ActionIdFilter, ActivityLogger) ────────────────────────────
    public static final String MDC_ACTION_ID = "actionId";
    public static final String MDC_USERNAME  = "username";
    public static final String MDC_TENANT    = "tenant";
    public static final String MDC_REMOTE_IP = "remoteIp";

    /**
     * Whether a row is still in force.
     *
     * <p>Not {@code status == STATUS_ACTIVE}. Every update in this codebase stamps
     * {@code STATUS_EDITED} as a "changed since activation" marker, so treating only
     * {@code STATUS_ACTIVE} as live would mean any row a person edits immediately stops counting —
     * the opposite of what editing it was for. Only {@code STATUS_INACTIVE} (deliberately switched
     * off) and {@code STATUS_DELETED} (soft-archived) take a row out of force.
     */
    public static boolean isLive(Integer status) {
        return status != null && status != STATUS_INACTIVE && status != STATUS_DELETED;
    }

    /** Map a numeric status to its human-readable flag, or null for unknown values. */
    public static String flagFor(int status) {
        return switch (status) {
            case STATUS_NEW          -> FLAG_NEW;
            case STATUS_ACTIVE       -> FLAG_ACTIVE;
            case STATUS_EDITED       -> FLAG_EDITED;
            case STATUS_DEACTIVATING -> FLAG_DEACTIVATING;
            case STATUS_INACTIVE     -> FLAG_INACTIVE;
            case STATUS_DELETED      -> FLAG_DELETED;
            default                  -> null;
        };
    }
}
