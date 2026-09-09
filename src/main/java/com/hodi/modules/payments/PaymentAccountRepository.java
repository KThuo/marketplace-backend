package com.hodi.modules.payments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** The configured accounts. */
public interface PaymentAccountRepository
        extends JpaRepository<PaymentAccount, Long>, JpaSpecificationExecutor<PaymentAccount> {

    /**
     * Which of our accounts a notification landed in.
     *
     * <p>Live rows only: withdrawing an account is what somebody does the moment it looks tampered with, and
     * a credit quoting a withdrawn account must go to the queue rather than be credited.
     */
    @Query("select a from PaymentAccount a where a.accountNo = :accountNo and a.status in (1, 2)")
    Optional<PaymentAccount> findLiveByAccountNo(@Param("accountNo") String accountNo);

    /** The fallback match, where the bank quoted the short code rather than the account. */
    @Query("select a from PaymentAccount a where a.shortCode = :shortCode and a.status in (1, 2)")
    Optional<PaymentAccount> findLiveByShortCode(@Param("shortCode") String shortCode);

    /** Whether this account number is already taken by any row that has not been archived. */
    @Query("select a from PaymentAccount a where a.accountNo = :accountNo and a.status <> 5")
    List<PaymentAccount> findByAccountNoNotArchived(@Param("accountNo") String accountNo);

    @Query("select a from PaymentAccount a where a.shortCode = :shortCode and a.status <> 5")
    List<PaymentAccount> findByShortCodeNotArchived(@Param("shortCode") String shortCode);

    /** A tenant's accounts, live only, in the order they were configured. */
    @Query("select a from PaymentAccount a where a.tenantId = :tenantId and a.status in (1, 2) "
            + "order by a.createdAt")
    List<PaymentAccount> findLiveForTenant(@Param("tenantId") Long tenantId);

    @Query("select a from PaymentAccount a where a.institutionId = :institutionId and a.status in (1, 2) "
            + "order by a.createdAt")
    List<PaymentAccount> findLiveForInstitution(@Param("institutionId") Long institutionId);

    /**
     * The platform's own accounts — the ones with no owner.
     *
     * <p>Its own finder rather than a null passed to the ones above: {@code tenant_id = null} is never true in
     * SQL, so that call would silently answer "none".
     */
    @Query("select a from PaymentAccount a where a.tenantId is null and a.institutionId is null "
            + "and a.status in (1, 2) order by a.createdAt")
    List<PaymentAccount> findLiveForPlatform();

    /** Every account on one channel that has not been archived, for the once-per-owner rule. */
    @Query("select a from PaymentAccount a where a.paymentTypeId = :paymentTypeId and a.status <> 5")
    List<PaymentAccount> findByPaymentTypeIdNotArchived(@Param("paymentTypeId") Long paymentTypeId);

    @Query("select count(a) from PaymentAccount a where a.paymentTypeId = :paymentTypeId and a.status <> 5")
    long countOnChannel(@Param("paymentTypeId") Long paymentTypeId);

    /**
     * How many accounts sit on each channel, across the platform. For a caller who may see all of it.
     *
     * <p>Grouped rather than one query per row: the catalogue is short, but a count per row is a query per
     * row for no reason.
     */
    @Query("select a.paymentTypeId, count(a) from PaymentAccount a where a.status <> 5 "
            + "group by a.paymentTypeId")
    List<Object[]> countByChannel();

    /**
     * The same, within one tenant.
     *
     * <p>The catalogue is platform-wide and meant to be; the count is not. Unscoped, it would tell a seller
     * how many accounts other organisations have configured on each channel, which is a fact about their
     * collection arrangements.
     */
    @Query("select a.paymentTypeId, count(a) from PaymentAccount a where a.status <> 5 "
            + "and a.tenantId = :tenantId group by a.paymentTypeId")
    List<Object[]> countByChannelForTenant(@Param("tenantId") Long tenantId);

    @Query("select a.paymentTypeId, count(a) from PaymentAccount a where a.status <> 5 "
            + "and a.institutionId = :institutionId group by a.paymentTypeId")
    List<Object[]> countByChannelForInstitution(@Param("institutionId") Long institutionId);
}
