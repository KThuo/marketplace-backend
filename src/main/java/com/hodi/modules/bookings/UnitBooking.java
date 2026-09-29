package com.hodi.modules.bookings;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * A unit booked by a buyer, and the terms of it.
 *
 * <h2>Not a purchase request</h2>
 *
 * <p>{@code purchase_requests} permits five live offers on one property, deliberately — an offer is an
 * expression of intent and a seller compares them. A booked unit has one holder, and that contradiction is why
 * this is a separate table rather than a state on that one.
 *
 * <h2>One live booking per unit, and the index says so</h2>
 *
 * <p>{@code uk_booking_live_property} is a partial unique index over {@code property_id} where the state is RESERVED or
 * AGREED. The service checks too, and the check is the courtesy — the index is the guarantee. Two agents
 * pressing Book in the same second both read "available", and only a constraint stops the second write from
 * quietly replacing the first.
 *
 * <h2>The buyer may have no account</h2>
 *
 * <p>{@link #buyerUserId} is the bonus; the name and the phone are the identity. A bank's off-plan buyer from
 * 2019 has no login here and never will, and a model that required one would make the real inventory
 * unrecordable.
 */
@Entity
@Table(name = "unit_bookings")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UnitBooking {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 16) private String reference;

    /**
     * The four characters a buyer quotes when paying for this booking — at the bank, on a transfer, on an STK
     * prompt. Unique across every booking ever, so a late payment quoting a cancelled booking's code finds the
     * cancelled booking and not whoever booked the home next.
     */
    @Column(name = "pay_reference", nullable = false, length = 8) private String payReference;

    /** Null for a house: a booking is on a property, and only a unit has a project above it. */
    @Column(name = "development_id") private Long developmentId;
    /** The home being bought — a UNIT row or a HOUSE row of {@code properties}. */
    @Column(name = "property_id", nullable = false) private Long propertyId;
    @Column(name = "unit_type_id") private Long unitTypeId;

    /** Cached from the development so an access check is one read rather than a walk up the tree. */
    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "institution_id") private Long institutionId;

    @Column(name = "buyer_user_id") private Long buyerUserId;
    @Column(name = "buyer_name", nullable = false, length = 160) private String buyerName;
    @Column(name = "buyer_phone", nullable = false, length = 32) private String buyerPhone;
    @Column(name = "buyer_email", length = 128) private String buyerEmail;
    @Column(name = "buyer_id_number", length = 32) private String buyerIdNumber;

    /**
     * The agent who brought this buyer, when one did. Set while the booking is live; frozen once it
     * completes, because a commission line has been raised against it by then.
     */
    @Column(name = "introduced_by_agent_id") private Long introducedByAgentId;

    @Column(nullable = false, length = 24)
    @Builder.Default private String state = AppConstant.BOOKING_RESERVED;

    @Column(name = "price_agreed", precision = 15, scale = 2) private BigDecimal priceAgreed;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";
    @Column(name = "deposit_due", precision = 15, scale = 2) private BigDecimal depositDue;

    @Column(name = "payment_plan", nullable = false, length = 24)
    @Builder.Default private String paymentPlan = AppConstant.PLAN_INSTALMENTS;

    @Column(name = "booked_on", nullable = false)
    @Builder.Default private LocalDate bookedOn = LocalDate.now();

    /** Null once the buyer has committed: a commitment does not expire. */
    @Column(name = "expires_at") private OffsetDateTime expiresAt;
    @Column(name = "agreed_at") private OffsetDateTime agreedAt;
    @Column(name = "completed_at") private OffsetDateTime completedAt;
    /** The proceeds reached the owner: set by the bank's confirmation of that transfer, never by hand. */
    @Column(name = "settled_at") private OffsetDateTime settledAt;
    @Column(name = "closed_at") private OffsetDateTime closedAt;
    @Column(name = "close_reason", columnDefinition = "TEXT") private String closeReason;

    @Column(columnDefinition = "TEXT") private String notes;

    /** NONE (before terms existed), PRESENTED, ACCEPTED or DECLINED — see {@code BookingTermsService}. */
    @Column(name = "terms_state", nullable = false, length = 10)
    @Builder.Default private String termsState = "NONE";
    @Column(name = "expiry_reminder_sent_at") private OffsetDateTime expiryReminderSentAt;

    /** Generated by the database from the reference and the buyer, for the receive form's picker. */
    @Column(name = "search_text", insertable = false, updatable = false) private String searchText;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    /** Holding the unit right now, whether reserved or agreed. What the unique index keys on. */
    public boolean isLive() {
        return AppConstant.BOOKING_RESERVED.equals(state) || AppConstant.BOOKING_AGREED.equals(state);
    }

    public boolean isReserved() { return AppConstant.BOOKING_RESERVED.equals(state); }
    public boolean isAgreed() { return AppConstant.BOOKING_AGREED.equals(state); }
    public boolean isCompleted() { return AppConstant.BOOKING_COMPLETED.equals(state); }

    /**
     * Whether the reservation has run out.
     *
     * <p>Derived rather than stored, like a unit's hold. A stored flag would be wrong for however long it took
     * something to notice, and the sweep would be the only thing keeping it true — which makes correctness
     * depend on a scheduler having run.
     */
    public boolean isExpired() {
        return isReserved() && expiresAt != null && expiresAt.isBefore(OffsetDateTime.now());
    }
}
