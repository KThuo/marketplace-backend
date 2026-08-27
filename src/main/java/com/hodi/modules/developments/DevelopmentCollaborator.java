package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * A right the owner of a development granted to another organisation.
 *
 * <p>The case this exists for: a bank finances a developer's project, owns the record because the exposure is
 * theirs, and lets the developer post the progress — they are the ones on site. Without a grant the developer
 * would have to be handed the whole record, and the bank would have to choose between tracking a project it
 * cannot see updated and giving away ownership of its own file.
 *
 * <p>A grant rather than a second owner. The principal on {@link Development} stays the single answer to
 * "whose record is this"; this row answers "who else may write to it, and what". It is also the only thing in
 * this module that widens anybody's visibility, which is why revoking is a timestamp and a reason rather than
 * a delete — "who could post progress last March" is a question an audit asks.
 */
@Entity
@Table(name = "development_collaborators")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DevelopmentCollaborator {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "development_id", nullable = false) private Long developmentId;

    /**
     * The organisation being granted the right. A tenant, never an institution: a lender owning a project
     * needs no grant to write to it, and one lender writing another's project is not a case that exists.
     */
    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "tenant_name", length = 255) private String tenantName;

    @Column(nullable = false, length = 24)
    @Builder.Default private String rights = AppConstant.COLLAB_PROGRESS_WRITE;

    @Column(name = "granted_by_user_id") private Long grantedByUserId;
    @Column(name = "granted_by_name", length = 160) private String grantedByName;
    @Column(name = "granted_at", nullable = false) @Builder.Default
    private OffsetDateTime grantedAt = OffsetDateTime.now();
    @Column(name = "revoked_by_user_id") private Long revokedByUserId;
    @Column(name = "revoked_at") private OffsetDateTime revokedAt;
    @Column(name = "revoked_reason", columnDefinition = "TEXT") private String revokedReason;
    @Column(columnDefinition = "TEXT") private String note;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    /**
     * Still in force. Revoked, deactivated and archived are all "no", and a caller should not have to check
     * three things — {@code AppConstant.isLive} already answers the last two for every row in the schema.
     */
    public boolean isLive() {
        return revokedAt == null && AppConstant.isLive(status);
    }

    /** May this grant write progress? FULL includes it; that is what FULL means. */
    public boolean mayWriteProgress() {
        return isLive() && (AppConstant.COLLAB_PROGRESS_WRITE.equals(rights)
                || AppConstant.COLLAB_FULL.equals(rights));
    }

    /** May this grant change the unit inventory? Never implied by PROGRESS_WRITE. */
    public boolean mayWriteUnits() {
        return isLive() && (AppConstant.COLLAB_UNITS_WRITE.equals(rights)
                || AppConstant.COLLAB_FULL.equals(rights));
    }
}
