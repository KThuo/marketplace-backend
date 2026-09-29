package com.hodi.modules.notifications;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** Said once: one row per rule, subject and key, with when it was last said and how many times. */
@Entity
@Table(name = "reminder_sent")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReminderSent {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_code", nullable = false, length = 40) private String ruleCode;
    @Column(name = "subject_type", nullable = false, length = 32) private String subjectType;
    @Column(name = "subject_id", nullable = false) private Long subjectId;
    @Column(name = "subject_key", nullable = false, length = 64) @Builder.Default private String subjectKey = "";
    @Column(name = "sent_at", nullable = false) @Builder.Default private OffsetDateTime sentAt = OffsetDateTime.now();
    @Column(nullable = false) @Builder.Default private Integer times = 1;
}
