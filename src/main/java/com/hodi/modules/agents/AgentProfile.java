package com.hodi.modules.agents;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * An independent property agent (M9, BRD FR160–FR161).
 *
 * <p>A person who registers themselves, signs the platform's terms, and — once approved — becomes their own
 * one-person selling organisation. {@link #tenantId} is null until that happens, which is what makes an
 * unapproved agent structurally unable to list rather than merely un-permitted: there is nowhere to put a
 * listing.
 *
 * <p>Not to be confused with the {@code SALES_AGENT} user type, which is a seller organisation's employee.
 */
@Entity
@Table(name = "agent_profiles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AgentProfile {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "profile_id", nullable = false) private Long profileId;

    /** Their own organisation. Set at approval, never before. */
    @Column(name = "tenant_id") private Long tenantId;

    @Column(name = "full_name", nullable = false, length = 160) private String fullName;
    @Column(length = 128) private String email;
    @Column(length = 32) private String phone;

    @Column(name = "self_employed", nullable = false) @Builder.Default private boolean selfEmployed = true;
    @Column(name = "agency_name", length = 255) private String agencyName;

    @Column(name = "id_number", length = 64) private String idNumber;
    @Column(name = "licence_number", length = 64) private String licenceNumber;
    @Column(name = "licence_expires_on") private LocalDate licenceExpiresOn;

    @Column(columnDefinition = "TEXT") private String counties;
    @Column(columnDefinition = "TEXT") private String bio;

    @Column(nullable = false, length = 16) @Builder.Default private String state = AgentState.PENDING;
    @Column(name = "decided_at") private OffsetDateTime decidedAt;
    @Column(name = "decided_by_user_id") private Long decidedByUserId;
    @Column(name = "decision_note", columnDefinition = "TEXT") private String decisionNote;

    @Column(name = "signature_id") private Long signatureId;
    @Column(name = "agreement_id") private Long agreementId;

    @Column(name = "listings_count", nullable = false) @Builder.Default private Integer listingsCount = 0;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false) private String searchText;

    /** Approved and not suspended — the only state in which listings are theirs to make. */
    public boolean isWorking() {
        return AgentState.APPROVED.equals(state) && AppConstant.isLive(status);
    }

    public boolean isPending() {
        return AgentState.PENDING.equals(state);
    }

    /**
     * How they should be named on an agreement and in the register.
     *
     * <p>A self-employed agent trades under their own name; anybody else trades under the agency's, and
     * putting the person's name on a contract their agency is party to would name the wrong party.
     */
    public String tradingName() {
        return selfEmployed || agencyName == null || agencyName.isBlank() ? fullName : agencyName;
    }

    /** Whether a named licence is still in date. No licence recorded is not the same as a lapsed one. */
    public boolean hasCurrentLicence() {
        if (licenceNumber == null || licenceNumber.isBlank()) return false;
        return licenceExpiresOn == null || !licenceExpiresOn.isBefore(LocalDate.now());
    }
}
