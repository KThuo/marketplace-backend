package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * One way money can be taken — the channel, not the account.
 *
 * <p>Platform-owned and arriving by migration. An organisation does not invent a channel; it configures one
 * with a {@link PaymentAccount}. There is deliberately no "create" for these: a row with no Pesi provider
 * behind it is a payment method that cannot collect anything, offered until somebody notices.
 *
 * <p>Everything about how a channel behaves derives from {@link #category}. The flags say what the
 * <em>form</em> must ask for, which is a different question and the reason they are stored rather than
 * derived.
 */
@Entity
@Table(name = "payment_types")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentType {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable across migrations, unlike the id. Configuration and screens refer to a channel by this. */
    @Column(nullable = false, unique = true, length = 40) private String code;
    @Column(nullable = false, length = 120) private String name;
    @Column(length = 250) private String description;
    /** Who runs the rails. Display only — there is no banks table here. */
    @Column(name = "provider_name", length = 120) private String providerName;

    /** Pesi's discriminator. Null for cash and cheque. */
    @Column(name = "pesi_provider_type", length = 50) private String pesiProviderType;

    /** One of {@code AppConstant.CHANNEL_*}. What the channel is, and therefore how it behaves. */
    @Column(nullable = false, length = 16) private String category;

    /** The coarse method a payment through this channel records — one of {@code AppConstant.PAY_*}. */
    @Column(nullable = false, length = 24) private String method;

    @Column(name = "is_electronic", nullable = false) @Builder.Default private boolean electronic = false;
    @Column(name = "is_account_based", nullable = false) @Builder.Default private boolean accountBased = false;
    @Column(name = "requires_short_code", nullable = false)
    @Builder.Default private boolean requiresShortCode = false;

    @Column(name = "sort_order", nullable = false) @Builder.Default private int sortOrder = 0;

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

    public PesiChannel.Category channelCategory() {
        return PesiChannel.Category.of(category);
    }

    /** Cash or cheque: recorded by hand, nothing to point it at. */
    public boolean isManual() {
        return channelCategory().isManual();
    }

    /** Whether an account must be configured before this channel can be offered. */
    public boolean needsAccount() {
        return accountBased && !isManual();
    }

    /** Switched on for the whole platform. INACTIVE means it cannot be assigned to anybody new. */
    public boolean isAvailable() {
        return status != null && (status == AppConstant.STATUS_ACTIVE || status == AppConstant.STATUS_EDITED);
    }
}
