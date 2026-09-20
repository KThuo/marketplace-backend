package com.hodi.infra.coop;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CoopStatementRepository extends JpaRepository<CoopStatement, Long> {

    /**
     * Whether this notification has already been recorded.
     *
     * <p>Checked for the fast path and for the message; the unique index is what actually guarantees it. Co-op
     * retries anything it does not get a clean answer to within thirty seconds, so two deliveries of the same
     * money can be in flight at once and both will pass this check.
     */
    @Query("select s from CoopStatement s where s.refNo = :refNo and s.status <> 5")
    Optional<CoopStatement> findByRefNo(@Param("refNo") String refNo);

    /** The ops queue: what arrived and could not be placed, oldest first — the oldest is being chased. */
    @Query("select s from CoopStatement s where s.state = 'UNMAPPED' and s.status <> 5 "
            + "order by s.createdAt")
    Page<CoopStatement> findUnmapped(Pageable pageable);

    @Query("select count(s) from CoopStatement s where s.state = 'UNMAPPED' and s.status <> 5")
    long countUnmapped();

    /**
     * Every statement credited as this payment.
     *
     * <p>Usually one. Two when the same money was reported twice — a status enquiry and a notification, say
     * — and the second was linked rather than credited. A void releases all of them, or the second one sits
     * MAPPED to a payment that no longer counts.
     */
    @Query("select s from CoopStatement s where s.mappedPaymentId = :paymentId and s.status <> 5")
    List<CoopStatement> findByMappedPaymentId(@Param("paymentId") Long paymentId);
}
