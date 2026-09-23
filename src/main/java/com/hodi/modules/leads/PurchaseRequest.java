package com.hodi.modules.leads;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * An offer on a property (M4, BRD FR045–FR048).
 *
 * <p>An expression of intent, not a contract — conveyancing does not exist on this platform and no screen
 * implies otherwise. What it does is end the funnel: a seller learns that a named buyer, with a figure and
 * a way of paying it, wants this house.
 *
 * <p>{@link #affordabilityReference} and {@link #productReference} are references rather than foreign keys.
 * A buyer attaches the sums they did; neither the check nor the bank's product should become undeletable
 * because somebody once pointed at it, and both survive as text if they do go.
 */
@Entity
@Table(name = "purchase_requests")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PurchaseRequest {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;

    @Column(name = "tenant_id", nullable = false) private Long tenantId;
    @Column(name = "tenant_name", length = 255) private String tenantName;
    @Column(name = "property_id", nullable = false) private Long propertyId;
    @Column(name = "property_reference", nullable = false, length = 16) private String propertyReference;
    @Column(name = "property_title", length = 255) private String propertyTitle;
    /** What was being asked when the offer was made. An offer read later means nothing without it. */
    @Column(name = "asking_price", precision = 15, scale = 2) private BigDecimal askingPrice;

    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "buyer_name", length = 160) private String buyerName;
    @Column(name = "buyer_email", length = 128) private String buyerEmail;
    @Column(name = "buyer_phone", length = 32) private String buyerPhone;

    @Column(name = "offer_amount", nullable = false, precision = 15, scale = 2) private BigDecimal offerAmount;
    /** What the buyer first offered. Never changes; the thread tells the rest. */
    @Column(name = "original_amount", precision = 15, scale = 2) private BigDecimal originalAmount;
    /** The seller's counter the buyer has not yet answered, or null. */
    @Column(name = "counter_amount", precision = 15, scale = 2) private BigDecimal counterAmount;
    @Column(name = "counter_by", length = 16) private String counterBy;
    /** The figure the offer was accepted at. Set once, on acceptance. */
    @Column(name = "agreed_amount", precision = 15, scale = 2) private BigDecimal agreedAmount;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";
    @Column(nullable = false, length = 16)
    @Builder.Default private String financing = AppConstant.FINANCING_MORTGAGE;
    @Column(name = "affordability_reference", length = 16) private String affordabilityReference;
    @Column(name = "product_reference", length = 16) private String productReference;
    @Column(name = "deposit_available", precision = 15, scale = 2) private BigDecimal depositAvailable;
    @Column(name = "buyer_message", columnDefinition = "TEXT") private String buyerMessage;

    @Column(nullable = false, length = 16)
    @Builder.Default private String state = AppConstant.PURCHASE_SUBMITTED;
    @Column(name = "decided_by_user_id") private Long decidedByUserId;
    @Column(name = "decided_at") private OffsetDateTime decidedAt;
    @Column(name = "decision_note", columnDefinition = "TEXT") private String decisionNote;
    /** The booking this offer became, once accepted and converted. The reservation and the money live there. */
    @Column(name = "booking_id") private Long bookingId;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;

    /** Outstanding: nobody has answered it and the buyer has not taken it back. */
    /** The first figure is whatever the offer was made at, wherever the row is built from. */
    @jakarta.persistence.PrePersist
    void keepTheFirstFigure() {
        if (originalAmount == null) originalAmount = offerAmount;
    }

    public boolean isLive() {
        return AppConstant.PURCHASE_SUBMITTED.equals(state)
                || AppConstant.PURCHASE_UNDER_REVIEW.equals(state);
    }
}
