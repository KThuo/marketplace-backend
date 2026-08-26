package com.hodi.modules.sellerops;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** What the platform sells: a placement for a number of days at a price (M13). */
@Entity
@Table(name = "promotion_packages")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PromotionPackage {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;
    @Column(nullable = false, length = 32) private String code;
    @Column(nullable = false, length = 120) private String name;
    @Column(columnDefinition = "TEXT") private String description;

    /** What the placement does, so search acts on this rather than on the package's name. */
    @Column(nullable = false, length = 24) private String placement;
    @Column(name = "duration_days", nullable = false) private Integer durationDays;
    @Column(nullable = false, precision = 15, scale = 2) private BigDecimal price;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";

    /** How hard it lifts a listing. A number rather than an order, so two packages can coexist. */
    @Column(nullable = false) @Builder.Default private Integer boost = 10;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;
}
