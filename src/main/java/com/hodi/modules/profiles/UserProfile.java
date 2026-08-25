package com.hodi.modules.profiles;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * What one person <em>is</em>, in one organisation.
 *
 * <p>The row that used to be a set of columns on {@code users}. A person is a credential — name, address,
 * password, second factor, lockout — and a profile is an actor: a kind of user, a group of permissions, and
 * at most one organisation. Splitting them is what lets the same natural person be a buyer and an approved
 * seller at once (BRD FR073), which the previous shape made impossible: {@code users.email} is unique and
 * there was exactly one place to record which actor somebody was.
 *
 * <p><strong>This is the row authorisation is resolved from.</strong> {@code EffectivePermissionResolver}
 * reads the group and user type here, {@code PrincipalFactory} reads the organisation here, and the access
 * token names the active profile. The person no longer answers any of those questions.
 *
 * <p>At most one organisation, enforced by a database CHECK exactly as it was on {@code users}: a seller
 * profile carries a tenant, a lender profile an institution, platform and buyer profiles neither. And as
 * before, {@link #profileType} is stored rather than inferred from those two columns — "both null" describes
 * a platform administrator and a house-hunter, and the inference that confuses them is the one that hands a
 * buyer the platform.
 *
 * <p>The {@code *_name} columns are label caches (the denormalisation convention): a list of people renders
 * their organisation and role without joining four tables, and each cache has exactly one writer — the
 * service that owns the renamed thing.
 */
@Entity
@Table(name = "user_profiles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserProfile {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false) private Long userId;

    /**
     * The same relationship again, read-only, so a query can join to the person.
     *
     * <p>The id above stays the writable one — every service works in ids, and an entity graph that has to be
     * loaded to save a row is a query nobody asked for. This association exists solely so the users list can
     * filter and search on the person's own columns (name, email, locked) in one statement instead of loading
     * profiles and then filtering in the JVM.
     *
     * <p>{@code insertable = false, updatable = false} is what keeps the two from fighting over the column.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", insertable = false, updatable = false)
    private com.hodi.modules.users.User user;

    /** The actor class: PLATFORM, SELLER, LENDER or BUYER. Copied from the user type. */
    @Column(name = "profile_type", nullable = false, length = 16) private String profileType;

    @Column(name = "user_type_id", nullable = false) private Long userTypeId;
    @Column(name = "user_type_code", nullable = false, length = 32) private String userTypeCode;
    @Column(name = "user_type_name", length = 64) private String userTypeName;

    @Column(name = "user_group_id") private Long userGroupId;
    @Column(name = "user_group_name", length = 128) private String userGroupName;

    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "tenant_name", length = 255) private String tenantName;

    @Column(name = "institution_id") private Long institutionId;
    @Column(name = "institution_name", length = 255) private String institutionName;

    /**
     * Where this profile stands with Compliance.
     *
     * <p>Nothing writes anything but {@code NOT_REQUIRED} yet — seller KYC is M8 (plan §3.3). It is here now
     * because the gate that consumes it lives in {@code EffectivePermissionResolver}, and a permission gate
     * added later to a column added later is two migrations and a change to the hottest path in the
     * application; this way M8 only has to start writing the value.
     */
    @Column(name = "kyc_status", nullable = false, length = 16)
    @Builder.Default private String kycStatus = AppConstant.KYC_NOT_REQUIRED;

    @Column(name = "kyc_decided_at") private OffsetDateTime kycDecidedAt;

    /** Where a fresh login lands. Exactly one per person, enforced by a partial unique index. */
    @Column(name = "is_default", nullable = false) @Builder.Default private boolean defaultProfile = false;

    @Column(nullable = false) @Builder.Default private Integer status = 1;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;
    @Column(name = "deactivation_reason", columnDefinition = "TEXT") private String deactivationReason;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    /** Generated by Postgres over the label columns, backed by a pg_trgm GIN index. Read-only here. */
    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;

    public boolean isPlatformActor() {
        return AppConstant.ACTOR_PLATFORM.equals(profileType);
    }

    public boolean isSellerActor() {
        return AppConstant.ACTOR_SELLER.equals(profileType);
    }

    public boolean isLenderActor() {
        return AppConstant.ACTOR_LENDER.equals(profileType);
    }

    public boolean isBuyerActor() {
        return AppConstant.ACTOR_BUYER.equals(profileType);
    }

    /** A valuer on the platform's panel. Carries no organisation; scoped by assignment (plan §3.5). */
    public boolean isValuerActor() {
        return AppConstant.ACTOR_VALUER.equals(profileType);
    }

    /** True when Compliance has cleared this profile, or never needed to. */
    public boolean isKycCleared() {
        return AppConstant.KYC_NOT_REQUIRED.equals(kycStatus)
                || AppConstant.KYC_APPROVED.equals(kycStatus);
    }

    /** The organisation this profile belongs to, in one string — the "scope" column on a list. */
    public String organisationLabel() {
        if (tenantName != null) return tenantName;
        if (institutionName != null) return institutionName;
        if (isBuyerActor()) return "Buyer";
        if (isValuerActor()) return "Valuer";
        return "Platform";
    }

    /** Which idle window governs a session on this profile (plan section 5). */
    public String sessionClass() {
        return isBuyerActor() ? AppConstant.SESSION_CLASS_BUYER : AppConstant.SESSION_CLASS_ADMIN;
    }
}
