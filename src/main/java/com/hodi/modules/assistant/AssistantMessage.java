package com.hodi.modules.assistant;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/**
 * One line of a conversation (M11).
 *
 * <p>{@link #intent} and {@link #payload} record what the assistant decided the question was about and what
 * it found. Kept because "why did it answer that" is the question anybody debugging this will ask, and a
 * transcript without the reasoning is a transcript nobody can act on.
 */
@Entity
@Table(name = "assistant_messages")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AssistantMessage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", nullable = false) private Long conversationId;

    @Column(nullable = false, length = 16) private String side;
    @Column(nullable = false, columnDefinition = "TEXT") private String body;

    @Column(length = 32) private String intent;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb") private String payload;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
}
