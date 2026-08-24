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
    public static final String ACTOR_LENDER   = "LENDER";
    public static final String ACTOR_BUYER    = "BUYER";

    // ── KYC state (user_profiles.kyc_status — plan §3.3) ─────────────────────
    // NOT_REQUIRED is not the same as APPROVED even though both clear the gate: a platform
    // administrator was never asked, an approved seller was asked and passed, and the difference is
    // the thing Compliance is looking for when they read the row.
    public static final String KYC_NOT_REQUIRED = "NOT_REQUIRED";
    public static final String KYC_PENDING      = "PENDING";
    public static final String KYC_SUBMITTED    = "SUBMITTED";
    public static final String KYC_APPROVED     = "APPROVED";
    public static final String KYC_REJECTED     = "REJECTED";

    // ── Seller-tenant lifecycle (tenants.onboarding_status) ──────────────────
    public static final String ONBOARDING_PENDING    = "PENDING";
    public static final String ONBOARDING_ACTIVE     = "ACTIVE";
    public static final String ONBOARDING_SUSPENDED  = "SUSPENDED";
    public static final String ONBOARDING_TERMINATED = "TERMINATED";

    // ── Partnership portfolio scope (tenant_lender_partnerships.portfolio_scope) ──
    /** The lender may see the seller's whole portfolio. */
    public static final String PORTFOLIO_FULL = "FULL";
    /**
     * The lender may see only named listings. The column exists now so the later per-listing join
     * is additive; nothing writes this value until listings exist (plan section 12, question 3).
     */
    public static final String PORTFOLIO_SELECTED = "SELECTED";

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
    public static final String AUDIT_SESSION_REVOKED  = "SESSION_REVOKED";

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
