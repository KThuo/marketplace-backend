package com.hodi.modules.bookings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface BookingPaymentRepository extends JpaRepository<BookingPayment, Long> {

    /** Newest first, reversals included — a reversal is part of the story, not a correction that hides one. */
    @Query("select p from BookingPayment p where p.bookingId = :bookingId and p.status <> 5 "
            + "order by p.paidOn desc, p.id desc")
    List<BookingPayment> findForBooking(@Param("bookingId") Long bookingId);

    /**
     * Everything received, net of reversals.
     *
     * <p>A plain sum, because a reversal is stored as a negative amount. That is the whole reason it is stored
     * that way: the arithmetic anybody would write by hand is already correct.
     */
    @Query("select coalesce(sum(p.amount), 0) from BookingPayment p "
            + "where p.bookingId = :bookingId and p.status <> 5")
    BigDecimal totalPaid(@Param("bookingId") Long bookingId);

    /** Whether this payment has already been reversed, so a second reversal can be refused. */
    @Query("select count(p) from BookingPayment p where p.reversalOfId = :paymentId and p.status <> 5")
    long reversalCount(@Param("paymentId") Long paymentId);
}
