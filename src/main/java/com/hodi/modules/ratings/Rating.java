package com.hodi.modules.ratings;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * What somebody thought of a property, a seller, an agent, a vendor or one thing a vendor sells (M7).
 *
 * <p>One entity for all five, discriminated by {@link #subjectType}. See the migration for why that is one
 * table rather than three.
 */
@Entity
@Table(name = "ratings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Rating {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;

    @Column(name = "subject_type", nullable = false, length = 24) private String subjectType;
    @Column(name = "subject_id", nullable = false) private Long subjectId;
    @Column(name = "subject_ref", length = 16) private String subjectRef;
    @Column(name = "subject_label", length = 255) private String subjectLabel;
    /**
     * The organisation the subject belongs to, resolved once at write time.
     *
     * <p>Denormalised so that "reviews about us" is one indexed predicate rather than a switch over five
     * repositories per row.
     */
    @Column(name = "subject_tenant_id") private Long subjectTenantId;

    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "rater_name", length = 160) private String raterName;

    @Column(nullable = false) private Short score;
    @Column(length = 180) private String title;
    @Column(columnDefinition = "TEXT") private String body;

    /** Derived from what the platform can see, never claimed by the rater. */
    @Column(nullable = false) @Builder.Default private boolean verified = false;
    @Column(name = "verified_via", length = 32) private String verifiedVia;

    @Column(nullable = false, length = 16) @Builder.Default private String state = RatingSubject.PUBLISHED;
    @Column(name = "held_reason", columnDefinition = "TEXT") private String heldReason;
    @Column(name = "moderated_at") private OffsetDateTime moderatedAt;
    @Column(name = "moderated_by_user_id") private Long moderatedByUserId;
    @Column(name = "moderation_note", columnDefinition = "TEXT") private String moderationNote;

    @Column(name = "reply_body", columnDefinition = "TEXT") private String replyBody;
    @Column(name = "replied_at") private OffsetDateTime repliedAt;
    @Column(name = "replied_by_user_id") private Long repliedByUserId;

    @Column(name = "report_count", nullable = false) @Builder.Default private Integer reportCount = 0;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false) private String searchText;

    /** Whether it counts towards the subject's average. Held and hidden ones do not. */
    public boolean isCounted() {
        return RatingSubject.PUBLISHED.equals(state) && AppConstant.isLive(status);
    }

    public boolean needsModeration() {
        return RatingSubject.HELD.equals(state) || reportCount > 0;
    }
}
