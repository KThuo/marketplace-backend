package com.hodi.modules.configurations;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * One seller organisation's override of one global configuration key (plan section 6).
 *
 * <p>Sparse by design: a row exists <em>only</em> where the organisation has deliberately shadowed a global
 * value. Clearing an override deletes the row rather than writing the global value into it — copying the
 * global value down would freeze it, so a later platform-wide change would silently stop reaching this
 * organisation.
 *
 * <p>Uniqueness is on {@code (tenant_id, config_key)} rather than on the key alone. Under axis's
 * schema-per-tenant the key alone was enough because the table itself was per-tenant; here every
 * organisation's overrides share one table, and a unique index on the key alone would let the first seller to
 * override a key stop every other seller from doing so.
 */
@Entity
@Table(name = "tenant_configurations",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_tenant_config", columnNames = {"tenant_id", "config_key"}))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TenantConfiguration {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "config_key", nullable = false, length = 128)
    private String configKey;

    @Column(name = "config_value", columnDefinition = "TEXT")
    private String configValue;

    @Column(name = "value_type", nullable = false, length = 16)
    @Builder.Default
    private String valueType = "STRING";

    @Column(nullable = false, length = 64)
    @Builder.Default
    private String category = "GENERAL";

    /**
     * Mirrors the global row's flag. Duplicated rather than joined because it decides whether the value is
     * encrypted, and the decryption path must not depend on a second lookup to find out.
     */
    @Column(name = "is_secret", nullable = false)
    @Builder.Default
    private boolean secret = false;

    @Column(nullable = false) @Builder.Default private Integer status = 1;
    @Column(name = "status_flag", nullable = false, length = 32) @Builder.Default private String statusFlag = "Active";

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;
}
