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
    STORAGE_LOCAL_BASE_URL(
            "storage.local.base.url", "STRING", "STORAGE", "/media",
            "Local media base URL", "Path prefix locally-stored files are served under.", false, false),
    STORAGE_AVATAR_MAX_KB(
            "storage.avatar.max.kb", "INTEGER", "STORAGE", "2048",
            "Max avatar size (KB)", "Rejected above this before anything is written.", false, false);

    private final String key;
    private final String valueType;
    private final String category;
    private final String defaultValue;
    private final String label;
    private final String description;
    private final boolean secret;
    private final boolean overridable;
}
