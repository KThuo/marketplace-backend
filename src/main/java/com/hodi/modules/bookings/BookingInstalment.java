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
 * One line of a booking's payment schedule.
 *
 * <h2>Rescheduling writes a new plan</h2>
 *
 * <p>{@link #planNo} rather than editing the dates in place. The schedule a buyer agreed to in March stays
 * readable in June, which is the point of having agreed to it — and "we moved your dates" is a conversation
 * that needs both versions in front of it.
 *
 * <h2>No paid figure on this row</h2>
 *
 * <p>Payments are allocated against the booking, not against a line of its schedule: a buyer pays what they
 * pay when they pay it, rarely in the shape of the plan. Which lines that settles is arithmetic over two
 * lists, and arithmetic done once is cheaper than a stored figure kept true in five places.
 */
@Entity
@Table(name = "booking_instalments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BookingInstalment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false) private Long bookingId;

    /** Which version of the schedule. The highest live one is current; the rest are history. */
    @Column(name = "plan_no", nullable = false) @Builder.Default private short planNo = 1;
    @Column(name = "sequence_no", nullable = false) private short sequenceNo;

    @Column(length = 120) private String label;
    @Column(name = "due_on", nullable = false) private LocalDate dueOn;
    @Column(nullable = false, precision = 15, scale = 2) private BigDecimal amount;
    @Column(nullable = false, length = 3) @Builder.Default private String currency = "KES";

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;
}
