package com.hodi.modules.configurations;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * A record of one configuration change, at either layer.
 *
 * <p>Append-only, with no soft-lifecycle columns: a change log entry is a statement about the past, and a
 * past that can be deactivated is not much of a log. Both the previous and new values are kept, because
 * "what was it before" is the question actually asked when a setting turns out to have broken something.
 *
 * <p>Secret values are stored here <strong>masked</strong>, never in the clear. The log's job is to say that
 * the gateway key changed, who changed it and why — not to become a second, unencrypted copy of every
 * credential the platform has ever held.
 */
@Entity
@Table(name = "configuration_logs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConfigurationLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, length = 128) private String configKey;

    /** {@code GLOBAL} or {@code TENANT} — which layer was written. */
    @Column(nullable = false, length = 16) private String scope;

    /** Null for a global change. */
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "tenant_name", length = 255) private String tenantName;

    @Column(name = "previous_value", columnDefinition = "TEXT") private String previousValue;
    @Column(name = "new_value", columnDefinition = "TEXT") private String newValue;

    @Column(columnDefinition = "TEXT") private String reason;

    @Column(name = "actor_username", nullable = false, length = 64) private String actorUsername;
    @Column(name = "actor_ip", length = 64) private String actorIp;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
