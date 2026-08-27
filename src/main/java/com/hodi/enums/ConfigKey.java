package com.hodi.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Catalogue of runtime configuration keys. Seeded into {@code configurations} — one row per constant — and
 * read live (Redis-cached) so operators tune behaviour without a redeploy. This enum is the single source of
 * truth for key names; they are deliberately not duplicated in {@code AppConstant}.
 *
 * <p><strong>Global by default, tenant override by exception</strong> (plan section 6).
 * {@code overridable = true} marks the small set a seller organisation may shadow with a row in
 * {@code tenant_configurations} — integration credentials they legitimately own. Everything else is platform
 * policy: an organisation weakening its own session window, lockout threshold or verification requirement
 * would defeat the control, so {@code ConfigurationService} rejects the override attempt rather than silently
 * ignoring it.
 *
 * <p>{@code secret = true} values are encrypted at rest with {@code EncryptionUtil} and masked in API
 * responses and logs.
 */
@Getter
@RequiredArgsConstructor
public enum ConfigKey {

    // ── AUTH / SESSION (never overridable — platform security policy) ─────────
    // One idle window per client class, per plan section 5. Each drives both the access-token TTL and
    // the refresh-token TTL (= window + grace), so a session dies after this much inactivity.
    SESSION_TIMEOUT_MINUTES_ADMIN(
            "security.session.timeout.minutes.admin", "INTEGER", "AUTH", "20",
            "Back-office session timeout (minutes)",
            "Idle window for platform, seller and lender back-office sessions.", false, false),
    SESSION_TIMEOUT_MINUTES_BUYER(
            "security.session.timeout.minutes.buyer", "INTEGER", "AUTH", "1440",
            "Buyer session timeout (minutes)",
            "Idle window for buyer sessions on the marketplace. Long on purpose: somebody comparing "
                    + "properties over an evening should not be signed out between tabs.", false, false),
    SESSION_REFRESH_GRACE_SECONDS(
            "security.session.refresh.grace.seconds", "INTEGER", "AUTH", "60",
            "Refresh grace (seconds)",
            "Added to the refresh-token TTL so a refresh fired at the window boundary still succeeds. "
                    + "Keep small — the client still logs out at exactly the window.", false, false),
    AUTH_TOTP_ENABLED(
            "auth.totp.enabled", "BOOLEAN", "AUTH", "true",
            "TOTP 2FA available",
            "Master switch for TOTP-based two-factor authentication.", false, false),
    AUTH_TOTP_REQUIRED(
            "auth.totp.required", "BOOLEAN", "AUTH", "false",
            "TOTP required for staff",
            "When true, staff without TOTP enrolled are forced into setup before login completes. "
                    + "Never applied to buyers.", false, false),
    AUTH_PASSWORD_MIN_LENGTH(
            "auth.password.min.length", "INTEGER", "AUTH", "10",
            "Minimum password length", "Minimum length for new passwords.", false, false),
    AUTH_PASSWORD_REQUIRE_SYMBOL(
            "auth.password.require.symbol", "BOOLEAN", "AUTH", "true",
            "Password requires a symbol", "New passwords must contain at least one symbol.", false, false),
    AUTH_PASSWORD_REQUIRE_NUMBER(
            "auth.password.require.number", "BOOLEAN", "AUTH", "true",
            "Password requires a number", "New passwords must contain at least one digit.", false, false),
    AUTH_PASSWORD_REQUIRE_UPPER(
            "auth.password.require.upper", "BOOLEAN", "AUTH", "true",
            "Password requires uppercase",
            "New passwords must contain at least one uppercase letter.", false, false),
    AUTH_PASSWORD_HISTORY_COUNT(
            "auth.password.history.count", "INTEGER", "AUTH", "5",
            "Password history depth", "Number of previous passwords blocked from reuse.", false, false),
    AUTH_PASSWORD_EXPIRY_DAYS(
            "auth.password.expiry.days", "INTEGER", "AUTH", "90",
            "Password expiry (days)",
            "Days before a password must be changed. 0 disables expiry.", false, false),
    AUTH_PASSWORD_RESET_TTL_HOURS(
            "auth.password.reset.ttl.hours", "INTEGER", "AUTH", "2",
            "Password-reset link TTL (hours)", "Validity window of a password-reset link.", false, false),
    AUTH_LOGIN_MAX_ATTEMPTS(
            "auth.login.max.attempts", "INTEGER", "AUTH", "5",
            "Max failed login attempts", "Failed attempts before the account locks.", false, false),
    AUTH_LOGIN_LOCKOUT_MINUTES(
            "auth.login.lockout.minutes", "INTEGER", "AUTH", "15",
            "Lockout duration (minutes)",
            "How long an account stays locked after too many failed attempts.", false, false),
    AUTH_OTP_TTL_MINUTES(
            "auth.otp.ttl.minutes", "INTEGER", "AUTH", "10",
            "One-time code TTL (minutes)",
            "How long a login or verification code stays valid.", false, false),
    AUTH_OTP_MAX_ATTEMPTS(
            "auth.otp.max.attempts", "INTEGER", "AUTH", "5",
            "Max one-time code attempts",
            "Wrong-code attempts allowed before the challenge is abandoned and a new one is required.",
            false, false),

