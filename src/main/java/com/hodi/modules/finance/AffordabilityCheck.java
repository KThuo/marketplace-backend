package com.hodi.modules.finance;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * One household's sums, kept (M3, BRD FR031–FR034).
 *
 * <p>Keyed on the person for the third time in this codebase, and for the third version of the same reason:
 * income is a fact about a household, not about an actor. See the shortlist and the consent store.
 *
 * <p>{@link #providerPayload} holds the assessor's own answer unparsed. When somebody asks why a figure was
 * what it was, the answer has to be the thing that produced it — not this application's reading of it, which
 * is precisely the part that could be wrong. It is also what makes the eventual switch to OCP auditable:
 * two rows from two assessors on the same inputs are comparable.
 */
@Entity
@Table(name = "affordability_checks")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AffordabilityCheck {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(nullable = false, length = 16) private String reference;

    @Column(name = "property_id") private Long propertyId;
    @Column(name = "property_reference", length = 16) private String propertyReference;
    @Column(name = "property_price", precision = 15, scale = 2) private BigDecimal propertyPrice;

    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";
    @Column(name = "monthly_take_home", nullable = false, precision = 15, scale = 2)
    private BigDecimal monthlyTakeHome;
    @Column(name = "other_monthly_income", nullable = false, precision = 15, scale = 2)
    @Builder.Default private BigDecimal otherMonthlyIncome = BigDecimal.ZERO;
    @Column(name = "monthly_obligations", nullable = false, precision = 15, scale = 2)
    @Builder.Default private BigDecimal monthlyObligations = BigDecimal.ZERO;
    @Column(name = "deposit_amount", nullable = false, precision = 15, scale = 2)
    @Builder.Default private BigDecimal depositAmount = BigDecimal.ZERO;
    @Column(name = "term_months", nullable = false) private Short termMonths;
    @Column(name = "employment_type", length = 24) private String employmentType;
    private Short dependants;

    @Column(nullable = false, length = 16) private String decision;
    @Column(name = "decision_reason", columnDefinition = "TEXT") private String decisionReason;
    @Column(name = "max_loan_amount", nullable = false, precision = 15, scale = 2)
    @Builder.Default private BigDecimal maxLoanAmount = BigDecimal.ZERO;
    @Column(name = "max_property_price", nullable = false, precision = 15, scale = 2)
    @Builder.Default private BigDecimal maxPropertyPrice = BigDecimal.ZERO;
    /**
     * What this particular listing needed borrowed, when the check was against one.
     *
     * <p>Kept beside the maximum rather than instead of it: reopening a check about a specific home has to
     * show what that home would have cost, not what the household could have borrowed in general.
     */
    @Column(name = "loan_required", precision = 15, scale = 2) private BigDecimal loanRequired;

    @Column(name = "monthly_repayment", nullable = false, precision = 15, scale = 2)
    @Builder.Default private BigDecimal monthlyRepayment = BigDecimal.ZERO;
    @Column(name = "dti_percent", precision = 5, scale = 2) private BigDecimal dtiPercent;
    @Column(name = "dti_ceiling_percent", precision = 5, scale = 2) private BigDecimal dtiCeilingPercent;
    @Column(name = "assumed_rate", precision = 6, scale = 3) private BigDecimal assumedRate;

    @Column(nullable = false, length = 24)
    @Builder.Default private String provider = AppConstant.PROVIDER_MOCK;
    @Column(name = "provider_reference", length = 64) private String providerReference;

    /**
     * The mortgage the figures were computed against, if one was chosen.
     *
     * <p>The reference and the name, not a foreign key: a check is what somebody was told on a day, and a
     * product whose rate moves afterwards must not silently rewrite the answer they were given. The same
     * reasoning the listing reference on this row already follows.
     */
    @Column(name = "product_reference", length = 16) private String productReference;
    @Column(name = "product_name", length = 160) private String productName;

    // jsonb needs the explicit JDBC type code — columnDefinition only drives DDL, and without this
    // Hibernate binds the map as bytea. Same annotation the audit log carries, for the same reason.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provider_payload", columnDefinition = "jsonb")
    private Map<String, Object> providerPayload;

    /**
     * The working, as it was shown on the day.
     *
     * <p>Kept rather than re-derived. Re-running the arithmetic when somebody reopens a check would answer
     * with today's product — a rate that has moved since, a ceiling the bank has tightened — and quietly
     * contradict the figures stored on this very row. A check is a record of what a household was told, and
     * the explanation is part of what they were told.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "provider_steps", columnDefinition = "jsonb")
    private java.util.List<AffordabilityProvider.Step> providerSteps;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;
}
