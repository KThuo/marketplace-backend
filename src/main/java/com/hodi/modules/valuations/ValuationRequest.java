package com.hodi.modules.valuations;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A valuation job (M5).
 *
 * <p>Raised by a seller wanting a figure for a listing, or by the bank who will not lend without one.
 * Exactly one of {@link #tenantId} and {@link #institutionId} is set, and which one decides who may read the
 * report — "a seller asked" and "a bank asked" are different answers to that question.
 *
 * <p>{@link #valuerProfileId} is the column §3.5's visibility rule turns on: a valuer's every list is
 * filtered to rows where this is their own profile.
 */
@Entity
@Table(name = "valuation_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ValuationRequest {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;

    @Column(name = "property_id", nullable = false) private Long propertyId;
    @Column(name = "property_reference", nullable = false, length = 16) private String propertyReference;
    @Column(name = "property_title", length = 255) private String propertyTitle;
    @Column(name = "property_price", precision = 15, scale = 2) private BigDecimal propertyPrice;
    @Column(length = 64) private String county;

    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "tenant_name", length = 255) private String tenantName;
    @Column(name = "institution_id") private Long institutionId;
    @Column(name = "institution_name", length = 255) private String institutionName;

    @Column(nullable = false, length = 24)
    @Builder.Default private String purpose = AppConstant.VALUATION_FOR_SALE;
    @Column(nullable = false, length = 16)
    @Builder.Default private String state = AppConstant.VALUATION_REQUESTED;

    @Column(name = "valuer_profile_id") private Long valuerProfileId;
    @Column(name = "valuer_name", length = 160) private String valuerName;
    @Column(name = "assigned_at") private OffsetDateTime assignedAt;
    @Column(name = "assigned_by_user_id") private Long assignedByUserId;
    @Column(name = "assignment_method", length = 16) private String assignmentMethod;

    @Column(name = "due_on") private LocalDate dueOn;
    @Column(name = "fee_amount", precision = 15, scale = 2) private BigDecimal feeAmount;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";
    @Column(name = "requester_note", columnDefinition = "TEXT") private String requesterNote;

    @Column(name = "declined_reason", columnDefinition = "TEXT") private String declinedReason;
    @Column(name = "cancelled_reason", columnDefinition = "TEXT") private String cancelledReason;
    /** The report landed; awaiting the platform's review. */
    @Column(name = "submitted_at") private OffsetDateTime submittedAt;
    @Column(name = "reviewed_by", length = 64) private String reviewedBy;
    @Column(name = "reviewed_at") private OffsetDateTime reviewedAt;
    @Column(name = "review_note", columnDefinition = "TEXT") private String reviewNote;
    @Column(name = "completed_at") private OffsetDateTime completedAt;

    /** Two people assigning at once: the second save fails rather than both succeeding. */
    @Version @Column(nullable = false) private Integer version;

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

    /** Still to be done: somebody is expected to act on it. */
    public boolean isOpen() {
        return !AppConstant.VALUATION_COMPLETED.equals(state)
                && !AppConstant.VALUATION_CANCELLED.equals(state);
    }

    /** The report is in and awaiting the platform's review. */
    public boolean isAwaitingReview() {
        return AppConstant.VALUATION_SUBMITTED.equals(state);
    }

    /** Waiting for a valuer. Either never assigned, or the last one said no. */
    public boolean isUnassigned() {
        return AppConstant.VALUATION_REQUESTED.equals(state);
    }
}
