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
 * One stage of a build, in time and in money.
 *
 * <h2>Two pairs of dates, never one pair overwritten</h2>
 *
 * <p>{@link #plannedCompletionOn} is the original promise and does not move. {@link #revisedCompletionOn} is
 * what is now expected and does. {@link #actualCompletionOn} is what happened. "It slipped four months" is the
 * first question a lender asks about a project they financed, and a single column that has been overwritten
 * cannot answer it — which is the whole reason there are three.
 *
 * <h2>No owner column</h2>
 *
 * <p>Deliberately no {@code tenantId} or {@code institutionId}. A development's owner may be an institution, so
 * a bare tenant id would be null for exactly the bank's own projects and any tenant-scoped query would filter
 * them out of their owner's view. Access to a phase is access to its development, checked once on the parent.
 */
@Entity
@Table(name = "development_phases")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DevelopmentPhase {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "development_id", nullable = false) private Long developmentId;
    @Column(nullable = false, unique = true, length = 16) private String reference;

    @Column(nullable = false, length = 160) private String name;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(name = "sequence_no", nullable = false) @Builder.Default private short sequenceNo = 1;

    @Column(name = "planned_start_on") private LocalDate plannedStartOn;
    @Column(name = "planned_completion_on") private LocalDate plannedCompletionOn;
    @Column(name = "revised_completion_on") private LocalDate revisedCompletionOn;
    @Column(name = "actual_start_on") private LocalDate actualStartOn;
    @Column(name = "actual_completion_on") private LocalDate actualCompletionOn;

    @Column(name = "budget_amount", precision = 15, scale = 2) private BigDecimal budgetAmount;
    /**
     * What this phase was expected to have cost by the time it finished.
     *
     * <p>Separate from {@link #budgetAmount}, which is what it was allowed to cost. The pair is what makes a
     * planned-versus-actual spend curve possible; with one of them it is a single line.
     */
    @Column(name = "planned_spend", precision = 15, scale = 2) private BigDecimal plannedSpend;
    @Column(name = "committed_amount", precision = 15, scale = 2) private BigDecimal committedAmount;
    @Column(name = "spent_amount", precision = 15, scale = 2) private BigDecimal spentAmount;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";

    /** This phase's share of the whole project. When every phase has one they must sum to 100. */
    @Column(name = "weight_pct") private Short weightPct;

    @Column(name = "percent_complete", nullable = false) @Builder.Default private short percentComplete = 0;
    @Column(name = "milestone_code", length = 32) private String milestoneCode;
    @Column(name = "planned_unit_count") private Integer plannedUnitCount;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;
    @Column(name = "deactivation_reason", columnDefinition = "TEXT") private String deactivationReason;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    /** Finished. The database enforces that this and a 100% figure agree, so either may be asked. */
    public boolean isComplete() { return actualCompletionOn != null; }

    /**
     * How late the phase is against its original promise, in days. Negative is early, null when there is
     * nothing to compare — no promise, or nothing has happened yet.
     *
     * <p>Measured against the *planned* date rather than the revised one on purpose: a forecast that has been
     * moved twice always looks on time, which is exactly the reading a slippage figure exists to prevent.
     */
    public Long slippageDays() {
        if (plannedCompletionOn == null) return null;
        LocalDate against = actualCompletionOn != null ? actualCompletionOn : revisedCompletionOn;
        if (against == null) return null;
        return against.toEpochDay() - plannedCompletionOn.toEpochDay();
    }
}
