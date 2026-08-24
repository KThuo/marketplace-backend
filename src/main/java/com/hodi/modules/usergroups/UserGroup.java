package com.hodi.modules.usergroups;

import com.hodi.modules.permissions.Permission;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A role: an arbitrary bundle of permissions (plan section 4.2, access axis 2).
 *
 * <p><strong>Three flavours, told apart by the two organisation columns:</strong>
 *
 * <ul>
 *   <li>both null + {@link #template} — a seeded global role template. Every organisation sees it and can
 *       clone it. Editing the template does <em>not</em> reach back into existing clones; a clone is
 *       independent from the moment it is made, which is what makes cloning safe to offer.
 *   <li>both null without {@link #template} — a platform role.
 *   <li>{@link #tenantId} set — owned by one seller organisation, invisible to every other.
 *   <li>{@link #institutionId} set — owned by one lending institution, invisible to every other.
 * </ul>
 *
 * <p>A database CHECK enforces that never both are set. The group is bound to one user type, which is what
 * makes the permission picker self-filtering: only modules whose {@code allowed_user_types} include that
 * type are offered.
 */
@Entity
@Table(name = "user_groups")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserGroup {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "user_type_id", nullable = false)
    private Long userTypeId;

    @Column(name = "user_type_code", nullable = false, length = 32)
    private String userTypeCode;

    @Column(name = "user_type_name", length = 64)
    private String userTypeName;

    /** NULL = platform-owned (template or platform role). */
    @Column(name = "tenant_id")
    private Long tenantId;

    @Column(name = "institution_id")
    private Long institutionId;

    @Column(name = "is_template", nullable = false)
    @Builder.Default
    private boolean template = false;

    /**
     * A system group carries the full permission set for its side and cannot be edited to the point of
     * locking an organisation out of itself. {@code UserGroupService} refuses the edit that would leave an
     * organisation with no active member of its system group.
     */
    @Column(name = "is_system", nullable = false)
    @Builder.Default
    private boolean system = false;

    /**
     * Eager because every authenticated request resolves the holder's permissions; a lazy collection here
     * means an extra query per request on the hottest path in the application.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_group_permissions",
            joinColumns = @JoinColumn(name = "user_group_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    @Builder.Default
    private Set<Permission> permissions = new LinkedHashSet<>();

    @Column(nullable = false)
    @Builder.Default
    private Integer status = 1;

    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default
    private String statusFlag = "Active";

    @Column(name = "deactivation_reason", columnDefinition = "TEXT")
    private String deactivationReason;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;

    /** True when no organisation owns this group — a global template or a platform role. */
    public boolean isGlobal() {
        return tenantId == null && institutionId == null;
    }
}
