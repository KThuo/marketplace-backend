package com.hodi.modules.notifications;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** An organisation's own answer for one rule. Null means the platform's. */
@Entity
@Table(name = "reminder_rule_overrides")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ReminderRuleOverride {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_code", nullable = false, length = 40) private String ruleCode;
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "institution_id") private Long institutionId;
    private Boolean enabled;
    private Integer days;
    @Column(name = "repeat_every_days") private Integer repeatEveryDays;
    @Column(name = "updated_at", nullable = false) @Builder.Default private OffsetDateTime updatedAt = OffsetDateTime.now();
    @Column(name = "updated_by", length = 64) private String updatedBy;
}
