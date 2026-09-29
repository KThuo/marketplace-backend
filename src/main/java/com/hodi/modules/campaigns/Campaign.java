package com.hodi.modules.campaigns;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A campaign: who it is for, the words, and where it stands (notifications plan §3.6).
 *
 * <p>Written as a draft, submitted to a checker, approved with a time, sent in batches inside the window,
 * or cancelled. The audience is fixed when it is approved — a row per recipient in {@code campaign_sends} —
 * so the number the approver saw is the number that is sent to.
 */
@Entity
@Table(name = "campaigns")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Campaign {

    public static final String DRAFT = "DRAFT";
    public static final String SUBMITTED = "SUBMITTED";
    public static final String APPROVED = "APPROVED";
    public static final String SENDING = "SENDING";
    public static final String SENT = "SENT";
    public static final String CANCELLED = "CANCELLED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 16) private String reference;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "institution_id") private Long institutionId;
    @Column(nullable = false, length = 160) private String title;
    @Column(nullable = false, length = 16) @Builder.Default private String audience = "BUYERS";
    @Column(length = 64) private String county;
    @Column(name = "registered_since") private LocalDate registeredSince;
    @Column(nullable = false, length = 32) @Builder.Default private String channels = "EMAIL,IN_APP";
    @Column(nullable = false, length = 255) private String subject;
    @Column(nullable = false, columnDefinition = "TEXT") private String body;
    @Column(name = "cta_label", length = 80) private String ctaLabel;
    @Column(name = "cta_path", length = 255) private String ctaPath;
    @Column(nullable = false, length = 12) @Builder.Default private String state = DRAFT;
    @Column(name = "scheduled_for") private OffsetDateTime scheduledFor;
    @Column(name = "audience_count") private Integer audienceCount;
    @Column(name = "sent_count", nullable = false) @Builder.Default private Integer sentCount = 0;
    @Column(name = "failed_count", nullable = false) @Builder.Default private Integer failedCount = 0;
    @Column(name = "skipped_count", nullable = false) @Builder.Default private Integer skippedCount = 0;
    @Column(name = "started_at") private OffsetDateTime startedAt;
    @Column(name = "finished_at") private OffsetDateTime finishedAt;
    @Column(name = "approved_by", length = 64) private String approvedBy;
    @Column(name = "approved_at") private OffsetDateTime approvedAt;
    @Column(name = "cancel_reason", columnDefinition = "TEXT") private String cancelReason;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32) @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;
    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    public boolean isEditable() { return DRAFT.equals(state); }
    public boolean isPlatformOwned() { return tenantId == null && institutionId == null; }
}
