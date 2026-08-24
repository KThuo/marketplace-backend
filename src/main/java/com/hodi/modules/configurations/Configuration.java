package com.hodi.modules.configurations;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * A global system configuration entry (plan section 6). One row per key; the value is read at runtime so
 * operators tune behaviour without a redeploy.
 *
 * <p>{@link #overridable} is the gate on tenant overrides: only a small, deliberate set of keys —
 * integration credentials a seller genuinely owns — may be shadowed by a row in
 * {@code tenant_configurations}. Platform policy keys (session windows, password rules, buyer verification)
 * are never overridable, because an organisation weakening its own controls defeats them.
 */
@Entity
@Table(name = "configurations")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Configuration {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", nullable = false, unique = true, length = 128)
    private String configKey;

    @Column(name = "config_value", columnDefinition = "TEXT")
    private String configValue;

    @Column(name = "value_type", nullable = false, length = 16)
    @Builder.Default
    private String valueType = "STRING";

    @Column(nullable = false, length = 64)
    @Builder.Default
    private String category = "GENERAL";

    @Column(length = 255) private String label;
    @Column(columnDefinition = "TEXT") private String description;

    /** Encrypted at rest and masked in responses and logs. */
    @Column(name = "is_secret", nullable = false)
    @Builder.Default
    private boolean secret = false;

    @Column(name = "is_overridable", nullable = false)
    @Builder.Default
    private boolean overridable = false;

    @Column(nullable = false) @Builder.Default private boolean editable = true;

    @Column(nullable = false) @Builder.Default private Integer status = 1;
    @Column(name = "status_flag", nullable = false, length = 32) @Builder.Default private String statusFlag = "Active";

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;
}
