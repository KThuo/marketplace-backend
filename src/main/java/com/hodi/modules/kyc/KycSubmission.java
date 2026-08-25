package com.hodi.modules.kyc;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * One seller's pack, and Compliance's answer to it (plan §3.3).
 *
 * <p>Keyed on the <em>profile</em> rather than the tenant, because {@code user_profiles.kyc_status} is what
 * {@code EffectivePermissionResolver} reads on every login — and the profile is where the actor lives. When
 * a pack is approved, the decision writes that status onto every live seller profile of the organisation, so
 * a colleague who never touched the pack still gets the listing permissions it unlocked.
 *
 * <p>{@link #requirementVersion} is frozen at submission. A requirement list that changed retroactively
 * would make a past approval impossible to explain.
 */
@Entity
@Table(name = "kyc_submissions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class KycSubmission {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;

    @Column(name = "profile_id", nullable = false) private Long profileId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "tenant_name", length = 255) private String tenantName;

    @Column(name = "entity_type", nullable = false, length = 32) private String entityType;
    @Column(name = "requirement_version", nullable = false) @Builder.Default private Integer requirementVersion = 1;

    @Column(nullable = false, length = 16)
    @Builder.Default private String state = AppConstant.KYC_SUB_DRAFT;
    @Column(name = "submitted_at") private OffsetDateTime submittedAt;
    @Column(name = "decided_at") private OffsetDateTime decidedAt;
    @Column(name = "decided_by_user_id") private Long decidedByUserId;
    @Column(name = "decision_note", columnDefinition = "TEXT") private String decisionNote;

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

    /** Still the seller's to change. */
    public boolean isEditable() {
        return AppConstant.KYC_SUB_DRAFT.equals(state) || AppConstant.KYC_SUB_MORE_INFO.equals(state);
    }
}
