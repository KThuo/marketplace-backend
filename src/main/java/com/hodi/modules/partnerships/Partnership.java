package com.hodi.modules.partnerships;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * An agreement that one lending institution may see one seller organisation's portfolio.
 *
 * <p><strong>This table is the whole of a lender's read access.</strong> Nothing else widens it: a lender's
 * staff resolve to the set of tenant ids with an active row here for their institution, and to nothing
 * otherwise. That makes the security question a readable one — "who may see this seller's data" is a
 * {@code SELECT} against one table rather than a walk through permissions.
 *
 * <p>Because it is that, the lifecycle is deliberately explicit rather than a bare boolean. A partnership is
 * <em>proposed</em> by one side and <em>approved</em> by the other, and both facts are kept with who did them
 * and when: an approval is the moment somebody decided another organisation's staff may read this one's rows,
 * and that is not a fact to overwrite. Revocation is likewise recorded rather than deleted — a row that
 * vanishes cannot answer "who used to have access, and until when".
 *
 * <p>{@link #approvedAt} being null is what makes a proposal inert. {@link #isActive()} is the single
 * predicate every read goes through, and {@code PartnershipRepository.findActiveTenantIdsForInstitution}
 * is its SQL twin — the two must agree, and there is a test that says so.
 */
@Entity
@Table(name = "tenant_lender_partnerships")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Partnership {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "institution_id", nullable = false) private Long institutionId;

    /** Label caches, so the list screens on all three sides render without joining. */
    @Column(name = "tenant_name", length = 255) private String tenantName;
    @Column(name = "institution_name", length = 255) private String institutionName;

    /**
     * {@code FULL} or {@code SELECTED}. Only {@code FULL} is written until listings exist — the column is
     * here so the eventual per-listing join is additive rather than a migration of live grants
     * (plan section 12, question 3).
     */
    @Column(name = "portfolio_scope", nullable = false, length = 16)
    @Builder.Default
    private String portfolioScope = AppConstant.PORTFOLIO_FULL;

    /** Which side proposed it — {@code SELLER}, {@code LENDER} or {@code PLATFORM}. */
    @Column(name = "requested_by_side", nullable = false, length = 16) private String requestedBySide;
    @Column(name = "requested_by_user_id") private Long requestedByUserId;
    @Column(name = "requested_at") private OffsetDateTime requestedAt;

    @Column(name = "approved_by_user_id") private Long approvedByUserId;
    @Column(name = "approved_at") private OffsetDateTime approvedAt;

    @Column(name = "revoked_by_user_id") private Long revokedByUserId;
    @Column(name = "revoked_at") private OffsetDateTime revokedAt;
    @Column(name = "revoke_reason", columnDefinition = "TEXT") private String revokeReason;

    @Column(nullable = false) @Builder.Default private Integer status = 1;
    @Column(name = "status_flag", nullable = false, length = 32) @Builder.Default private String statusFlag = "Active";

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;

    /**
     * Whether this partnership currently grants anything.
     *
     * <p>Three conditions, all necessary: approved, not revoked, and the row still in force. A proposal
     * grants nothing, a revoked partnership grants nothing, and an archived row grants nothing.
     */
    public boolean isActive() {
        return approvedAt != null && revokedAt == null && AppConstant.isLive(status);
    }

    public boolean isPending() {
        return approvedAt == null && revokedAt == null && AppConstant.isLive(status);
    }
}
