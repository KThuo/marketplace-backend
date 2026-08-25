package com.hodi.modules.kyc;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * One document one kind of seller must produce (plan §3.3).
 *
 * <p>Rows, not code: Compliance changes what a SACCO must show without a deploy. {@link #version} is what
 * makes that safe — a submission records the version it was judged against, so a list that changes today
 * does not make yesterday's approval unexplainable.
 */
@Entity
@Table(name = "kyc_requirement_configs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class KycRequirement {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entity_type", nullable = false, length = 32) private String entityType;
    @Column(nullable = false) @Builder.Default private Integer version = 1;

    @Column(name = "document_code", nullable = false, length = 48) private String documentCode;
    @Column(name = "document_name", nullable = false, length = 160) private String documentName;
    @Column(columnDefinition = "TEXT") private String description;
    @Column(nullable = false) @Builder.Default private boolean required = true;
    /** How long it stays valid once issued. Null means it does not lapse. */
    @Column(name = "validity_months") private Short validityMonths;
    @Column(name = "sort_order", nullable = false) @Builder.Default private Integer sortOrder = 0;

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