    // ── BUYER REGISTRATION (never overridable — one marketplace, one policy) ──
    BUYER_SELF_REGISTRATION_ENABLED(
            "buyer.self.registration.enabled", "BOOLEAN", "BUYER", "true",
            "Buyers may register themselves",
            "When false, buyer accounts can only be created by staff.", false, false),
    BUYER_EMAIL_VERIFICATION_REQUIRED(
            "buyer.email.verification.required", "BOOLEAN", "BUYER", "true",
            "Buyers must verify their email",
            "A buyer cannot sign in until the emailed code is confirmed.", false, false),
    BUYER_PHONE_VERIFICATION_REQUIRED(
            "buyer.phone.verification.required", "BOOLEAN", "BUYER", "false",
            "Buyers must verify their phone",
            "A buyer cannot sign in until the texted code is confirmed. Off by default: an enquiry is "
                    + "cheap to make and an SMS is not.", false, false),

    // ── THEME (never overridable in this phase — plan section 7) ──────────────
    // One marketplace, one brand. Per-seller theming only becomes meaningful alongside vanity seller
    // hosts; when that lands, flipping these to overridable is the whole change.
    THEME_PRIMARY(
            "theme.primary", "STRING", "THEME", "#0B2545",
            "Primary colour", "Ink surfaces — sidebar, auth panel, body text.", false, true),
    THEME_ACCENT(
            "theme.accent", "STRING", "THEME", "#1B7F79",
            "Accent colour", "Primary actions and links.", false, true),
    THEME_ACCENT_LIGHT(
            "theme.accent.light", "STRING", "THEME", "#4FB3A9",
            "Light accent", "Gradient terminus and headline accents.", false, true),
    THEME_INK(
            "theme.ink", "STRING", "THEME", "#0B2545",
            "Ink base",
            "The configured ink is --ink-800; 850 and 900 are darker derivations computed at runtime. "
                    + "CSS defaults that disagree cause a colour shift between first paint and hydration.",
            false, true),
    THEME_DARK_ENABLED(
            "theme.dark.enabled", "BOOLEAN", "THEME", "true",
            "Dark mode available", "Offer the light/dark toggle.", false, true),
    THEME_LOGO_URL(
            "theme.logo.url", "STRING", "THEME", "",
            "Logo URL", "Overrides the inline SVG lockup when set.", false, true),
    THEME_LOGO_MARK_URL(
            "theme.logo.mark.url", "STRING", "THEME", "",
            "Logo mark URL", "Overrides the inline SVG mark when set.", false, true),
    THEME_FAVICON_URL(
            // Deliberately NOT overridable, unlike the rest of the palette: the favicon is the browser tab
            // of one marketplace on one host, so a per-seller value would be a seller renaming the shared
            // site's tab rather than branding their own workspace.
            "theme.favicon.url", "STRING", "THEME", "",
            "Favicon URL", "Browser tab icon.", false, false),
    UI_FIELD_HINTS(
            "ui.field.hints", "BOOLEAN", "THEME", "true",
            "Show field hints", "Render the helper text under form fields.", false, true),

