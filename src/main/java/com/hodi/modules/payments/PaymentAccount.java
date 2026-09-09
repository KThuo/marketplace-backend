package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Where one organisation's money lands on one channel.
 *
 * <p>The paybill, the account number, the account name and the short code — what a bank needs to route a
 * payment, and what an organisation would otherwise write on a letter. Editing any of them changes where a
 * buyer's deposit goes, which is why every write takes a one-time code sent to the organisation itself.
 *
 * <h2>Whose it is</h2>
 *
 * <p>{@link #tenantId} or {@link #institutionId}, or neither for the platform's own. The same owner pair
 * every other row in this schema carries, and the reason a lending institution can collect on a development
 * it financed without being a tenant. Callers comparing an owner must use {@code Objects.equals} and treat
 * a null as "not this organisation's": an NPE is not a refusal.
 *
 * <h2>The account number is the inbound match key</h2>
 *
 * <p>A Pesi notification names the till it landed in, and {@code PesiIpnService} resolves it against
 * {@link #accountNo}. So the number is unique across every live row — two rows claiming one till would make
 * whose money it is depend on row order.
 */
@Entity
@Table(name = "payment_accounts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentAccount {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_type_id", nullable = false) private Long paymentTypeId;

    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "institution_id") private Long institutionId;

    /** One development, or null for every development the owner has. */
    @Column(name = "development_id") private Long developmentId;

    @Column(name = "pay_bill_no", length = 32) private String payBillNo;
    /** Null only for cash and cheque; the CHECK constraint says so. */
    @Column(name = "account_no", length = 64) private String accountNo;
    @Column(name = "account_name", length = 160) private String accountName;
    /** A secondary reference the bank may quote. Optional; unique when present. */
    @Column(name = "short_code", length = 64) private String shortCode;

    /** Copied from the catalogue row so an inbound match is one table and one index. */
    @Column(name = "pesi_type", length = 50) private String pesiType;
    @Column(nullable = false, length = 16) private String category;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @Column(name = "search_text", insertable = false, updatable = false) private String searchText;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    /** The platform's own, rather than an organisation's. */
    public boolean isPlatformOwned() {
        return tenantId == null && institutionId == null;
    }

    /** Whether this account covers every development the owner has rather than one. */
    public boolean isOwnerWide() {
        return developmentId == null;
    }

    /** Whether it reaches a given development: an owner-wide account reaches all of them. */
    public boolean reaches(Long developmentId) {
        return this.developmentId == null || Objects.equals(this.developmentId, developmentId);
    }

    /** Whether this is the account of the owner described by the pair. Null-safe on both sides. */
    public boolean belongsTo(Long tenantId, Long institutionId) {
        return Objects.equals(this.tenantId, tenantId) && Objects.equals(this.institutionId, institutionId);
    }

    public PesiChannel.Category channelCategory() {
        return PesiChannel.Category.of(category);
    }

    /** Live: offered to the receive form and matched by an inbound credit. Withdrawn rows are neither. */
    public boolean isLive() {
        return AppConstant.isLive(status);
    }

    /**
     * Copies the channel's identity from its catalogue row.
     *
     * <p>One place, called on every write, because {@code pesi_type} and {@code category} exist to be queried
     * and a copy that can drift from its source is worse than a join.
     */
    public void stampChannel(PaymentType type) {
        this.paymentTypeId = type.getId();
        this.pesiType = type.getPesiProviderType();
        this.category = type.getCategory();
    }
}
