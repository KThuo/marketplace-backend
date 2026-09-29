package com.hodi.modules.notifications;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** One thing the platform can say: who it is for, whether it is on, where it goes, and the words. */
@Entity
@Table(name = "notification_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationEvent {

    @Id @Column(length = 40) private String code;
    @Column(nullable = false, length = 16) private String audience;
    @Column(nullable = false, length = 32) private String purpose;
    @Column(nullable = false) @Builder.Default private boolean enabled = true;
    @Column(nullable = false, length = 32) @Builder.Default private String channels = "EMAIL,SMS,IN_APP";
    @Column(nullable = false, length = 255) private String subject;
    @Column(nullable = false, columnDefinition = "TEXT") private String line;
    @Column(nullable = false, length = 255) private String description;
    @Column(nullable = false, length = 255) @Builder.Default private String placeholders = "";
    @Column(nullable = false) @Builder.Default private boolean composed = false;
    @Column(name = "sort_order", nullable = false) @Builder.Default private Integer sortOrder = 0;
    @Column(name = "updated_at", nullable = false) @Builder.Default private OffsetDateTime updatedAt = OffsetDateTime.now();
    @Column(name = "updated_by", length = 64) private String updatedBy;
}
