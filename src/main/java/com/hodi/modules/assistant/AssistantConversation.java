package com.hodi.modules.assistant;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
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
    @Column(name = "user_id", nullable = false) private Long userId;

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
