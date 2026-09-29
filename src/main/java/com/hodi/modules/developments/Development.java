package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One development project: many units, sold off-plan or never sold at all.
 *
 * <p>A {@link com.hodi.modules.properties.Property} is one saleable thing. This is the thing a hundred of them
 * belong to, and it is a different kind of row: it has phases, a budget, a completion date that has moved
 * twice, and — when a bank financed it rather than a seller listing it — no marketplace presence whatsoever.
 *
 * <h2>Exactly one principal, and it may be the bank</h2>
 *
 * <p>{@link #tenantId} or {@link #institutionId}, never both and never neither. A bank financing a
 * developer's block owns that record: they created it and it is their exposure being tracked.
 * {@code auction_lots} solved the same problem the same way, and this class follows it — including the
 * consequence, which is that visibility cannot come from {@code TenantScope}. The bank is not a tenant and has
 * no visible-tenant set describing it, so {@code DevelopmentService} carries a hand-written specification
 * instead.
 *
 * <p>{@link #sellingTenantId} is a third question, not a synonym for either: whose listings the units become
 * when the project is marketed. A tracked-only project has none, which is why the never-for-sale case needs no
 * flag — it is a development with no selling tenant, no properties rows, and a {@code PRIVATE} listing state.
 *
 * <h2>Counted columns, and one writer</h2>
 *
 * <p>Every {@code units*} figure, both prices, and {@link #percentComplete} are counted from the units and
 * phases below by {@code DevelopmentInventoryService} and by nothing else. They are stored rather than derived
 * on read for the reason {@code promotionBoost} is stored: a marketplace card reads them once per result, and
 * an aggregate per card is a join per card. {@link #percentBasis} records which rule produced the percentage
 * so a screen can say "62%, weighted by phase budget" — a derived figure that cannot explain itself gets read
 * as somebody's opinion.
 */
@Entity
@Table(name = "developments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Development {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 16) private String reference;

    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "tenant_name", length = 255) private String tenantName;
    @Column(name = "institution_id") private Long institutionId;
    @Column(name = "institution_name", length = 255) private String institutionName;

    @Column(name = "selling_tenant_id") private Long sellingTenantId;
    @Column(name = "selling_tenant_name", length = 255) private String sellingTenantName;

    /** The builder, as a label. Not a foreign key: a bank's borrower is usually not on this platform. */
    @Column(name = "developer_name", length = 255) private String developerName;

    @Column(nullable = false, length = 255) private String name;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(name = "development_type", nullable = false, length = 32) private String developmentType;

    @Column(nullable = false, length = 24)
    @Builder.Default private String purpose = AppConstant.DEV_PURPOSE_FOR_SALE;

    @Column(length = 64) private String county;
    @Column(length = 64) private String town;
    @Column(length = 128) private String estate;
    @Column(name = "address_line", columnDefinition = "TEXT") private String addressLine;
    @Column(precision = 9, scale = 6) private BigDecimal latitude;
    @Column(precision = 9, scale = 6) private BigDecimal longitude;

    /** What the developer says they are building, kept apart from what has actually been entered. */
    @Column(name = "planned_unit_count") private Integer plannedUnitCount;

    @Column(name = "units_total", nullable = false) @Builder.Default private int unitsTotal = 0;
    @Column(name = "units_available", nullable = false) @Builder.Default private int unitsAvailable = 0;
    @Column(name = "units_reserved", nullable = false) @Builder.Default private int unitsReserved = 0;
    @Column(name = "units_sold", nullable = false) @Builder.Default private int unitsSold = 0;
    @Column(name = "from_price", precision = 15, scale = 2) private BigDecimal fromPrice;
    @Column(name = "to_price", precision = 15, scale = 2) private BigDecimal toPrice;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";

    /**
     * What the project itself is certified as, and how it performs.
     *
     * <p>These were on {@code properties} only, which had it backwards: an EDGE or Safari Green
     * certificate is issued to the project, and every unit in it inherits the claim. A seller could
     * therefore tick the box ninety times, once per listing, or not at all — and nothing anywhere held
     * the certificate itself. The evidence is {@code CERTIFICATE}-kind media against the development.
     */
    @Column(name = "green_certified", nullable = false) @Builder.Default private boolean greenCertified = false;
    @Column(name = "green_certification", length = 64) private String greenCertification;
    /** An A–G band, constrained by the column rather than by convention. */
    @Column(name = "energy_rating", length = 8) private String energyRating;

    @Column(name = "construction_status", nullable = false, length = 24)
    @Builder.Default private String constructionStatus = AppConstant.BUILD_PLANNED;
    @Column(name = "percent_complete", nullable = false) @Builder.Default private short percentComplete = 0;
    @Column(name = "percent_basis", nullable = false, length = 16)
    @Builder.Default private String percentBasis = AppConstant.PERCENT_BASIS_EQUAL;

    @Column(name = "started_on") private LocalDate startedOn;
    @Column(name = "projected_completion_on") private LocalDate projectedCompletionOn;
    @Column(name = "actual_completion_on") private LocalDate actualCompletionOn;

    /** Never on a public response record. The public DTO omits these rather than blanking them. */
    @Column(name = "budget_amount", precision = 15, scale = 2) private BigDecimal budgetAmount;
    @Column(name = "facility_reference", length = 32) private String facilityReference;
    @Column(name = "facility_amount", precision = 15, scale = 2) private BigDecimal facilityAmount;

    /**
     * Who collects a buyer's money on this development: {@link #COLLECTED_BY_BANK} or {@link #COLLECTED_BY_OWNER}.
     *
     * <p>The bank's decision, and per development rather than platform-wide, so that a change of model is a
     * reconfiguration. Under BANK only an account the bank configured collects for it — see
     * {@code PaymentAccountService} — because the bank sells on the owner's behalf and holds the money until
     * every party is satisfied.
     */
    @Column(name = "collection_mode", nullable = false, length = 8)
    @Builder.Default private String collectionMode = COLLECTED_BY_BANK;

    /**
     * Who records and pays this development's costs: {@link #MANAGED_BY_OWNER} or {@link #MANAGED_BY_BANK}.
     *
     * <p>The side that does not manage still reads every figure. See
     * {@code DevelopmentVisibility.assertMayManageSpending}.
     */
    @Column(name = "spending_managed_by", nullable = false, length = 8)
    @Builder.Default private String spendingManagedBy = MANAGED_BY_OWNER;

    /**
     * What the bank earns on a sale here, and what an agent earns for bringing the buyer, as percentages of
     * the price. Null means the platform's default at the moment a sale completes; zero raises nothing.
     * Copied onto every commission line when it is raised, so a change here never restates a past sale.
     */
    @Column(name = "bank_commission_percent", precision = 6, scale = 3) private BigDecimal bankCommissionPercent;
    @Column(name = "agent_commission_percent", precision = 6, scale = 3) private BigDecimal agentCommissionPercent;
    /**
     * Whose money the agent's fee comes out of when the bank settles a sale it collected:
     * {@link #AGENT_PAID_BY_SELLER} (the owner's proceeds, the usual arrangement) or {@link #AGENT_PAID_BY_BANK}
     * (the bank's own fee). Null means the seller. Changes who bears it, never the amount.
     */
    @Column(name = "agent_commission_paid_by", length = 8) private String agentCommissionPaidBy;

    /**
     * What a booking here is made under when refunded or revived (lapsed-bookings plan §2.2). Null means the
     * platform default; what a buyer agreed to is kept on their booking's terms, not read from here.
     */
    @Column(name = "refund_penalty_basis", length = 20) private String refundPenaltyBasis;
    @Column(name = "refund_penalty_rate", precision = 15, scale = 3) private BigDecimal refundPenaltyRate;
    @Column(name = "refund_penalty_cap", precision = 15, scale = 2) private BigDecimal refundPenaltyCap;
    @Column(name = "refund_penalty_bank_share_percent", precision = 6, scale = 3) private BigDecimal refundPenaltyBankSharePercent;
    @Column(name = "refund_within_days") private Integer refundWithinDays;
    @Column(name = "revive_within_days") private Integer reviveWithinDays;
    @Column(name = "booking_policy_note", columnDefinition = "TEXT") private String bookingPolicyNote;

    public static final String AGENT_PAID_BY_SELLER = "SELLER";
    public static final String AGENT_PAID_BY_BANK = "BANK";

    public String agentFeeBorneBy() {
        return AGENT_PAID_BY_BANK.equals(agentCommissionPaidBy) ? AGENT_PAID_BY_BANK : AGENT_PAID_BY_SELLER;
    }

    public static final String COLLECTED_BY_BANK = "BANK";
    public static final String COLLECTED_BY_OWNER = "OWNER";
    public static final String MANAGED_BY_OWNER = "OWNER";
    public static final String MANAGED_BY_BANK = "BANK";

    public boolean bankCollects() { return !COLLECTED_BY_OWNER.equals(collectionMode); }
    public boolean bankManagesSpending() { return MANAGED_BY_BANK.equals(spendingManagedBy); }

    @Column(name = "listing_state", nullable = false, length = 16)
    @Builder.Default private String listingState = AppConstant.LISTING_DRAFT;
    @Column(name = "published_at") private OffsetDateTime publishedAt;
    @Column(name = "withdrawn_at") private OffsetDateTime withdrawnAt;
    @Column(name = "withdrawn_reason", columnDefinition = "TEXT") private String withdrawnReason;
    @Column(name = "primary_image_key", length = 512) private String primaryImageKey;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;
    @Column(name = "deactivation_reason", columnDefinition = "TEXT") private String deactivationReason;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    /** Read-only: generated in the database, and writing to it from here would fail on flush. */
    @Column(name = "search_text", insertable = false, updatable = false) private String searchText;

    // ── the questions asked often enough to deserve a name ────────────────────

    public boolean isDraft() { return AppConstant.LISTING_DRAFT.equals(listingState); }
    public boolean isLive() { return AppConstant.LISTING_LIVE.equals(listingState); }

    /**
     * Tracked and never marketed — the bank's financed project.
     *
     * <p>Worth a method rather than a string comparison at each call site: it is the condition that decides
     * whether a development may have a selling tenant, whether its progress may be published, and whether it
     * appears in any public query at all.
     */
    public boolean isPrivate() { return AppConstant.DEV_STATE_PRIVATE.equals(listingState); }

    /** True when a lending institution owns this record rather than a seller organisation. */
    public boolean isInstitutionOwned() { return institutionId != null; }

    /** The owning organisation's name, whichever kind it is — for a label, never for authorisation. */
    public String principalName() { return institutionId != null ? institutionName : tenantName; }
}
