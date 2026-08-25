package com.hodi.modules.properties;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A post about how a build is going (M8, BRD FR087–FR089).
 *
 * <p>For off-plan and under-construction property, where the gap between listing and completion is measured
 * in years and a buyer who has paid a deposit wants to see the slab go down.
 *
 * <p>{@link #reportedOn} is when the work happened, not when the post was written — a developer catching up
 * on three months of photographs in one sitting should produce a timeline in the order of the work.
 *
 * <p>The photograph goes through the ordinary media store, not the vault. This is marketing, and the
 * distinction between the two stores is the point of having two.
 */
@Entity
@Table(name = "listing_progress_updates")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProgressUpdate {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "property_id", nullable = false) private Long propertyId;
    @Column(name = "tenant_id", nullable = false) private Long tenantId;

    @Column(nullable = false, length = 180) private String title;
    @Column(columnDefinition = "TEXT") private String body;

    @Column(name = "percent_complete") private Short percentComplete;
    @Column(length = 64) private String milestone;

    @Column(name = "reported_on", nullable = false)
    @Builder.Default private LocalDate reportedOn = LocalDate.now();

    @Column(name = "image_key", length = 512) private String imageKey;

    @Column(nullable = false) @Builder.Default private boolean published = false;
    @Column(name = "published_at") private OffsetDateTime publishedAt;

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
