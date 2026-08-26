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

    boolean existsByPropertyIdAndSoldAt(Long propertyId, java.time.OffsetDateTime soldAt);

    /** What is still owed, in one figure. Null when nothing is. */
    @Query("select sum(c.amount) from CommissionRecord c "
            + "where c.state in ('DUE', 'INVOICED') and c.status <> 5 "
            + "and (:tenantId is null or c.tenantId = :tenantId)")
    BigDecimal outstandingTotal(@Param("tenantId") Long tenantId);

    @Query("select sum(c.amount) from CommissionRecord c where c.state = 'PAID' and c.status <> 5 "
            + "and (:tenantId is null or c.tenantId = :tenantId)")
    BigDecimal paidTotal(@Param("tenantId") Long tenantId);
}
