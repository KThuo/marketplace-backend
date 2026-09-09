package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * What a development's costs are filed under.
 *
 * <p>Configuration rather than an enum, the way progress milestones and property types are: eight are
 * seeded, and the platform adds, renames and suspends them without a deploy. A suspended category is no
 * longer offered on the form; the lines already filed under it keep their name.
 */
@Entity
@Table(name = "development_cost_categories")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DevelopmentCostCategory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32) private String code;
    @Column(nullable = false, length = 120) private String name;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(name = "sort_order", nullable = false) @Builder.Default private Integer sortOrder = 100;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    public boolean isLive() { return AppConstant.isLive(status); }
}
