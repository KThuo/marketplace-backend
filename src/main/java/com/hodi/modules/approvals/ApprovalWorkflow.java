package com.hodi.modules.approvals;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * One thing waiting for a second person to agree with it.
 *
 * <p>Maker/Checker as a row rather than as a rule each module re-implements. A partnership proposal, a
 * listing waiting to be published, a seller's KYC pack, a promotion — all the same shape: somebody submitted
 * it, somebody else has to decide, and the two cannot be the same person.
 *
 * <p><strong>The rule lives in the database.</strong> {@code ck_approval_maker_checker} refuses a decision by
 * the submitter. The service guard exists for the readable message; the constraint is what makes the
 * guarantee hold for the modules that have not been written yet.
 *
 * <p>{@code entityType}/{@code entityId} are deliberately a loose reference rather than a foreign key: the
 * table serves types that do not exist yet, and one nullable FK column per module would be a schema change
 * per module. What keeps it honest is that every decision is applied through an {@link ApprovalHandler}
 * registered for that type — an unknown type has no handler, and a decision on it is refused rather than
 * silently recorded.
 */
@Entity
@Table(name = "approval_workflows")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ApprovalWorkflow {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false, length = 64) private String entityType;
    @Column(name = "entity_id", nullable = false) private Long entityId;

    /** Which decision is being asked for — one entity can need several over its life. */
    @Column(nullable = false, length = 32) private String action;

    /** Whose queue this belongs in. At most one is set. */
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "institution_id") private Long institutionId;

    /** What a reviewer sees before opening anything. A label cache, so the queue needs no per-type join. */
    @Column(name = "subject_label", length = 255) private String subjectLabel;

    @Column(name = "submitted_by_user_id", nullable = false) private Long submittedByUserId;
    @Column(name = "submitted_by_username", length = 64) private String submittedByUsername;
    @Column(name = "submitted_at", nullable = false) private OffsetDateTime submittedAt;
    @Column(name = "submission_note", columnDefinition = "TEXT") private String submissionNote;

    @Column(name = "checked_by_user_id") private Long checkedByUserId;
    @Column(name = "checked_by_username", length = 64) private String checkedByUsername;
    @Column(name = "checked_at") private OffsetDateTime checkedAt;
    @Column(length = 16) private String decision;
    @Column(name = "decision_reason", columnDefinition = "TEXT") private String decisionReason;

    @Column(nullable = false, length = 16)
    @Builder.Default private String state = AppConstant.APPROVAL_PENDING;

    @Column(nullable = false) @Builder.Default private Integer status = 1;
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

    public boolean isPending() {
        return AppConstant.APPROVAL_PENDING.equals(state);
    }

    /** The organisation whose queue this sits in, whichever kind it is. For display and scoping. */
    public Long scopeId() {
        return tenantId != null ? tenantId : institutionId;
    }
}
