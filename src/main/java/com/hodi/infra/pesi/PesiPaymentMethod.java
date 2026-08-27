package com.hodi.infra.pesi;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * One receiving account configured with Pesi, and whose money arrives in it.
 *
 * <p>The platform is one Pesi business with many tills, each assigned to whoever collects on it — the bank for
 * its own developments, a developer for theirs. An inbound notification carries {@code accountIdentifier},
 * which resolves to one of these, which resolves the owner. Nobody needs their own API key and the platform
 * never holds the money.
 *
 * <p>{@link #accountNumber} must match what was registered with Pesi exactly. A mismatch is a payment that
 * cannot be placed, which is why it is unique across live rows: two rows claiming one till would make the
 * owner depend on row order.
 */
@Entity
@Table(name = "pesi_payment_methods")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PesiPaymentMethod {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "super_type_id", nullable = false) private Long superTypeId;

    @Column(name = "account_number", nullable = false, length = 64) private String accountNumber;
    @Column(name = "account_name", nullable = false, length = 160) private String accountName;

    /** Exactly one, or neither for a platform-operated till. */
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "institution_id") private Long institutionId;

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
