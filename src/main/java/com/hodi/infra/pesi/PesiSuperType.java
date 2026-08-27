package com.hodi.infra.pesi;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * What one Pesi code means, apart from which of our accounts it settles to.
 *
 * <p>The guide's two-tier model, and the reason to mirror it rather than hardcode {@code type} strings: adding
 * a till, a paybill or a provider code becomes a config row instead of a deploy.
 *
 * <p>{@link #category} is what decides how a code behaves — an IPN code is never called, only received, and
 * putting one on a "pay" form would be a form that can never work.
 */
@Entity
@Table(name = "pesi_super_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PesiSuperType {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Pesi's code, e.g. BUNI_IPN_TILL. Theirs, not translated. */
    @Column(nullable = false, unique = true, length = 48) private String code;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false, length = 24) private String category;

    @Column(name = "requires_phone", nullable = false) @Builder.Default private boolean requiresPhone = false;
    @Column(name = "requires_account_number", nullable = false)
    @Builder.Default private boolean requiresAccountNumber = false;

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
