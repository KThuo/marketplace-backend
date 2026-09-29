package com.hodi.modules.notifications;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** One reminder: how many days before or after the thing, whether it repeats, and whether it is on. */
@Entity
@Table(name = "reminder_rules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReminderRule {

    @Id @Column(length = 40) private String code;
    @Column(nullable = false, length = 255) private String description;
    @Column(nullable = false) @Builder.Default private boolean enabled = true;
    @Column(nullable = false) private Integer days;
    @Column(name = "repeat_every_days") private Integer repeatEveryDays;
    @Column(name = "sort_order", nullable = false) @Builder.Default private Integer sortOrder = 0;
    @Column(name = "updated_at", nullable = false) @Builder.Default private OffsetDateTime updatedAt = OffsetDateTime.now();
    @Column(name = "updated_by", length = 64) private String updatedBy;
}
