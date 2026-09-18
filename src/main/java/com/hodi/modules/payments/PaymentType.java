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

    /**
     * Which fields one <em>account</em> of this channel needs, as the same kind of descriptor.
     *
     * <p>A different question from {@link #requiredConfigFields}, and asked of a different person: that one
     * is the channel's own wiring, set by the platform once — hosts, paths — while this is what an
     * organisation fills in per account. A Co-op phone prompt wants an operator code and a consumer key; a
     * Co-op biller wants nine fields, credentials in both directions among them.
     *
     * <p>Also carries {@code accountKey}, naming the fields that compose the code an inbound notification is
     * matched on, and {@code accountsLabel} for what one of them is called on screen.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "account_config_fields", columnDefinition = "jsonb")
    private Map<String, Object> accountConfigFields;

    /**
     * What this method is for, and therefore whether anybody may choose it.
     *
     * <p>{@code COLLECT} takes money and is the only kind offered when somebody picks how to pay.
     * {@code SEND} moves money out — a method, chosen deliberately, never offered to a payer.
     * {@code ENQUIRY} asks about a payment that already exists; it collects nothing and cannot be
     * chosen, because on its own it means nothing.
     *
     * <p>An enquiry is still a method in every other respect — it has an endpoint, set here like all the
     * others. Modelling it as anything else is what put a status check in a list of ways to pay.
     */
    @Column(nullable = false, length = 16) @Builder.Default private String kind = COLLECT;

    public static final String COLLECT = "COLLECT";
    public static final String SEND = "SEND";
    public static final String ENQUIRY = "ENQUIRY";

    /** Whether somebody may choose this as a way to be paid. Collecting only. */
    public boolean selectable() {
        return kind == null || COLLECT.equals(kind);
    }

    /**
     * Whether an account can be set up on this method.
     *
     * <p>Wider than {@link #selectable()}, and the difference matters: money going out needs an account
     * to go <em>from</em>, so a transfer method is configured like any other even though no payer will
     * ever be offered it. Only an enquiry needs nothing — it asks about somebody else's payment and has
     * no account of its own.
     */
    public boolean configurable() {
        return !ENQUIRY.equals(kind);
    }

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
