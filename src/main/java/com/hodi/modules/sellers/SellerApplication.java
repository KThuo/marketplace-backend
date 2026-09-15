package com.hodi.modules.sellers;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * Somebody asking to sell property through the bank.
 *
 * <p>The application, not the seller. Once it is approved a {@code Tenant} exists and that is the seller
 * from then on; this row stays as the record of how they got there — who checked what, when, and on what
 * basis — which is the question a regulator asks and a tenant row cannot answer.
 *
 * <p>The applicant has a real account from the moment they apply, because they finish the application
 * signed in. That grants nothing: {@link #tenantId} is null until approval so there is nowhere to put a
 * listing, and {@code EffectivePermissionResolver} withholds every listing verb while the profile's
 * {@code kyc_status} is not cleared.
 */
@Entity
@Table(name = "seller_applications")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SellerApplication {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32) private String reference;

    @Column(name = "user_id", nullable = false)    private Long userId;
    @Column(name = "profile_id", nullable = false) private Long profileId;
    /** Null until approved. This is the thing being asked for. */
    @Column(name = "tenant_id") private Long tenantId;

    @Column(name = "full_name", nullable = false, length = 160) private String fullName;
    @Column(nullable = false, length = 128) private String email;
    @Column(length = 32)  private String phone;
    @Column(name = "id_number", length = 64) private String idNumber;
    @Column(name = "kra_pin", length = 32)   private String kraPin;

    @Column(name = "identity_source", nullable = false, length = 24)
    @Builder.Default private String identitySource = SellerState.SOURCE_SELF;
    @Column(name = "coop_account_number", length = 34) private String coopAccountNumber;
    @Column(name = "coop_account_verified", nullable = false)
    @Builder.Default private boolean coopAccountVerified = false;

    @Column(name = "organisation_name", length = 160) private String organisationName;
    @Column(name = "seller_type", length = 32)        private String sellerType;
    @Column(name = "registration_number", length = 64) private String registrationNumber;
    @Column(length = 64)  private String county;
    @Column(length = 64)  private String town;
    @Column(name = "address_line", length = 255) private String addressLine;

    @Column(nullable = false, length = 16) @Builder.Default private String state = SellerState.DRAFT;
    @Column(name = "submitted_at") private OffsetDateTime submittedAt;
    @Column(name = "decided_at")   private OffsetDateTime decidedAt;
    @Column(name = "decided_by_user_id") private Long decidedByUserId;
    @Column(name = "decision_note", columnDefinition = "TEXT") private String decisionNote;

    @Column(nullable = false) @Builder.Default private Integer status = 1;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = "Active";

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;

    /** Still the applicant's to change: before submission, or after the bank asked for something. */
    public boolean isOpenToApplicant() {
        return SellerState.DRAFT.equals(state) || SellerState.MORE_INFO.equals(state);
    }

    public boolean isAwaitingDecision() {
        return SellerState.SUBMITTED.equals(state);
    }

    /** What the organisation will be called. Falls back to the person, for a sole trader who named none. */
    public String tradingName() {
        return organisationName == null || organisationName.isBlank() ? fullName : organisationName;
    }
}