    // ── GENERAL ───────────────────────────────────────────────────────────────
    COMPANY_NAME(
            "company.name", "STRING", "GENERAL", "Hodi Market Place",
            // The four COMPANY_* keys stay platform-only, unlike axis where they are per-tenant. Axis's
            // tenants are separate businesses with their own storefronts; Hodi's sellers work inside one
            // marketplace, and their own contact details already live on the tenant row. A seller overriding
            // "platform name" would be renaming the marketplace in its own browser title.
            "Platform name", "Shown in the browser title, emails and the brand lockup.", false, false),
    COMPANY_EMAIL(
            "company.email", "STRING", "GENERAL", "hello@hodi.local",
            "Contact email", "Public contact address.", false, false),
    COMPANY_PHONE(
            "company.phone", "STRING", "GENERAL", "",
            "Contact phone", "Public contact number.", false, false),
    COMPANY_ADDRESS(
            "company.address", "STRING", "GENERAL", "",
            "Contact address", "Public postal address.", false, false),
    PUBLIC_URL(
            "platform.public.url", "STRING", "GENERAL", "http://localhost:3020",
            "Public base URL",
            "Used to build links in emails and texts. Getting this wrong makes every password-reset "
                    + "link point at the wrong host.", false, false),
    MONEY_DECIMAL_PLACES(
            "money.decimal.places", "INTEGER", "GENERAL", "2",
            "Money decimal places", "Rounding scale for displayed and stored money.", false, false),

    // ── NOTIFY (overridable — a seller may send under their own identity) ─────
    NOTIFY_BASE_URL(
            "notify.base.url", "STRING", "NOTIFY", "",
            "Notify base URL", "SMS/email gateway endpoint.", false, true),
    NOTIFY_API_KEY(
            "notify.api.key", "STRING", "NOTIFY", "",
            "Notify API key", "Gateway credential. Encrypted at rest and masked in responses.",
            true, true),
    NOTIFY_SMS_SENDER_ID(
            "notify.sms.sender.id", "STRING", "NOTIFY", "HODI",
            "SMS sender id",
            "The name a text appears to come from. Overridable because a seller may legitimately want "
                    + "their own, billed to their own gateway account.", false, true),
    NOTIFY_EMAIL_DOMAIN(
            "notify.email.domain", "STRING", "NOTIFY", "hodi.local",
            "Outbound email domain",
            "Only the domain is configured; the local part is derived from who is sending, so a seller "
                    + "onboarded a minute ago already sends under their own name. Every derived address "
                    + "sits on this one domain, so its SPF and DKIM records must authorise the gateway.",
            false, true),
    NOTIFY_SMS_ENABLED(
            "notify.sms.enabled", "BOOLEAN", "NOTIFY", "false",
            "SMS enabled", "Master switch for outbound SMS.", false, true),
    NOTIFY_EMAIL_ENABLED(
            "notify.email.enabled", "BOOLEAN", "NOTIFY", "false",
            "Email enabled", "Master switch for outbound email.", false, true),

