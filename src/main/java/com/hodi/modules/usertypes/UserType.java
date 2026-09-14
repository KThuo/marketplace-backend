package com.hodi.modules.usertypes;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * A class of user, global across every organisation (plan section 4.2). Neither a seller nor the bank can
 * create one — only the platform.
 *
 * <p>Which modules a type may reach is declared on the module, not here — see
 * {@code app_modules.allowed_user_types}. Keeping it there rather than on a join table from this side is
 * what lets the super admin answer "who can reach credit decisioning" by editing one field.
 */
@Entity
@Table(name = "user_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserType {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Which of the four populations this type belongs to — {@code PLATFORM}, {@code SELLER},
     * {@code BUYER}, {@code VALUER}, {@code AGENT} or {@code VENDOR}.
     *
     * <p>Treated as part of the type's identity: the seeder reconciles names and descriptions but never
     * moves a type between classes, and the API refuses to. Changing it would silently reclassify every user
     * holding it, including which organisation column they are supposed to carry.
     */
    @Column(name = "actor_class", nullable = false, length = 16)
    private String actorClass;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

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

    public boolean isPlatformLevel() {
        return AppConstant.ACTOR_PLATFORM.equals(actorClass);
    }
}
