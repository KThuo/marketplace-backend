package com.hodi.modules.finance;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

/**
 * What one bank offers (M3, BRD FR025–FR030).
 *
 * <p>Owned by a lending institution and scoped by {@code institutionId} exactly as a listing is scoped by
 * {@code tenantId}. Which products a <em>buyer</em> sees against a particular listing is a different
 * question, and the partnership table already answers it — see {@code FinanceMatchService}.
 *
 * <p>Every money field is {@code BigDecimal} and every rate is a scaled decimal. A rate multiplies money;
 * 13.5 that is really 13.499999 compounds over 240 months into a repayment that does not match the bank's
 * own quote, and the buyer is holding the one that is wrong.
 */
@Entity
@Table(name = "mortgage_products")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MortgageProduct {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "institution_id", nullable = false) private Long institutionId;
    /** Label cache: a marketplace panel names the bank without joining. One writer, in the institution service. */
    @Column(name = "institution_name", length = 255) private String institutionName;

    @Column(nullable = false, length = 16) private String reference;
    @Column(nullable = false, length = 160) private String name;
    @Column(columnDefinition = "TEXT") private String description;

    @Column(name = "product_type", nullable = false, length = 24)
    @Builder.Default private String productType = AppConstant.PRODUCT_MORTGAGE;

    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";

    @Column(name = "min_amount", precision = 15, scale = 2) private BigDecimal minAmount;
    @Column(name = "max_amount", precision = 15, scale = 2) private BigDecimal maxAmount;
    @Column(name = "min_term_months", nullable = false) @Builder.Default private Short minTermMonths = 12;
    @Column(name = "max_term_months", nullable = false) @Builder.Default private Short maxTermMonths = 240;

    @Column(name = "interest_rate", nullable = false, precision = 6, scale = 3) private BigDecimal interestRate;
    @Column(name = "rate_type", nullable = false, length = 16)
    @Builder.Default private String rateType = AppConstant.RATE_FIXED;

    @Column(name = "max_ltv_percent", nullable = false, precision = 5, scale = 2)
    @Builder.Default private BigDecimal maxLtvPercent = new BigDecimal("90.00");
    @Column(name = "min_deposit_percent", nullable = false, precision = 5, scale = 2)
    @Builder.Default private BigDecimal minDepositPercent = new BigDecimal("10.00");

    @Column(name = "processing_fee_percent", nullable = false, precision = 5, scale = 2)
    @Builder.Default private BigDecimal processingFeePercent = BigDecimal.ZERO;
    @Column(name = "insurance_percent", nullable = false, precision = 5, scale = 2)
    @Builder.Default private BigDecimal insurancePercent = BigDecimal.ZERO;
    @Column(name = "other_fees_note", columnDefinition = "TEXT") private String otherFeesNote;

    /**
     * The least a household may take home each month and still be considered.
     *
     * <p>Take-home, not gross: the affordability calculator asks applicants for the figure that reaches
     * their account, and a floor quoted in gross would be compared against a smaller number and refuse
     * people it should not.
     */
    @Column(name = "min_monthly_income", precision = 15, scale = 2) private BigDecimal minMonthlyIncome;
    @Column(name = "max_dti_percent", precision = 5, scale = 2) private BigDecimal maxDtiPercent;
    @Column(name = "eligibility_notes", columnDefinition = "TEXT") private String eligibilityNotes;
    @Column(name = "required_documents", columnDefinition = "TEXT") private String requiredDocuments;

    @Column(nullable = false) @Builder.Default private boolean published = false;
    @Column(name = "published_at") private OffsetDateTime publishedAt;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;
    @Column(name = "deactivation_reason", columnDefinition = "TEXT") private String deactivationReason;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;

    /** On offer to the public: published, and not switched off or archived. */
    public boolean isOnOffer() {
        return published && AppConstant.isLive(status);
    }

    /**
     * The deposit this product requires on a given price, in money.
     *
     * <p>The larger of the two constraints wins. Banks quote a minimum deposit and a maximum
     * loan-to-value, and the two are only equivalent when they agree — a product with a 10% minimum deposit
     * and an 85% LTV ceiling requires 15%, and telling a buyer 10% would be quoting them a loan the same
     * product would refuse.
     */
    public BigDecimal depositOn(BigDecimal price) {
        if (price == null) return BigDecimal.ZERO;
        BigDecimal byDeposit = price.multiply(minDepositPercent)
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        BigDecimal byLtv = price.multiply(new BigDecimal("100").subtract(maxLtvPercent))
                .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
        return byDeposit.max(byLtv);
    }
}
