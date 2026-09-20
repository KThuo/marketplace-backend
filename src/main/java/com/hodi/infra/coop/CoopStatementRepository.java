package com.hodi.infra.coop;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CoopStatementRepository extends JpaRepository<CoopStatement, Long>,
        JpaSpecificationExecutor<CoopStatement> {

    /**
     * The row, held for update.
     *
     * <p>A person attaching a credit and a notification or a sweep crediting the same money can meet in the
     * same second — the slip is validated, the bank's own callback lands, the operator presses the button.
     * The lock makes the second of them see the first one's decision rather than credit again.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CoopStatement s where s.id = :id and s.status <> 5")
    Optional<CoopStatement> lockById(@Param("id") Long id);

    /**
     * Every statement a bank reference could mean: the bank's own id, or what the payer typed.
     *
     * <p>Whatever its state. A slip lookup that searched only unused rows would find nothing for a slip
     * already applied, and "nothing" is what makes a clerk key it by hand a second time.
     */
    @Query("select s from CoopStatement s where s.status <> 5 and upper(s.refNo) = :reference order by s.id")
    List<CoopStatement> findAnyByReference(@Param("reference") String reference);

    /** What is waiting to be placed: how many, how much, and how long the oldest has waited. */
    @Query("select count(s), coalesce(sum(s.amount), 0), min(s.paidAt) from CoopStatement s "
            + "where s.state = 'UNMAPPED' and s.status <> 5")
    List<Object[]> waiting();

    @Query("select count(s), coalesce(sum(s.amount), 0), min(s.paidAt) from CoopStatement s "
            + "where s.state = 'UNMAPPED' and s.status <> 5 and s.tenantId = :tenantId")
    List<Object[]> waitingForTenant(@Param("tenantId") Long tenantId);

    @Query("select count(s), coalesce(sum(s.amount), 0), min(s.paidAt) from CoopStatement s "
            + "where s.state = 'UNMAPPED' and s.status <> 5 and s.institutionId = :institutionId")
    List<Object[]> waitingForInstitution(@Param("institutionId") Long institutionId);

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
