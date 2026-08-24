package com.hodi.modules.permissions;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * A single granted action, e.g. {@code USERS_CREATE}. {@code action_code} is the Spring Security authority,
 * so it appears verbatim in {@code @PreAuthorize}.
 *
 * <p>{@code moduleCode} / {@code moduleName} are label caches so permission lists and the group permission
 * picker render without joining.
 */
@Entity
@Table(name = "permissions")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Permission {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "action_code", nullable = false, unique = true, length = 64)
    private String actionCode;

    @Column(name = "action_name", nullable = false, length = 255)
    private String actionName;

    @Column(name = "app_module_id", nullable = false)
    private Long appModuleId;

    @Column(name = "module_code", nullable = false, length = 64)
    private String moduleCode;

    @Column(name = "module_name", length = 128)
    private String moduleName;

    /**
     * Never granted to an organisation by any automatic grant.
     *
     * <p>Derived from {@code AppPermissionEnum}, reconciled on every boot, and read by <strong>both</strong>
     * things that hand out permissions — the seeded role templates and the organisation system-group top-up
     * that gives an owner "everything". A rule living in only one of those is a rule the other will break,
     * which is why this is a column rather than a check in the seeder.
     */
    @Column(name = "platform_only", nullable = false)
    @Builder.Default
    private boolean platformOnly = false;

    @Column(nullable = false)
    @Builder.Default
    private Integer status = 1;

    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default
    private String statusFlag = "Active";

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;
}