    // ── STORAGE (never overridable — platform infrastructure) ────────────────
    STORAGE_S3_BUCKET(
            "storage.s3.bucket", "STRING", "STORAGE", "",
            "S3 bucket",
            "Blank means files are written to the local directory instead. This key is what decides "
                    + "which, which is why stored rows keep a storage key rather than a URL.", false, false),
    STORAGE_S3_ENDPOINT(
            "storage.s3.endpoint", "STRING", "STORAGE", "",
            "S3 endpoint", "Override for a non-AWS S3-compatible provider.", false, false),
    STORAGE_S3_REGION(
            "storage.s3.region", "STRING", "STORAGE", "us-east-1",
            "S3 region", "Region the bucket lives in.", false, false),
    /**
     * The browser key for Google Maps.
     *
     * <p><strong>Not marked secret</strong>, and that is not an oversight: a Maps browser key is meant to be
     * in the page — it is protected by an HTTP-referrer restriction on Google's side, not by being hidden.
     * Masking it here would only stop the client that needs it from reading it, while doing nothing about the
     * fact that anybody can read it out of a rendered page.
     *
     * <p>Empty means no embedded map. The listing page then shows the location and a link that opens Google
     * Maps, which needs no key at all — so an unconfigured environment degrades to something useful rather
     * than to a grey box asking for a key.
     */
    MAPS_GOOGLE_KEY(
            "maps.google.key", "STRING", "GENERAL", "",
            "Google Maps key",
            "Browser key for the embedded map on a listing. Empty shows a link to Google Maps instead.",
            false, false),
    // ── AFFORDABILITY (M3 — plan §3.11) ──────────────────────────────────────
    /**
     * Which assessor answers "what can this household carry".
     *
     * <p>{@code MOCK} until the OCP microservice contract exists. The switch is a configuration row rather
     * than a build-time choice so the day it does exist, going live is a new class and an edit here — with
     * the way back being the same edit, which is what makes trying it a reversible decision.
     */
    AFFORDABILITY_PROVIDER(
            "affordability.provider", "STRING", "AFFORDABILITY", "MOCK",
            "Affordability provider",
            "Which assessor scores an affordability check. MOCK uses the platform's own documented rules; "
                    + "OCP calls the credit microservice. An unknown value falls back to MOCK and logs.",
            false, false),
    AFFORDABILITY_DTI_CEILING(
            "affordability.dti.ceiling.percent", "INTEGER", "AFFORDABILITY", "40",
            "Debt-to-income ceiling (%)",
            "The share of net monthly income a repayment may take before the mock assessor calls a "
                    + "household stretched. Kenyan lenders commonly sit between 35 and 50.", false, false),
    AFFORDABILITY_MARGINAL_BAND(
            "affordability.marginal.band.percent", "INTEGER", "AFFORDABILITY", "10",
            "Marginal band (%)",
            "How far past the ceiling still counts as MARGINAL rather than NOT_ELIGIBLE. A hard line at "
                    + "the ceiling turns a shilling into a refusal, which is not how a lender reads it.",
            false, false),
    AFFORDABILITY_DEFAULT_RATE(
            "affordability.default.rate.percent", "STRING", "AFFORDABILITY", "13.5",
            "Indicative rate (%)",
            "The rate assumed when no specific product is in play. Only ever used for the headline "
                    + "figure — a quote against a real product uses that product's own rate.", false, false),
    AFFORDABILITY_DEFAULT_TERM(
            "affordability.default.term.months", "INTEGER", "AFFORDABILITY", "240",
            "Default term (months)",
            "The term the calculator opens with. Twenty years.", false, false),
    OCP_BASE_URL(
            "ocp.base.url", "STRING", "AFFORDABILITY", "",
            "OCP base URL",
            "Credit microservice endpoint. Declared now so the real provider is a class and two rows, "
                    + "not a migration.", false, false),
    OCP_API_KEY(
            "ocp.api.key", "STRING", "AFFORDABILITY", "",
            "OCP API key", "Credential for the credit microservice.", true, false),

