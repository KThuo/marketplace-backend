package com.hodi.modules.tenants;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * A seller organisation — a developer, an agency, or an individual property owner trading as one.
 * <strong>The only kind of tenant in Hodi.</strong>
 *
 * <p>Note what is absent compared with the axis original: no {@code schema_name}, no
 * {@code provision_status}, no {@code domain}. There is no schema to create, so there is no half-created
 * schema to guard against and no provisioning state machine to model; and there is no host to resolve,
 * because one marketplace serves every seller (plan section 11, deviations 1 and 2). Onboarding a seller is
 * an ordinary insert plus a module top-up, which is why it cannot half-fail.
 *
 * <p>{@link #onboardingStatus} is the business lifecycle and is separate from the soft {@code status}
 * lifecycle every table here has. They answer different questions: {@code status} is whether the row is in
 * force, {@code onboardingStatus} is whether the business may trade. A suspended seller is a live row whose
 * staff cannot sign in.
 */
@Entity
@Table(name = "tenants")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Tenant {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255) private String name;

    /** URL-safe handle. Unique, lower-case, and the local part of this seller's outbound email identity. */
    @Column(nullable = false, unique = true, length = 128) private String slug;

    /** Human-quotable reference, from {@code RrnGenerator} — what support asks for on the phone. */
    @Column(name = "tenant_ref", nullable = false, unique = true, length = 16) private String tenantRef;

    @Column(name = "seller_type", length = 32) private String sellerType;

    /**
     * What kind of organisation this is: a property seller, an agent's own one-person business, or a vendor.
     *
     * <p>They share a table because they share a visibility rule — {@code TenantScope} scopes all three the
     * same way — and they differ in what the platform does with them. Without this column the seller
     * administration screen offered partnerships and staff management to a one-person agency.
     */
    @Column(name = "organisation_kind", nullable = false, length = 16)
    @Builder.Default private String organisationKind = AppConstant.ORG_KIND_SELLER;

    @Column(name = "contact_name", length = 128) private String contactName;
    @Column(name = "contact_email", length = 128) private String contactEmail;
    @Column(name = "contact_phone", length = 32) private String contactPhone;

    @Column(length = 2) private String country;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";
    @Column(nullable = false, length = 64) @Builder.Default private String timezone = "Africa/Nairobi";

    @Column(name = "onboarding_status", nullable = false, length = 16)
    @Builder.Default
    private String onboardingStatus = AppConstant.ONBOARDING_PENDING;

    @Column(name = "activated_at") private OffsetDateTime activatedAt;
    @Column(name = "suspended_at") private OffsetDateTime suspendedAt;
    @Column(name = "suspension_reason", columnDefinition = "TEXT") private String suspensionReason;
    @Column(name = "terminated_at") private OffsetDateTime terminatedAt;

    @Column(nullable = false) @Builder.Default private Integer status = 1;
    @Column(name = "status_flag", nullable = false, length = 32) @Builder.Default private String statusFlag = "Active";
    @Column(name = "deactivation_reason", columnDefinition = "TEXT") private String deactivationReason;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;

    /** Whether this seller's staff may sign in and work. */
    public boolean isTradeable() {
        return AppConstant.ONBOARDING_ACTIVE.equals(onboardingStatus) && AppConstant.isLive(status);
    }

    public boolean isSuspended() {
        return AppConstant.ONBOARDING_SUSPENDED.equals(onboardingStatus);
    }

    public boolean isTerminated() {
        return AppConstant.ONBOARDING_TERMINATED.equals(onboardingStatus);
    }
}
