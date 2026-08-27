package com.hodi.modules.progress;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * A named stage of a build.
 *
 * <p>These eight lived in {@code ProgressEditor.vue} as a {@code <datalist>} over a free-text column, which
 * meant nobody could group a timeline by stage and a redeploy was needed to add one. The same move
 * {@code property_type_configs} already made for property types.
 *
 * <p>{@link #typicalPercent} is offered when somebody picks a milestone and never written over a figure they
 * typed. A site can be at roofing and 40% complete, and correcting them would be reporting our estimate as
 * their measurement.
 */
@Entity
@Table(name = "progress_milestone_configs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProgressMilestone {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32) private String code;
    @Column(nullable = false, length = 120) private String name;
    @Column(columnDefinition = "TEXT") private String description;

    @Column(name = "typical_percent") private Short typicalPercent;

    /** Build order, which is neither alphabetical nor the order rows were added. */
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
}
