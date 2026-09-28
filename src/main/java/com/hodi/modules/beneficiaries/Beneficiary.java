package com.hodi.modules.beneficiaries;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Somebody a development pays: a supplier, a contractor, the county.
 *
 * <p>Owned by the organisation that pays them — a seller or a bank — and reusable across that owner's
 * developments. A cost line used to carry a free-text payee; this is the record the payee becomes, so a
 * statement can say <em>who</em> was paid and <em>what kind</em> of payee they are.
 *
 * <h2>Confirmed before it can be paid</h2>
 *
 * <p>{@link #confirmedName} is what the bank says the account is held in. A beneficiary is {@code VERIFIED}
 * only when the bank has answered with a name, and only a verified one can be paid: the name a checker approves
 * a payment against is the bank's, never the one somebody typed. An account the bank could not confirm is kept,
 * marked {@code UNVERIFIED} with the bank's reason, and asked about again on request.
 *
 * <h2>Two pairs of eyes on where the money goes</h2>
 *
 * <p>Written at {@code STATUS_NEW} and live only once a second person approves — and back to NEW on any change
 * to the bank code or account number, because a changed account is how money is diverted. Contact details
 * change without ceremony.
 *
 * <p>Deactivated, never deleted: a payment keeps pointing at the beneficiary as it was when it was made.
 */
@Entity
@Table(name = "beneficiaries")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Beneficiary {

    public static final String VERIFIED = "VERIFIED";
    public static final String UNVERIFIED = "UNVERIFIED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Ours, "BN…", for the statement and the payment form. */
    @Column(nullable = false, unique = true, length = 16) private String reference;

    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "institution_id") private Long institutionId;

    @Column(name = "type_id", nullable = false) private Long typeId;
    @Column(nullable = false, length = 160) private String name;
    @Column(name = "kra_pin", length = 16) private String kraPin;
    @Column(name = "contact_name", length = 120) private String contactName;
    @Column(name = "contact_phone", length = 32) private String contactPhone;
    @Column(name = "contact_email", length = 160) private String contactEmail;

    @Column(name = "bank_code", nullable = false, length = 8) private String bankCode;
    @Column(name = "account_no", nullable = false, length = 32) private String accountNo;

    @Column(nullable = false, length = 12) @Builder.Default private String verification = UNVERIFIED;
    @Column(name = "confirmed_name", length = 160) private String confirmedName;
    @Column(name = "confirmed_at") private OffsetDateTime confirmedAt;
    @Column(name = "verification_note", columnDefinition = "TEXT") private String verificationNote;

    @Column(columnDefinition = "TEXT") private String notes;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_NEW;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_NEW;

    @Column(name = "search_text", insertable = false, updatable = false) private String searchText;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    public boolean isVerified() { return VERIFIED.equals(verification); }

    /**
     * Approved and in use. Not {@code AppConstant.isLive}, which counts a row still waiting for its second
     * person as live; here that row is exactly the one that must not be paid.
     */
    public boolean isLive() {
        return status != null && (status == AppConstant.STATUS_ACTIVE || status == AppConstant.STATUS_EDITED);
    }

    /** Live and confirmed with the bank — the only state a payment may name. */
    public boolean isPayable() { return isLive() && isVerified(); }

    public boolean belongsTo(Long tenant, Long institution) {
        return (tenant != null && Objects.equals(tenant, tenantId))
                || (institution != null && Objects.equals(institution, institutionId));
    }
}
