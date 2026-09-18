package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * One way money can be taken — the channel, not the account.
 *
 * <p>Platform-owned and arriving by migration. An organisation does not invent a channel; it configures one
 * with a {@link PaymentAccount}. There is deliberately no "create" for these: a row with no provider
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

    /** Co-op's discriminator. Null for cash and cheque. */
    @Column(name = "provider_type", length = 50) private String providerType;

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

    /**
     * Which fields this channel needs configuring, as a form descriptor.
     *
     * <p>Key, label, type and whether it is required — the screen renders from it and the adapter reads
     * the values by key. Nothing here knows what a Co-op token path <em>is</em>, which is the point:
     * adding a channel, or a field to one, is a row rather than a deploy.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_config_fields", columnDefinition = "jsonb")
    private Map<String, Object> requiredConfigFields;

    /**
     * The values for those fields, secrets encrypted.
     *
     * <p>Including the hosts and the paths. A URL compiled into the application has to be redeployed when
     * the bank moves it, opens a second environment or versions an endpoint — all three of which banks do
     * — so where the calls go is configuration, and only the credentials and the environment switch are
     * platform settings.
     *
     * <p>Every field whose descriptor says {@code "type":"password"} is stored through
     * {@code EncryptionUtil}, the same AES-256-GCM that protects a secret configuration value. A read
     * never returns one: see {@code PaymentTypeService.configOf}.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "search_text", insertable = false, updatable = false) private String searchText;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    public CoopChannel.Category channelCategory() {
        return CoopChannel.Category.of(category);
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
