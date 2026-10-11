package com.hodi.modules.assistant;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/** One conversation between a buyer and the assistant (M11). */
@Entity
@Table(name = "assistant_conversations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssistantConversation {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;
    /** Null while it belongs to a visitor; set when they sign in and claim it. */
    @Column(name = "user_id") private Long userId;

    /** SHA-256 of the visitor's own token. Cleared when the conversation is claimed. */
    @Column(name = "visitor_hash", length = 64) private String visitorHash;

    /** A keyed hash of the address a visitor asked from, for the hourly limit and nothing else. */
    @Column(name = "client_hash", length = 64) private String clientHash;

    /** What the conversation remembers: {@link AssistantContext} as JSON. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") private String context;

    /** Taken from the first thing they said, so a list of these reads as a list of questions. */
    @Column(length = 180) private String title;

    @Column(nullable = false, length = 16)
    @Builder.Default private String state = AssistantConstants.STATE_OPEN;
    @Column(name = "enquiry_ref", length = 16) private String enquiryRef;
    @Column(name = "handed_off_at") private OffsetDateTime handedOffAt;

    @Column(name = "message_count", nullable = false) @Builder.Default private Integer messageCount = 0;
    @Column(name = "last_message_at") private OffsetDateTime lastMessageAt;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    public boolean isOpen() {
        return AssistantConstants.STATE_OPEN.equals(state);
    }
}
