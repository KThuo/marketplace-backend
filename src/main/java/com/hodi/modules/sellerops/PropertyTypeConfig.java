package com.hodi.modules.sellerops;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * A kind of property, and which questions a listing of that kind should be asked (M13).
 *
 * <p>Replaces six values that lived in a Java enum, three Vue arrays and a form's validation. Adding
 * "godown" used to mean a deploy in two repositories; the fields the form asked for were decided by a switch
 * nobody could see.
 */
@Entity
@Table(name = "property_type_configs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PropertyTypeConfig {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32) private String code;
    @Column(nullable = false, length = 120) private String name;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(length = 8) private String icon;
    @Column(name = "sort_order", nullable = false) @Builder.Default private Integer sortOrder = 100;

    @Column(name = "has_bedrooms", nullable = false) @Builder.Default private boolean hasBedrooms = true;
    @Column(name = "has_bathrooms", nullable = false) @Builder.Default private boolean hasBathrooms = true;
    @Column(name = "has_floor_area", nullable = false) @Builder.Default private boolean hasFloorArea = true;
    @Column(name = "has_plot_area", nullable = false) @Builder.Default private boolean hasPlotArea = false;
    @Column(name = "has_year_built", nullable = false) @Builder.Default private boolean hasYearBuilt = true;
    @Column(nullable = false) @Builder.Default private boolean lettable = true;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;
}