    /**
     * The seller types for which clearance is a precondition to listing.
     *
     * <p>The policy statement §13 left open. The machinery was already there — {@code
     * EffectivePermissionResolver} drops the listing permissions for any profile whose {@code kyc_status}
     * does not clear — and what was missing was a statement of *when* that applies.
     *
     * <p>Applied at the moment a seller type is set, not re-derived on every permission resolution: a
     * tenant given a listed type has its people moved from {@code NOT_REQUIRED} (never asked) to {@code
     * PENDING} (asked, not yet cleared), and PENDING is what fails the gate. Empty means nothing is
     * mandatory, which is the setting to reach for while onboarding a market rather than a code change.
     */
    KYC_REQUIRED_SELLER_TYPES(
            "kyc.required.seller.types", "STRING", "GENERAL",
            "INDIVIDUAL,COMPANY,SACCO,DEVELOPER,AGENCY,GOVERNMENT",
            "Seller types needing KYC",
            "Comma-separated seller types that must be cleared by Compliance before they can list. "
                    + "Empty means nobody is blocked.", false, false),

    // ── AGENTS (M9, BRD FR160–FR161) ─────────────────────────────────────────
    /**
     * Whether an agent can start an application themselves.
     *
     * <p>Unlike buyer self-registration this creates a *privileged* actor — somebody who will list property —
     * so the switch matters more. It is safe on by default only because registering does not grant anything:
     * the application lands PENDING, the profile's KYC status fails the listing gate, and no organisation
     * exists to list into until the platform approves it.
     */
    AGENT_SELF_REGISTRATION_ENABLED(
            "agent.self.registration.enabled", "BOOLEAN", "AGENT", "true",
            "Agents may apply themselves",
            "When false, agent applications can only be started by platform staff.", false, false),
    /**
     * The version of the terms currently in force.
     *
     * <p>Bumping this is what makes existing signatures historical rather than wrong: an artifact records the
     * version it was captured against, so "which agents have accepted the current terms" is answerable
     * afterwards rather than needing to have been anticipated.
     */
    AGENT_TERMS_VERSION(
            "agent.terms.version", "STRING", "AGENT", "2026.1",
            "Agent terms version",
            "Bump this when the terms change. Signatures keep the version they were captured against.",
            false, false),
    /**
     * The terms themselves.
     *
     * <p>In configuration rather than in a template file because the hash of this exact text is the evidence:
     * a text a deploy can change without anybody noticing is a text nobody can be held to. Changing it here
     * is an audited settings edit with a name against it.
     */
    AGENT_TERMS_TEXT(
            "agent.terms.text", "TEXT", "AGENT",
            "1. You confirm that the information in your application is true, and that any licence you have "
                    + "named is current and held in your own name or your agency's.\n"
                    + "2. You will mark every listing you create as either your own property or a client's, "
                    + "and you will not list a client's property without their instruction to do so.\n"
                    + "3. You will keep your clients' personal details accurate and will not use them for "
                    + "anything other than the sale you were instructed on.\n"
                    + "4. You are responsible for the accuracy of every listing you publish, including its "
                    + "price, its description and its photographs.\n"
                    + "5. Hodi Market Place introduces buyers and sellers. It is not a party to any sale, it "
                    + "holds no deposit, and it gives no valuation or legal advice.\n"
                    + "6. Your registration may be suspended if a listing is found to be materially "
                    + "inaccurate, if a licence lapses, or if a client complains and the complaint is upheld.\n"
                    + "7. You may end this agreement at any time by writing to the platform, and your live "
                    + "listings will be withdrawn.",
            "Agent terms",
            "The text an agent is shown and signs. Its SHA-256 is stored with every signature, so editing "
                    + "this does not change what anybody has already accepted.", false, false),
    /**
     * The agreement generated on approval.
     *
     * <p>Placeholders in double braces are filled at generation: {@code {{agentName}}}, {@code {{agency}}},
     * {@code {{licence}}}, {@code {{reference}}}, {@code {{date}}}, {@code {{termsVersion}}},
     * {@code {{signedBy}}}, {@code {{signedAt}}}, {@code {{terms}}}.
     */
    AGENT_AGREEMENT_TEMPLATE(
            "agent.agreement.template", "TEXT", "AGENT",
            "AGENCY AGREEMENT\n"
                    + "Hodi Market Place and {{agentName}}\n"
                    + "Reference {{reference}} · effective {{date}} · terms version {{termsVersion}}\n\n"
                    + "Agent: {{agentName}}\n"
                    + "Agency: {{agency}}\n"
                    + "Licence: {{licence}}\n\n"
                    + "The agent named above has applied to list property on Hodi Market Place, has been "
                    + "approved by the platform, and accepted the following terms:\n\n"
                    + "{{terms}}\n\n"
                    + "Signed by {{signedBy}} on {{signedAt}}.",
            "Agent agreement template",
            "Rendered once when an agent is approved and stored with the agent. Placeholders in double "
                    + "braces are filled at generation.", false, false),

