package com.hodi.modules.notifications;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** One line in a person's inbox: what happened, and where to look. */
@Entity
@Table(name = "notifications")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Notification {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "event_code", nullable = false, length = 40) private String eventCode;
    @Column(nullable = false, length = 255) private String title;
    @Column(nullable = false, columnDefinition = "TEXT") private String line;
    @Column(length = 255) private String link;
    @Column(name = "about_type", length = 32) private String aboutType;
    @Column(name = "about_id") private Long aboutId;
    @Column(name = "about_ref", length = 32) private String aboutRef;
    @Column(name = "read_at") private OffsetDateTime readAt;
    @Column(name = "created_at", nullable = false) @Builder.Default private OffsetDateTime createdAt = OffsetDateTime.now();
}
