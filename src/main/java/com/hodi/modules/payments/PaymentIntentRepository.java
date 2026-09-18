package com.hodi.modules.payments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {

    Optional<PaymentIntent> findByReference(String reference);

    Optional<PaymentIntent> findByBankReference(String bankReference);

    /**
     * What the sweep works through: in flight, and asked long enough ago that silence means something.
     *
     * <p>The cutoff is generous and the per-intent deadline is checked in Java, because each intent
     * carries its own timeout — the query narrows the rows, it does not decide them. Oldest first, so a
     * payment that has waited longest is asked about first.
     */
    @Query("select i from PaymentIntent i where i.state = 'PROCESSING' and i.processedAt < :cutoff "
            + "order by i.processedAt asc")
    List<PaymentIntent> findInFlightBefore(@Param("cutoff") OffsetDateTime cutoff);

    List<PaymentIntent> findByBookingIdOrderByCreatedAtDesc(Long bookingId);
}
