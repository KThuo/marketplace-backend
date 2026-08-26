package com.hodi.modules.vendors;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * A business offering services to buyers and sellers (M10, BRD FR170).
 *
 * <p>The same shape as an agent, deliberately: apply, be approved, get an organisation. {@link #tenantId} is
 * null until approval, which is what makes an unapproved vendor structurally unable to publish a catalogue
 * rather than merely un-permitted.
 */
@Entity
@Table(name = "vendor_profiles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VendorProfile {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "profile_id", nullable = false) private Long profileId;
    @Column(name = "tenant_id") private Long tenantId;

    @Column(name = "business_name", nullable = false, length = 255) private String businessName;
    @Column(name = "contact_name", length = 160) private String contactName;
    @Column(length = 128) private String email;
    @Column(length = 32) private String phone;

    @Column(name = "category_id", nullable = false) private Long categoryId;
    @Column(name = "category_name", length = 120) private String categoryName;
    /** Cached beside the name, so a list does not resolve it per row. See the migration. */
    @Column(name = "category_code", length = 32) private String categoryCode;

    @Column(name = "registration_number", length = 64) private String registrationNumber;
    @Column(name = "kra_pin", length = 32) private String kraPin;
    @Column(columnDefinition = "TEXT") private String counties;
    @Column(columnDefinition = "TEXT") private String about;
    @Column(length = 255) private String website;
    @Column(name = "logo_key", length = 512) private String logoKey;

    @Column(nullable = false, length = 16) @Builder.Default private String state = VendorState.PENDING;
    @Column(name = "decided_at") private OffsetDateTime decidedAt;
    @Column(name = "decided_by_user_id") private Long decidedByUserId;
    @Column(name = "decision_note", columnDefinition = "TEXT") private String decisionNote;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false) private String searchText;

    public boolean isPending() {
        return VendorState.PENDING.equals(state);
    }

    /** Approved and live — the only state in which a catalogue item may be published. */
    public boolean isTrading() {
        return VendorState.APPROVED.equals(state) && AppConstant.isLive(status);
    }
}
