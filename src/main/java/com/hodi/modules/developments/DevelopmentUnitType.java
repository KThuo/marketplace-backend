package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * A typology: "two bedroom, 92 m², from KES 9.5M, seventy of them".
 *
 * <p>This is the product a buyer chooses between, and the thing that gets listed on the marketplace. The unit
 * beside it is what one buyer ends up owning. The distinction is the whole reason a development needs three
 * tables rather than one: a plan is a plan, a typology is a price, and a unit is a door.
 *
 * <p>Bedrooms may legitimately be zero — that is what a studio has, and null would mean "not asked". Whether
 * the form asks at all is decided by the property type's own {@code has_bedrooms} flag, exactly as the listing
 * form already does.
 */
@Entity
@Table(name = "development_unit_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DevelopmentUnitType {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "development_id", nullable = false) private Long developmentId;
    @Column(nullable = false, unique = true, length = 16) private String reference;

    /** The developer's own name for it — "TYPE A", "2BED-CORNER". Unique within the development. */
    @Column(nullable = false, length = 32) private String code;
    @Column(nullable = false, length = 160) private String name;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(name = "property_type", nullable = false, length = 32) private String propertyType;

    @Column private Short bedrooms;
    @Column private Short bathrooms;
    @Column(name = "parking_spaces") private Short parkingSpaces;
    @Column(name = "floor_area_sqm", precision = 10, scale = 2) private BigDecimal floorAreaSqm;
    @Column(name = "balcony_area_sqm", precision = 10, scale = 2) private BigDecimal balconyAreaSqm;

    @Column(name = "list_price", precision = 15, scale = 2) private BigDecimal listPrice;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";
    @Column(name = "service_charge", precision = 15, scale = 2) private BigDecimal serviceCharge;

    @Column(name = "planned_unit_count", nullable = false) @Builder.Default private int plannedUnitCount = 0;

    @Column(name = "units_total", nullable = false) @Builder.Default private int unitsTotal = 0;
    @Column(name = "units_available", nullable = false) @Builder.Default private int unitsAvailable = 0;
    @Column(name = "units_reserved", nullable = false) @Builder.Default private int unitsReserved = 0;
    @Column(name = "units_sold", nullable = false) @Builder.Default private int unitsSold = 0;
    @Column(name = "from_price", precision = 15, scale = 2) private BigDecimal fromPrice;
    @Column(name = "construction_status", nullable = false, length = 24)
    @Builder.Default private String constructionStatus = AppConstant.BUILD_PLANNED;

    @Column(name = "floor_plan_key", length = 512) private String floorPlanKey;
    @Column(name = "primary_image_key", length = 512) private String primaryImageKey;
    @Column(name = "sort_order", nullable = false) @Builder.Default private int sortOrder = 100;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;
    @Column(name = "deactivation_reason", columnDefinition = "TEXT") private String deactivationReason;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    /** Nothing left to sell. What turns the typology's listing SOLD and takes it off the marketplace. */
    public boolean isSoldOut() { return unitsTotal > 0 && unitsAvailable == 0; }
}
