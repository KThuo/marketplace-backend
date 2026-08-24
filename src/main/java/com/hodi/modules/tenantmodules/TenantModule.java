package com.hodi.modules.tenantmodules;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * One seller organisation's enablement of one module — access axis (1) of plan section 4, seller side.
 *
 * <p>A row exists for every module a seller has switched on, core modules included: the seeder tops those up
 * at onboarding so that "enabled" is always a row rather than sometimes a row and sometimes an inference from
 * {@code app_modules.is_core}. Two ways to be enabled is one way too many —
 * {@code EffectivePermissionResolver} asks this table and nothing else.
 */
@Entity
@Table(name = "tenant_modules")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TenantModule {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "app_module_id", nullable = false) private Long appModuleId;

    /** Label cache. The resolver matches module codes, so keeping it here saves a join per request. */
    @Column(name = "module_code", nullable = false, length = 64) private String moduleCode;
    @Column(name = "module_name", length = 128) private String moduleName;

    @Column(name = "enabled_at") private OffsetDateTime enabledAt;
    @Column(name = "disabled_at") private OffsetDateTime disabledAt;

    @Column(nullable = false) @Builder.Default private Integer status = 1;
    @Column(name = "status_flag", nullable = false, length = 32) @Builder.Default private String statusFlag = "Active";

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;
}
