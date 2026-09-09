package com.hodi.modules.payments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long>, JpaSpecificationExecutor<Payment> {

    Optional<Payment> findByReference(String reference);

    /**
     * A booking's payments, newest first, voided ones included.
     *
     * <p>A voided payment is part of the story: the buyer was told it had been received, and the row saying so
     * — and saying why it was reversed — is what answers "we paid that" later.
     */
    @Query("select p from Payment p where p.bookingId = :bookingId and p.status <> 5 "
            + "order by p.paidOn desc, p.id desc")
    List<Payment> findForBooking(@Param("bookingId") Long bookingId);

    /** Everything received and standing. The same sum {@code v_booking_balances} performs. */
    @Query("select coalesce(sum(p.amount), 0) from Payment p "
            + "where p.bookingId = :bookingId and p.status = 1")
    BigDecimal totalPaid(@Param("bookingId") Long bookingId);

    @Query("select count(p) from Payment p where p.bookingId = :bookingId and p.status = 1")
    long countReceived(@Param("bookingId") Long bookingId);
}
