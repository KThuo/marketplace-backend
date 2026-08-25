package com.hodi.modules.leads;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One thing somebody said.
 *
 * <p>No setter for {@link #body} beyond construction and no update path anywhere: a message is a record of
 * what was said, and an edited one is a different claim about a conversation the other party remembers.
 */
@Entity
@Table(name = "enquiry_messages")
@Getter @NoArgsConstructor @AllArgsConstructor @Builder
public class EnquiryMessage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ticket_id", nullable = false) private Long ticketId;

    /** {@code BUYER}, {@code SELLER} or {@code PLATFORM}. Stored, never inferred from the author's profile. */
    @Column(name = "author_side", nullable = false, length = 16) private String authorSide;
    @Column(name = "author_user_id") private Long authorUserId;
    @Column(name = "author_name", length = 160) private String authorName;

    @Column(nullable = false, columnDefinition = "TEXT") private String body;

    @Column(name = "created_at", nullable = false)
    @Builder.Default private OffsetDateTime createdAt = OffsetDateTime.now();
}
