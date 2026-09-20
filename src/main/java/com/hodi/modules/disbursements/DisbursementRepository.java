package com.hodi.modules.disbursements;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface DisbursementRepository extends JpaRepository<Disbursement, Long>,
        JpaSpecificationExecutor<Disbursement> {

    Optional<Disbursement> findByReference(String reference);

    Optional<Disbursement> findByBankReference(String bankReference);

    /** Held for update: the claim before a send, and every settlement, go through this. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Disbursement d where d.id = :id and d.status <> 5")
    Optional<Disbursement> lockById(@Param("id") Long id);

    /** Approved and never claimed — an after-commit send that did not run. Oldest first. */
    @Query("select d from Disbursement d where d.state = 'APPROVED' and d.status <> 5 "
            + "and d.checkedAt < :before order by d.checkedAt asc")
    List<Disbursement> findApprovedBefore(@Param("before") OffsetDateTime before);

    /** Out with the bank and unanswered. The deadline is each row's own, checked in Java. */
    @Query("select d from Disbursement d where d.state in ('SENDING', 'SENT') and d.status <> 5 "
            + "order by d.sentAt asc")
    List<Disbursement> findOut();
}