    // ── VENDORS (M10, BRD FR170) ─────────────────────────────────────────────
    /**
     * Whether a business can apply to be listed without being invited.
     *
     * <p>Safe on by default for the reason the agent switch is: applying grants nothing. A vendor who is
     * never approved has no organisation, fails the publication gate, and can read the directory.
     */
    VENDOR_SELF_REGISTRATION_ENABLED(
            "vendor.self.registration.enabled", "BOOLEAN", "VENDOR", "true",
            "Vendors may apply themselves",
            "When false, vendor applications can only be started by platform staff.", false, false),

    // ── RATINGS (M7) ─────────────────────────────────────────────────────────
    /**
     * Words that hold a rating for review rather than publishing it.
     *
     * <p>Not a judgement about the words — a rating held here is queued, not rejected. What the list buys is
     * that the obvious cases never appear publicly even for the minutes before somebody reports them, which
     * is the window that matters for a name, a phone number or an accusation.
     *
     * <p>Deliberately short and deliberately editable. A long automatic list catches ordinary complaints and
     * teaches people to write around it.
     */
    RATING_HELD_WORDS(
            "rating.held.words", "STRING", "GENERAL",
            "fraud,fraudster,scam,thief,criminal,sue,lawyer up,my number is",
            "Words that hold a review",
            "Comma-separated. A review containing one of these waits for a moderator instead of appearing. "
                    + "Empty means nothing is held automatically.", false, false),

    // ── COMMISSION (M13) ─────────────────────────────────────────────────────
    /**
     * What the platform earns on a completed sale, as a percentage of the price.
     *
     * <p>Overridable per organisation, which is how a seller with an agreed rate carries it without a table
     * of their own. The rate is copied onto every commission when it is raised, so changing this affects
     * what is owed from here on and never restates what was owed before.
     *
     * <p>Zero is a valid setting and means nothing is raised at all.
     */
    COMMISSION_RATE_PERCENT(
            "commission.rate.percent", "STRING", "GENERAL", "1.5",
            "Commission rate (%)",
            "Charged on the sale price when a listing is marked sold. Copied onto each commission, so "
                    + "changing it never restates what was already owed. Zero raises nothing.", false, true),

    // ── DOCUMENT VAULT (plan §3.9) ───────────────────────────────────────────
    /**
     * A separate bucket for documents nobody but Compliance should see.
     *
     * <p>Empty falls back to the media bucket, where the {@code vault/} key prefix still separates them —
     * enough for a prefix policy, not enough for a different retention or a different account. A real
     * deployment sets this.
     */
    VAULT_S3_BUCKET(
            "vault.s3.bucket", "STRING", "STORAGE", "",
            "Vault bucket",
            "Where KYC and legal documents are written. Falls back to the media bucket, separated only by "
                    + "the vault/ prefix — set this in production.", false, false),
    /**
     * A KMS key for the vault. Set, objects are written SSE-KMS; unset, SSE-S3.
     *
     * <p>Either way the row records which, because "was this document encrypted at rest" is a question about
     * a particular document rather than about the deployment somebody happens to be reading it on.
     */
    VAULT_KMS_KEY_ID(
            "vault.kms.key.id", "STRING", "STORAGE", "",
            "Vault KMS key",
            "Customer-managed key for vault objects. Empty uses S3-managed encryption (SSE-S3).",
            false, false),

