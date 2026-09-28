package com.hodi.modules.agents;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * An account an agent is paid into.
 *
 * <p>On the agent, not in the beneficiary register: an agent is paid as themselves, whichever development's
 * buyer they brought. {@link #confirmedName} is what the bank says the account is held in; an account is
 * {@code VERIFIED} only while the bank has confirmed it, and only a verified account is paid to. Several per
 * agent, one default — the one a settlement proposes unless somebody picks another.
 */
@Entity
@Table(name = "agent_payout_accounts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AgentPayoutAccount {

    public static final String VERIFIED = "VERIFIED";
    public static final String UNVERIFIED = "UNVERIFIED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "agent_profile_id", nullable = false) private Long agentProfileId;
    @Column(name = "bank_code", nullable = false, length = 4) private String bankCode;
    @Column(name = "account_no", nullable = false, length = 32) private String accountNo;
    /** As the agent typed it. What the bank says is {@link #confirmedName}. */
    @Column(name = "holder_name", length = 160) private String holderName;

    @Column(nullable = false, length = 12) @Builder.Default private String verification = UNVERIFIED;
    @Column(name = "confirmed_name", length = 160) private String confirmedName;
    @Column(name = "confirmed_at") private OffsetDateTime confirmedAt;
    @Column(name = "verification_note", columnDefinition = "TEXT") private String verificationNote;
    @Column(name = "is_default", nullable = false) @Builder.Default private boolean defaultAccount = false;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    public boolean isVerified() { return VERIFIED.equals(verification); }
    public boolean isLive() { return status != null && status != AppConstant.STATUS_DELETED; }
}
