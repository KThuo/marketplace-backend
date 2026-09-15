package com.hodi.modules.sellers;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * One run of one check against one application.
 *
 * <p>A row per attempt rather than columns on the application, because the honest answer to "what did AML
 * say" is "zero or more attempts, each with its own moment" — a check re-run after a name correction is a
 * second row, not an overwrite of the first.
 *
 * <p>Written even when the check could not run. A reviewer looking at an application with no AML line
 * cannot tell whether it passed, failed, or was never attempted, and the first of those is the dangerous
 * reading.
 */
@Entity
@Table(name = "seller_identity_checks")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SellerIdentityCheck {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false) private Long applicationId;

    @Column(name = "check_code", nullable = false, length = 24) private String checkCode;
    @Column(nullable = false, length = 32) private String verdict;
    @Column(name = "provider_ref", length = 128) private String providerRef;
    @Column(columnDefinition = "TEXT") private String detail;
    @Column(name = "ran_at", nullable = false) @Builder.Default
    private OffsetDateTime ranAt = OffsetDateTime.now();

    @Column(nullable = false) @Builder.Default private Integer status = 1;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = "Active";

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;
}