    STORAGE_LOCAL_DIR(
            "storage.local.dir", "STRING", "STORAGE", "uploads",
            "Local media directory",
            "Where files are written when no S3 bucket is configured. Relative to the working "
                    + "directory.", false, false),
    STORAGE_S3_ACCESS_KEY(
            "storage.s3.access.key", "STRING", "STORAGE", "",
            "S3 access key", "Encrypted at rest and masked in responses.", true, false),
    STORAGE_S3_SECRET_KEY(
            "storage.s3.secret.key", "STRING", "STORAGE", "",
            "S3 secret key", "Encrypted at rest and masked in responses.", true, false),
    /**
     * Where locally-stored files are served from, as an <em>origin</em> — not a path.
     *
     * <p>It used to default to {@code /media}, which {@code StorageService.urlFor} then appended
     * {@code /media/} to: every local URL came out as {@code /media/media/…} and every photograph was a broken
     * image. The path is the application's own ({@code MediaController} owns {@code /media/**}), so what this
     * key configures is the host in front of it — a CDN, or an object store's public origin.
     *
     * <p>Empty means same origin, which is the right default: a relative URL works behind any host, needs no
     * configuration in development, and cannot point at the wrong environment.
     */
    STORAGE_LOCAL_BASE_URL(
            "storage.local.base.url", "STRING", "STORAGE", "",
            "Local media origin",
            "Host that serves locally-stored files. Empty means this application, which is usually right.",
            false, false),
    /**
     * Ceiling for a development's photographs, floor plans and brochures.
     *
     * <p>Below {@code StorageService}'s own 10 MB, which is sized for a title document rather than the two
     * hundredth photograph of a building site. Configurable because the right number depends on whose phones
     * are taking the pictures, and an operator should not need a deploy to tighten it.
     */
    MEDIA_MAX_KB(
            "storage.media.max.kb", "INTEGER", "STORAGE", "8192",
            "Max media size (KB)",
            "Rejected above this before anything is written. Applies to development, phase, unit and "
                    + "progress files.", false, false),
    STORAGE_AVATAR_MAX_KB(
            "storage.avatar.max.kb", "INTEGER", "STORAGE", "2048",
            "Max avatar size (KB)", "Rejected above this before anything is written.", false, false),

    /**
     * The shared secret an inbound Pesi notification must carry.
     *
     * <p>Blank by default, and what that means is deliberate: the endpoint still accepts and stores every
     * notification — refusing them would make Pesi retry and eventually give up, losing real money — but
     * nothing is matched to a booking automatically. Every payment waits for a person instead.
     *
     * <p>That is the safe failure. An unauthenticated endpoint that creates payment records is one where a
     * forged notification guessing a four-character code and an amount could credit somebody's balance. With
     * no secret configured we will take the money in and let a human place it; with one configured we will
     * place it ourselves.
     *
     * <p>Secret, and not tenant-overridable: there is one Pesi business — the marketplace — so there is one
     * key, held by the platform. A tenant able to set this could authorise notifications against everybody's
     * tills.
     */
    PESI_IPN_SECRET(
            "pesi.ipn.secret", "STRING", "INTEGRATION", "",
            "Pesi notification secret",
            "Required in the X-Pesi-Signature header on inbound notifications. While blank, payments are "
                    + "still accepted and stored but never matched automatically.", true, false);

    private final String key;
    private final String valueType;
    private final String category;
    private final String defaultValue;
    private final String label;
    private final String description;
    private final boolean secret;
    private final boolean overridable;
}
