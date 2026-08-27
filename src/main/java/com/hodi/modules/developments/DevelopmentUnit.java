package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * One unit: a door, in a block, on a floor.
 *
 * <p>The inventory row. It is what makes "who bought B4-12" and "sixty of seventy still available" answerable,
 * and it is the only place in this schema where construction status is a fact about a physical thing rather
 * than a figure counted from other rows.
 *
 * <h2>The buyer may not have an account</h2>
 *
 * <p>{@link #buyerUserId} when they do, {@link #buyerName} and a phone number when they do not — the same
 * arrangement {@code properties.clientOwnerName} already uses, and for the same reason. A bank's off-plan
 * buyer from 2019 has no login here, and requiring one would mean the sale could not be recorded at all.
 *
 * <p>Those fields are personal data. They are absent from every generated search column, from every public
 * response record, and from the reporting views — a unit's availability is public, a unit's owner is not.
 *
 * <h2>The pay reference</h2>
 *
 * <p>{@link #payReference} is the short code a buyer quotes when paying. It lives here rather than on a
 * booking because it goes on the letter and is read down the phone long before any payment record exists, and
 * because it must survive a cancellation: a payment arriving after one has to land on the unit it names.
 */
@Entity
@Table(name = "development_units")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DevelopmentUnit {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "development_id", nullable = false) private Long developmentId;
    @Column(name = "unit_type_id", nullable = false) private Long unitTypeId;
    @Column(name = "phase_id") private Long phaseId;
    @Column(nullable = false, unique = true, length = 16) private String reference;

    /** Four characters from the ambiguity-free alphabet. Unique across every unit ever. */
    @Column(name = "pay_reference", unique = true, length = 8) private String payReference;

    /** What is painted on the door — "B4-12". Unique within the development. */
    @Column(name = "unit_label", nullable = false, length = 32) private String unitLabel;
    @Column(length = 32) private String block;
    @Column(name = "floor_no") private Short floorNo;
    @Column(name = "door_no", length = 16) private String doorNo;

    /** Overrides the typology's price where this one differs, which a top-floor corner usually does. */
    @Column(name = "list_price", precision = 15, scale = 2) private BigDecimal listPrice;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";

    @Column(name = "sale_state", nullable = false, length = 16)
    @Builder.Default private String saleState = AppConstant.UNIT_AVAILABLE;
    @Column(name = "construction_status", nullable = false, length = 24)
    @Builder.Default private String constructionStatus = AppConstant.BUILD_PLANNED;
    @Column(name = "completed_on") private LocalDate completedOn;
    @Column(name = "handed_over_on") private LocalDate handedOverOn;

    @Column(name = "buyer_user_id") private Long buyerUserId;
    @Column(name = "buyer_name", length = 160) private String buyerName;
    @Column(name = "buyer_phone", length = 32) private String buyerPhone;
    @Column(name = "buyer_email", length = 128) private String buyerEmail;
    /** Where the sale came from, when it came through the platform's own funnel. */
    @Column(name = "purchase_request_id") private Long purchaseRequestId;

    @Column(name = "reserved_at") private OffsetDateTime reservedAt;
    @Column(name = "reserved_until") private OffsetDateTime reservedUntil;
    @Column(name = "sold_price", precision = 15, scale = 2) private BigDecimal soldPrice;
    @Column(name = "sold_at") private OffsetDateTime soldAt;

    @Column(columnDefinition = "TEXT") private String notes;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;
    @Column(name = "deactivation_reason", columnDefinition = "TEXT") private String deactivationReason;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    // ── the three questions the inventory grid asks ────────────────────────────

    public boolean isAvailable() { return AppConstant.UNIT_AVAILABLE.equals(saleState); }
    public boolean isSold() { return AppConstant.UNIT_SOLD.equals(saleState); }

    /** Held or reserved — somebody has it for now. */
    public boolean isOnHold() {
        return AppConstant.UNIT_HELD.equals(saleState) || AppConstant.UNIT_RESERVED.equals(saleState);
    }

    /**
     * A hold whose deadline has passed.
     *
     * <p>Derived rather than swept into a stored state: nothing writes an EXPIRED sale state, so there is no
     * column that can be wrong while a nightly job is down. What a screen shows and what the data says are
     * the same sentence.
     */
    public boolean isHoldExpired() {
        return isOnHold() && reservedUntil != null && reservedUntil.isBefore(OffsetDateTime.now());
    }

    /** The price actually being asked: this unit's own, or its typology's. Never null at the call site. */
    public BigDecimal effectivePrice(BigDecimal typologyPrice) {
        return listPrice != null ? listPrice : typologyPrice;
    }
}
