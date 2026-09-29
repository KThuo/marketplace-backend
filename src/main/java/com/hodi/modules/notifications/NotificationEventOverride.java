package com.hodi.modules.notifications;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** An organisation's own answer for one event: off, other channels, its own words. Null means the platform's. */
@Entity
@Table(name = "notification_event_overrides")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationEventOverride {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_code", nullable = false, length = 40) private String eventCode;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "institution_id") private Long institutionId;
    private Boolean enabled;
    @Column(length = 32) private String channels;
    @Column(length = 255) private String subject;
    @Column(columnDefinition = "TEXT") private String line;
    @Column(name = "updated_at", nullable = false) @Builder.Default private OffsetDateTime updatedAt = OffsetDateTime.now();
    @Column(name = "updated_by", length = 64) private String updatedBy;
}
