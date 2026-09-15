package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * The vocabulary of things a home can have.
 *
 * <p>Data rather than columns, because nobody can enumerate this in advance and it differs by developer. A
 * column per feature would mean a migration every time a marketing team invents a selling point — the same
 * reason property types and build stages are tables here.
 */
@Entity
@Table(name = "unit_feature_configs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UnitFeatureConfig {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 48) private String code;
    @Column(nullable = false, length = 120) private String name;
    @Column(columnDefinition = "TEXT") private String description;
    /** A display heading — KITCHEN, OUTDOOR, FIXTURES. Free text: refusing a new one helps nobody. */
    @Column(length = 32) private String category;

    /**
     * A key into the client's icon set — {@code water}, {@code lift}, {@code cctv}.
     *
     * <p>A key rather than an uploaded image: there is nothing to store or resize, and every listing then
     * shows the same borehole. An unknown or null key renders a neutral fallback, so a key that reaches the
     * database before the client knows it degrades rather than breaks.
     */
    @Column(length = 40) private String icon;
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
