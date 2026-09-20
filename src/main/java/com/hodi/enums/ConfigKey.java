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
            "Idle window for every back-office session.", false, false),
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
    //
    // These are SEED VALUES for a database that does not have the key yet, and nothing more. Every one of
    // them is edited from Settings, and the running platform's colours come from the configurations table
    // rather than from here — so re-branding a deployment is an afternoon on that screen, not a release.
    //
    // They must stay in step with the defaults in the frontend's styles/theme.css, which are what the
    // browser paints before the theme store has fetched anything. A default here that disagrees with the
    // one there is a visible colour shift between first paint and hydration.
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

    // ── BRANDING ──────────────────────────────────────────────────────────────
    //
    // Who the platform says it is: the name, and the three ways to reach it. Their own category rather
    // than sitting in GENERAL with session windows and rounding, because they are what somebody changes
    // when they are re-branding and they should be one screenful when they do — the same grouping axis
    // uses, for the same reason.
    //
    // The name has a default and the contacts do not, and that asymmetry is deliberate. A platform with
    // no name renders a blank browser tab, so it needs one to start from. A platform with no published
    // telephone number should show nothing at all — a seeded placeholder is worse than an absence,
    // because it looks like a real detail right up until somebody tries it.
    //
    // Platform-only, unlike axis where they are per-tenant. Axis's tenants are separate businesses with
    // their own storefronts; this platform's sellers work inside one marketplace, and their own contact
    // details already live on the tenant row. A seller overriding "platform name" would be renaming the
    // marketplace in its own browser title.
    COMPANY_NAME(
            "company.name", "STRING", "BRANDING", "Hodi Market Place",
            "Platform name",
            "Shown in the browser title, the brand lockup, emails and the documents an agent signs.",
            false, false),
    COMPANY_EMAIL(
            "company.email", "STRING", "BRANDING", "",
            "Contact email", "Public contact address. Hidden while blank.", false, false),
    COMPANY_PHONE(
            "company.phone", "STRING", "BRANDING", "",
            "Contact phone", "Public contact number. Hidden while blank.", false, false),
    COMPANY_ADDRESS(
            "company.address", "STRING", "BRANDING", "",
            "Contact address", "Public postal address. Hidden while blank.", false, false),

    // ── GENERAL ───────────────────────────────────────────────────────────────
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
                    + "household stretched. Kenyan banks commonly sit between 35 and 50.", false, false),
    AFFORDABILITY_MARGINAL_BAND(
            "affordability.marginal.band.percent", "INTEGER", "AFFORDABILITY", "10",
            "Marginal band (%)",
            "How far past the ceiling still counts as MARGINAL rather than NOT_ELIGIBLE. A hard line at "
                    + "the ceiling turns a shilling into a refusal, which is not how a bank reads it.",
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

    /*
     * ── seller onboarding's three outside checks ─────────────────────────────────────────────────
     *
     * Empty, and declared anyway, for the reason written on OCP_BASE_URL above: the real provider is then
     * a class and two rows rather than a migration. Each provider reports itself unconfigured while its
     * base URL is blank, which is what puts "not connected yet" in front of the applicant instead of a
     * silent pass.
     */
    COOP_ACCOUNT_BASE_URL(
            "coop.account.base.url", "STRING", "INTEGRATION", "",
            "Co-op account validation URL",
            "Core-banking endpoint that validates an account number and returns the holder. Blank means "
                    + "the step is shown as pending integration.", false, false),
    COOP_ACCOUNT_API_KEY(
            "coop.account.api.key", "STRING", "INTEGRATION", "",
            "Co-op account validation key", "Credential for account validation.", true, false),
    AML_BASE_URL(
            "aml.base.url", "STRING", "INTEGRATION", "",
            "AML screening URL",
            "Screening service for a seller applying without a Co-op account. Blank means the step is "
                    + "shown as pending integration.", false, false),
    AML_API_KEY(
            "aml.api.key", "STRING", "INTEGRATION", "",
            "AML screening key", "Credential for the screening service.", true, false),
    IPRS_BASE_URL(
            "iprs.base.url", "STRING", "INTEGRATION", "",
            "IPRS lookup URL",
            "Registry lookup confirming an ID number belongs to the person named. Blank means the step "
                    + "is shown as pending integration.", false, false),
    IPRS_API_KEY(
            "iprs.api.key", "STRING", "INTEGRATION", "",
            "IPRS lookup key", "Credential for the registry lookup.", true, false),

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
                    + "5. {{platformName}} introduces buyers and sellers. It is not a party to any sale, it "
                    + "holds no deposit, and it gives no valuation or legal advice.\n"
                    + "6. Your registration may be suspended if a listing is found to be materially "
                    + "inaccurate, if a licence lapses, or if a client complains and the complaint is upheld.\n"
                    + "7. You may end this agreement at any time by writing to the platform, and your live "
                    + "listings will be withdrawn.",
            "Agent terms",
            "The text an agent is shown and signs. {{platformName}} is filled in from the platform name, so "
                    + "re-branding does not leave the old name in a document somebody is signing. Its "
                    + "SHA-256 is stored with every signature, so editing this does not change what anybody "
                    + "has already accepted.", false, false),
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
                    + "{{platformName}} and {{agentName}}\n"
                    + "Reference {{reference}} · effective {{date}} · terms version {{termsVersion}}\n\n"
                    + "Agent: {{agentName}}\n"
                    + "Agency: {{agency}}\n"
                    + "Licence: {{licence}}\n\n"
                    + "The agent named above has applied to list property on {{platformName}}, has been "
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
     * What an uploaded photograph is compressed towards, in KB.
     *
     * <p>A target rather than a limit. An image over it is resized and re-encoded; one that will not reach it
     * at acceptable quality is stored slightly over. Nothing is refused for being too big — a phone
     * photograph is eight to twenty megabytes and the person uploading it has no way to shrink it.
     */
    IMAGE_TARGET_KB(
            "image.target.kb", "INTEGER", "STORAGE", "900",
            "Compress photographs towards (KB)",
            "Images above this are resized and re-encoded rather than refused. Not a hard limit.",
            false, false),

    /**
     * The longest edge a stored photograph keeps, in pixels.
     *
     * <p>Where most of the saving comes from, and the reason the encoder rarely has to work hard. 2560 is
     * sharp on every screen these are viewed on, including a retina laptop at full width.
     */
    IMAGE_MAX_EDGE(
            "image.max.edge", "INTEGER", "STORAGE", "2560",
            "Longest edge kept (pixels)",
            "A photograph larger than this is scaled down. Most of the size saving is here rather than in "
                    + "the encoder.", false, false),

    /** Where JPEG encoding starts. 85 is the usual point at which re-encoding stops being visible. */
    IMAGE_JPEG_QUALITY(
            "image.jpeg.quality", "INTEGER", "STORAGE", "85",
            "JPEG quality (%)",
            "Where compression starts. Steps down towards the floor only if the target is not met.",
            false, false),

    /**
     * How far JPEG quality may fall before we accept a file over target.
     *
     * <p>The point of the floor: an image that cannot reach the target without going below this is stored
     * larger instead. A visibly mushy photograph of somebody's building is worse than half a megabyte.
     */
    IMAGE_JPEG_QUALITY_FLOOR(
            "image.jpeg.quality.floor", "INTEGER", "STORAGE", "65",
            "Lowest JPEG quality (%)",
            "Compression stops here. An image that still exceeds the target is stored over it rather than "
                    + "degraded further.", false, false),

    /**
     * The shared secret an inbound Co-op notification must carry.
     *
     * <p>Blank by default, and what that means is deliberate: the endpoint still accepts and stores every
     * notification — refusing them would make Co-op retry and eventually give up, losing real money — but
     * nothing is matched to a booking automatically. Every payment waits for a person instead.
     *
     * <p>That is the safe failure. An unauthenticated endpoint that creates payment records is one where a
     * forged notification guessing a four-character code and an amount could credit somebody's balance. With
     * no secret configured we will take the money in and let a human place it; with one configured we will
     * place it ourselves.
     *
     * <p>Secret, and not tenant-overridable: there is one Co-op business — the marketplace — so there is one
     * key, held by the platform. A tenant able to set this could authorise notifications against everybody's
     * tills.
     */
    /**
     * Where a one-time code goes when the platform's own payment account is being changed.
     *
     * <p>An organisation's accounts are confirmed by a code to that organisation's contact number; the
     * platform has no organisation record, so this is its equivalent. While blank, the platform's own
     * accounts cannot be set up — refused plainly rather than silently skipping the code.
     */
    PLATFORM_SUPPORT_PHONE(
            "platform.support.phone", "STRING", "GENERAL", "",
            "Platform support phone",
            "The number a one-time code is texted to when the platform's own payment accounts are changed.",
            false, false),
    /**
     * What Co-op presents when it calls us, and what we answer it with.
     *
     * <h3>Basic, and closed when unset</h3>
     *
     * <p>Co-op reaches this platform directly — there is no gateway in between — and authenticates with
     * HTTP Basic, which is what its own integration expects. While either of these is blank the
     * notification endpoint refuses every request rather than falling open: a deployment that accepts
     * anonymous payment notifications is worse than one that accepts none, because the first records money
     * that never arrived and the second merely stops.
     *
     * <p>This replaces the notification secret that came before it. That was an {@code Authorization}
     * header, which is how the Co-op gateway authenticates <em>its</em> business clients — a mechanism from
     * a topology this platform does not use.
     */
    COOP_IPN_USERNAME(
            "coop.ipn.username", "STRING", "INTEGRATION", "",
            "Co-op notification username",
            "The username Co-op sends on inbound payment notifications, as HTTP Basic. While this or the "
                    + "password is blank, notifications are refused rather than accepted unauthenticated.",
            false, false),
    COOP_IPN_PASSWORD(
            "coop.ipn.password", "STRING", "INTEGRATION", "",
            "Co-op notification password",
            "The password paired with the notification username. Stored encrypted.", true, false),

    /**
     * What we present to Co-op.
     *
     * <p>OAuth2 client credentials: these are exchanged for a bearer token, which is cached until it
     * expires rather than fetched per call — a token request against every payment is how an integration
     * gets rate-limited into failures.
     *
     * <p><strong>No host here, and no endpoint.</strong> Where the calls go is a property of each payment
     * type, configured against it, because a platform that hardcodes a bank's URL has to be redeployed
     * when that bank moves a path or opens a second environment. These two are the credentials only.
     */
    /**
     * Which addresses may notify us, and the reason it is here rather than on an account.
     *
     * <p>It was a field on the inbound-account form, copied across from a gateway where each business
     * declared the addresses <em>it</em> would be called from. That is the wrong way round here: nobody
     * calls out to a business — Co-op calls this platform, and which of Co-op's addresses may reach us is
     * one fact about one bank, not a property of each account somebody sets up.
     *
     * <p><b>Empty accepts every address</b>, deliberately. The alternative reading turns a setting nobody
     * has filled in yet into a deployment that silently drops every payment notification it receives, and
     * the money would already be in the bank. HTTP Basic is the control that fails closed; this one
     * narrows it.
     */
    COOP_IPN_ALLOWED_IPS(
            "coop.ipn.allowed.ips", "STRING", "INTEGRATION", "",
            "Addresses Co-op notifies us from",
            "Comma-separated IP addresses allowed to post payment notifications. Empty accepts any "
                    + "address, leaving HTTP Basic as the control.", false, false),

    COOP_CONSUMER_KEY(
            "coop.consumer.key", "STRING", "INTEGRATION", "",
            "Co-op consumer key",
            "The OAuth2 client id issued by Co-op, exchanged for the bearer token every outbound call "
                    + "carries.", false, false),
    COOP_CONSUMER_SECRET(
            "coop.consumer.secret", "STRING", "INTEGRATION", "",
            "Co-op consumer secret",
            "The OAuth2 client secret issued by Co-op. Stored encrypted.", true, false),

    /**
     * Where every Co-op call goes, and the endpoint that authenticates it.
     *
     * <p><strong>One host, and no environment switch.</strong> There used to be a sandbox host and a
     * production host on each payment type and a setting saying which was live. That is two sources of
     * truth for one fact: an address that reads sandbox <em>is</em> the sandbox, and a switch that can
     * disagree with it is a way to pay the wrong bank. What is in here decides, and nothing else does.
     *
     * <p>Shared by every channel, because a bank has one base address — what differs per channel is the
     * endpoint hanging off it, which is configured against the payment type.
     *
     * <p>Blank means no outbound call is attempted, so a deployment that has not been configured cannot
     * quietly reach somebody else's sandbox.
     */
    COOP_BASE_URL(
            "coop.base.url", "STRING", "INTEGRATION", "",
            "Co-op host",
            "The base address every Co-op call is made against. Whether this deployment talks to the "
                    + "sandbox or to production is decided by what is in here and nothing else.",
            false, false),
    COOP_TOKEN_PATH(
            "coop.token.path", "STRING", "INTEGRATION", "",
            "Co-op token path",
            "The OAuth2 endpoint on that host, exchanged for the bearer token every call carries. One "
                    + "for the bank rather than one per channel.", false, false),

    /**
     * Whether an organisation may collect money into an account of its own.
     *
     * <p>{@code PLATFORM} — every payment is collected to the platform's account, and an organisation
     * cannot attach one. This is the default and what the platform did before the setting existed.
     *
     * <p>{@code ORGANISATION} — an organisation configures its own account and collects to it; one that
     * has not falls back to the platform's, so turning this on cannot leave anybody unable to take money.
     *
     * <p>Read where an account is <em>set up</em>, which is the only moment it can be applied: the
     * ownership columns already exist and {@code PaymentScope} already decides who may read which, so
     * what this decides is whether a non-platform owner may be written at all.
     *
     * <p>Not overridable. A setting that says whether organisations may collect their own money is not
     * one an organisation may answer for itself.
     */
    /**
     * Whether a buyer may validate a bank slip themselves.
     *
     * <p>Slip validation finds an unused credit by the reference the payer quotes and applies it to their
     * booking. Platform staff always may. Whether the buyer may — from their own account, against their
     * own booking — is the institution's call: it saves a phone call for every transfer, and it also lets a
     * payer probe references. OFF until somebody decides.
     */
    PAYMENTS_BUYER_SLIP_VALIDATION(
            "payments.buyer.slip.validation", "BOOLEAN", "PAYMENTS", "false",
            "Buyers may validate a bank slip",
            "When true, a signed-in buyer paying for their own booking may enter the bank reference of a "
                    + "transfer they made and have the matching credit applied. Platform staff can always "
                    + "do this on a buyer's behalf.", false, false),

    PAYMENT_COLLECTION_SCOPE(
            "payments.collection.scope", "STRING", "PAYMENTS", "PLATFORM",
            "Who collects payments",
            "PLATFORM: every payment is collected to the platform's own account. ORGANISATION: an "
                    + "organisation with a configured account of its own collects to it, and one without "
                    + "falls back to the platform's.", false, false),

    /**
     * Who we are to Co-op on the calls that ask for it.
     *
     * <p>Their {@code UserID} — account validation and transfer status both carry it, while the phone
     * prompt does not. One value for the institution, so it is a setting rather than something repeated
     * on every account.
     */
    COOP_USER_ID(
            "coop.user.id", "STRING", "INTEGRATION", "",
            "Co-op user ID",
            "The UserID Co-op issued, sent on account validation and transfer status enquiries.",
            false, false),

    /**
     * How a Co-op status answer is read.
     *
     * <p>Their "still processing" is a MessageCode and a MessageDescription rather than an HTTP state,
     * and the exact values are theirs to change — so they are configured rather than compiled. Anything
     * unrecognised is read as still-processing: a payment failed on a code we simply did not know is a
     * customer told their money did not arrive when it did.
     */
    COOP_PENDING_STATUS_CODES(
            "coop.pending.status.codes", "STRING", "INTEGRATION", "S_001",
            "Co-op still-processing codes",
            "Comma-separated MessageCode values meaning the payment is still in progress.",
            false, false),
    COOP_PENDING_STATUS_DESCRIPTIONS(
            "coop.pending.status.descriptions", "STRING", "INTEGRATION", "PROCESSING",
            "Co-op still-processing descriptions",
            "Comma-separated MessageDescription values meaning the payment is still in progress.",
            false, false),

    /**
     * How many times the sweep may ask about one stuck payment.
     *
     * <p>Capped because an uncapped sweep re-queries a stuck payment every thirty seconds for as long
     * as it exists. Past the cap it stops and leaves a sentence for a person. A person's own query is
     * neither counted nor limited — the cap exists to stop a machine looping, not to stop an operator
     * working.
     */
    COOP_STATUS_QUERY_MAX_ATTEMPTS(
            "coop.status.query.max.attempts", "INTEGER", "INTEGRATION", "2",
            "Automatic status queries per payment",
            "How many times the sweep asks Co-op about one stuck payment before leaving it for a person.",
            false, false),
    /**
     * How long the request holds open while the customer decides.
     *
     * <p>A phone prompt is answered by a person walking to their handset and typing a PIN, so the honest
     * interaction is to wait for them rather than to answer "sent" and make somebody watch a list. The
     * request is held, the answer is the payment itself, and the screen closes on it.
     *
     * <p>Under the client's own timeout on purpose — the browser gives it three minutes — so a wait that
     * runs out is answered by this server with an intent still in flight rather than by the browser with a
     * network error and nothing to show.
     */
    COOP_STK_WAIT_SECONDS(
            "coop.stk.wait.seconds", "INTEGER", "INTEGRATION", "150",
            "Seconds to wait at the screen",
            "How long a phone prompt holds the screen while the customer approves it. After this the "
                    + "payment is left in flight and the status query settles it.",
            false, false),
    COOP_CALLBACK_TIMEOUT_SECONDS(
            "coop.callback.timeout.seconds", "INTEGER", "INTEGRATION", "60",
            "Seconds to wait for a callback",
            "How long a payment waits for Co-op to call back before the sweep asks what became of it.",
            false, false),

    /**
     * Which providers the payment catalogue offers.
     *
     * <p>The catalogue was seeded with every bank the reference gateway fronts — Safaricom, KCB, Co-op and
     * Equity — because that table described what <em>it</em> could do rather than what this deployment
     * sells through. This deployment banks with Co-op, so the rest are noise on a platform screen and,
     * worse, a list somebody can attach an account to the wrong bank from.
     *
     * <p>Empty means no restriction, matching {@code KYC_REQUIRED_SELLER_TYPES}: a blank allow-list allows
     * everything, because the other reading turns an accidentally-cleared setting into "no way to take
     * money".
     *
     * <p>What this does <em>not</em> touch is inbound. A credit that arrives for a channel outside this
     * list is still stored and still recorded — money already in the bank is not made to disappear by a
     * setting about what to offer next.
     */
    PAYMENT_PROVIDERS(
            "payment.providers", "STRING", "INTEGRATION", "Co-operative Bank",
            "Payment providers offered",
            "Comma-separated provider names whose channels appear in the payment catalogue and can be "
                    + "given an account. Cash and cheque are always offered. Empty means every provider.",
            false, false);

    private final String key;
    private final String valueType;
    private final String category;
    private final String defaultValue;
    private final String label;
    private final String description;
    private final boolean secret;
    private final boolean overridable;
}
