package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * One thing a unit or a typology has.
 *
 * <p>Belongs to a unit or to a typology, never both. That single table is what makes the inheritance rule
 * expressible: a unit with any rows of its own has exactly those, and a unit with none inherits its
 * typology's. Two tables would have made the "this one, but without the open-plan kitchen" case a join across
 * two shapes.
 */
@Entity
@Table(name = "unit_features")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UnitFeature {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /*
     * Exactly one of the three is set, and which one says what the amenity describes.
     *
     * A development's borehole belongs to the estate; a typology's en-suite belongs to every flat of that
     * kind; a listing's own row belongs to that one home. Before this the development had nowhere to say
     * so, and ticking the gate on all ninety listings was the only way to record one gate.
     */
    @Column(name = "development_id") private Long developmentId;
    @Column(name = "unit_id") private Long unitId;
    @Column(name = "unit_type_id") private Long unitTypeId;
    @Column(name = "feature_code", nullable = false, length = 48) private String featureCode;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @Column(name = "created_by", length = 64) private String createdBy;
}
