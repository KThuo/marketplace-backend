package com.hodi.modules.beneficiaries;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * What kind of payee a beneficiary is: a supplier, a contractor, a consultant, the county.
 *
 * <p>Configuration rather than an enum, the way cost categories are: ten are seeded and the bank adds, renames
 * and suspends them without a deploy. A suspended type is no longer offered on the form; the beneficiaries
 * already filed under it keep their name. The type is what the money-out statement groups by, which is why
 * it is a list shared by every owner rather than a word each one types.
 */
@Entity
@Table(name = "beneficiary_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BeneficiaryType {

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
