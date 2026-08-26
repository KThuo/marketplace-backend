package com.hodi.enums;

import com.hodi.common.AppConstant;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Discriminator for {@code users.user_type_code}. Seeded into {@code user_types} — this enum is the
 * source of truth and the rows are derived from it.
 *
 * <p><strong>User types are global</strong> (plan section 4.2): an organisation cannot invent one. Which
 * modules a type may reach is declared on the module, not here — see
 * {@code app_modules.allowed_user_types}.
 *
 * <p>The codes are stable identifiers once seeded. Renaming one means rewriting every
 * {@code allowed_user_types} CSV that mentions it, so treat a rename as a migration.
 *
 * <p>{@code actorClass} is what separates the four populations. It is a stored column rather than
 * something inferred from {@code tenant_id}/{@code institution_id}, because platform staff and buyers both
 * carry neither — inferring would make them indistinguishable, and the one that gets it wrong is the one
 * that grants a buyer platform access.
 */
@Getter
@RequiredArgsConstructor
public enum UserTypeEnum {

    // ── Platform (no organisation) ────────────────────────────────────────────
    SUPER_ADMIN("Super Administrator",
            "Full platform control — sellers, lending institutions, modules, global configuration",
            AppConstant.ACTOR_PLATFORM, 10),
    SUPPORT_ADMIN("Support Administrator",
            "Read-mostly troubleshooting across organisations",
            AppConstant.ACTOR_PLATFORM, 20),
    PLATFORM_AUDITOR("Platform Auditor",
            "Read-only access to audit logs and platform reporting for compliance review",
            AppConstant.ACTOR_PLATFORM, 30),

    // ── Seller organisations (users.tenant_id set) ────────────────────────────
    SELLER_OWNER("Seller Owner",
            "Full control of one seller organisation: staff, user groups, settings, portfolio, lender partnerships",
            AppConstant.ACTOR_SELLER, 40),
    LISTING_MANAGER("Listing Manager",
            "Property records and media for their organisation",
            AppConstant.ACTOR_SELLER, 50),
    SALES_AGENT("Sales Agent",
            "Buyer enquiries, viewings and conversations",
            AppConstant.ACTOR_SELLER, 60),

    // ── Lending institutions (users.institution_id set) ───────────────────────
    /**
     * Runs one institution. Cross-tenant by nature, and bounded by partnership rather than by grant: the
     * widest set this type can ever see is the sellers their institution has an active partnership with.
     */
    LENDER_ADMIN("Lender Administrator",
            "Full control of one lending institution: staff, user groups, seller partnerships",
            AppConstant.ACTOR_LENDER, 70),
    MORTGAGE_OFFICER("Mortgage Officer",
            "Works finance cases against the portfolios of partnered sellers",
            AppConstant.ACTOR_LENDER, 80),
    CREDIT_ANALYST("Credit Analyst",
            "Assessment and decisioning on finance cases",
            AppConstant.ACTOR_LENDER, 90),

    /**
     * Somebody looking to buy, signing in on their own behalf.
     *
     * <p>Unlike every type above, a buyer reaches no back-office module at all. What they may see is not
     * decided by permissions but by <strong>identity</strong> — their own saved properties, their own
     * enquiries, their own applications — and every buyer-facing query resolves that from the signed-in
     * principal rather than from an id in the request. The single {@code BUYER_PORTAL} module exists to admit
     * them to that surface and nothing else.
     */
    BUYER("Buyer",
            "Signs in to see their own saved properties, enquiries and finance applications",
            AppConstant.ACTOR_BUYER, 100),

    /**
     * A valuer on the platform's panel (M5, plan §3.5).
     *
     * <p>The third visibility rule on this platform, and the reason §3.5 called it a new mechanism. A seller
     * sees their organisation's rows; a lender sees the organisations they are partnered with; a valuer sees
     * <strong>the jobs assigned to them</strong> — not their firm's, not the requesting seller's portfolio,
     * and not the property they were sent to value beyond what the job says about it.
     *
     * <p>Like a buyer they carry no organisation, so like a buyer their scope cannot come from
     * {@code TenantScope}. It comes from the assignment column on each valuation row, and the module
     * enforces it in one place — see {@code ValuationScope}.
     */
    VALUER("Valuer",
            "An independent valuer on the platform's panel, working the jobs assigned to them",
            AppConstant.ACTOR_VALUER, 110),

    /**
     * An independent property agent (M9, BRD FR160–FR161).
     *
     * <p>Not to be confused with {@code SALES_AGENT} above, which is a seller organisation's employee. This
     * one registers themselves, signs the platform's terms, and on approval becomes their own one-person
     * selling organisation — so unlike a valuer they carry a tenant, and their visibility is the ordinary
     * "my organisation's rows" rule with nothing new behind it.
     */
    AGENT("Property Agent",
            "An independent agent listing their own property and their clients'",
            AppConstant.ACTOR_AGENT, 120),

    /**
     * A vendor: conveyancers, movers, surveyors, security firms (M10, BRD FR170).
     *
     * <p>The population the platform serves that never touches a listing. They publish a catalogue, and the
     * catalogue is what a buyer sees after an offer is accepted.
     */
    VENDOR("Vendor",
            "A business offering services to buyers and sellers",
            AppConstant.ACTOR_VENDOR, 130);

    private final String displayName;
    private final String description;
    private final String actorClass;
    private final int sortOrder;

    public boolean isPlatformLevel() {
        return AppConstant.ACTOR_PLATFORM.equals(actorClass);
    }
}
