package com.hodi.modules.leads;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One thing somebody said about a viewing or an offer.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>A viewing carried {@code sellerNote} and {@code outcomeNote}; an offer carried {@code decisionNote}.
 * One column each, and every decision overwrote the last — so a viewing rescheduled twice retained only
 * the second reason, and an offer countered twice only the final word. Read from the admin side that is
 * indistinguishable from the history not having been saved, because it was not.
 *
 * <h2>Polymorphic, like the enquiry thread is not</h2>
 *
 * <p>{@link EnquiryMessage} belongs to one parent and names it with a column. This belongs to one of two,
 * so it carries {@link #leadType} and no foreign key — the same trade {@code media_assets} makes, and for
 * the same reason: a constraint that can only name one of two parents enforces half a rule and hides the
 * other half. Two near-identical tables were the alternative, and the two histories are read the same way.
 *
 * <p>Immutable past construction, as the enquiry message is: a record of what was said, where an edited
 * one is a different claim about a conversation the other party remembers.
 */
@Entity
@Table(name = "lead_messages")
@Getter @NoArgsConstructor @AllArgsConstructor @Builder
public class LeadMessage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@link AppConstant#LEAD_SITE_VISIT} or {@link AppConstant#LEAD_PURCHASE_REQUEST}. */
    @Column(name = "lead_type", nullable = false, length = 24) private String leadType;
    @Column(name = "lead_id", nullable = false) private Long leadId;

    /** {@code BUYER}, {@code SELLER} or {@code PLATFORM}. Stored, never inferred from the author's profile. */
    @Column(name = "author_side", nullable = false, length = 16) private String authorSide;
    @Column(name = "author_user_id") private Long authorUserId;
    @Column(name = "author_name", length = 160) private String authorName;

    @Column(nullable = false, columnDefinition = "TEXT") private String body;

    /**
     * The state the lead moved to, when this message was the reason it moved. Null for a plain note.
     *
     * <p>Kept beside the text so the history reads as a sequence of decisions rather than as loose
     * remarks whose consequence has to be inferred from the timestamps around them.
     */
    @Column(name = "state_after", length = 32) private String stateAfter;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Column(name = "created_by", length = 64) private String createdBy;
}
