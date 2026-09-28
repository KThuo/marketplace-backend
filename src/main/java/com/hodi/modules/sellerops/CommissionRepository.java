package com.hodi.modules.sellerops;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface CommissionRepository
        extends JpaRepository<CommissionRecord, Long>, JpaSpecificationExecutor<CommissionRecord> {

    Optional<CommissionRecord> findByReference(String reference);

    /** One line per payee per sale: the unique index says so too. */
    boolean existsByBookingIdAndPayeeKindAndStatusNot(Long bookingId, String payeeKind, Integer status);

    java.util.List<CommissionRecord> findByBookingIdAndStatusNotOrderByPayeeKind(Long bookingId, Integer status);

    /** An agent's own lines, wherever the sale was. */
    java.util.List<CommissionRecord> findByAgentProfileIdAndPayeeKindAndStatusNot(Long agentProfileId,
                                                                                 String payeeKind, Integer status);

    /** What is still owed, in one figure. Null when nothing is. */
    @Query("select sum(c.amount) from CommissionRecord c "
            + "where c.state in ('DUE', 'INVOICED') and c.status <> 5 "
            + "and (:tenantId is null or c.tenantId = :tenantId)")
    BigDecimal outstandingTotal(@Param("tenantId") Long tenantId);

    @Query("select sum(c.amount) from CommissionRecord c where c.state = 'PAID' and c.status <> 5 "
            + "and (:tenantId is null or c.tenantId = :tenantId)")
    BigDecimal paidTotal(@Param("tenantId") Long tenantId);

    /** The same two figures for one kind of payee: the bank's lines, or the agents'. */
    @Query("select sum(c.amount) from CommissionRecord c "
            + "where c.payeeKind = :payeeKind and c.state in ('DUE', 'INVOICED') and c.status <> 5 "
            + "and (:tenantId is null or c.tenantId = :tenantId)")
    BigDecimal outstandingTotalFor(@Param("payeeKind") String payeeKind, @Param("tenantId") Long tenantId);

    @Query("select sum(c.amount) from CommissionRecord c "
            + "where c.payeeKind = :payeeKind and c.state = 'PAID' and c.status <> 5 "
            + "and (:tenantId is null or c.tenantId = :tenantId)")
    BigDecimal paidTotalFor(@Param("payeeKind") String payeeKind, @Param("tenantId") Long tenantId);
}
