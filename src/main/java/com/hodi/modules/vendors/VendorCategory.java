package com.hodi.modules.vendors;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * A kind of service a vendor offers (M10).
 *
 * <p>A table rather than a CHECK, unlike seller types: the list is expected to grow as the platform learns
 * what buyers ask for, and growing it should not be a deploy.
 */
@Entity
@Table(name = "vendor_categories")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VendorCategory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32) private String code;
    @Column(nullable = false, length = 120) private String name;
    @Column(columnDefinition = "TEXT") private String description;
    /** An emoji. A taxonomy this small does not justify an upload pipeline. */
    @Column(length = 8) private String icon;
    @Column(name = "sort_order", nullable = false) @Builder.Default private Integer sortOrder = 100;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;
}
