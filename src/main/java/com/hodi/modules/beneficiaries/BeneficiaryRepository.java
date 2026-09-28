package com.hodi.modules.beneficiaries;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BeneficiaryRepository extends JpaRepository<Beneficiary, Long>,
        JpaSpecificationExecutor<Beneficiary> {

    boolean existsByReference(String reference);

    /**
     * Another of this owner's beneficiaries already paid at this account.
     *
     * <p>The same question {@code uk_beneficiary_payout} asks, asked first so the answer is a sentence naming
     * the clash rather than a constraint violation.
     */
    @Query("select b from Beneficiary b where b.bankCode = :bankCode and b.accountNo = :accountNo "
            + "and b.status <> 5 and b.id <> :exceptId "
            + "and ((:tenantId is not null and b.tenantId = :tenantId) "
            + "  or (:institutionId is not null and b.institutionId = :institutionId) "
            // The bank's shared one counts as already registered for everybody.
            + "  or (b.tenantId is null and b.institutionId is null))")
    Optional<Beneficiary> findPayoutClash(@Param("tenantId") Long tenantId,
                                          @Param("institutionId") Long institutionId,
                                          @Param("bankCode") String bankCode,
                                          @Param("accountNo") String accountNo,
                                          @Param("exceptId") Long exceptId);

    /** Live and verified — what a payment form may pick from. */
    @Query("select b from Beneficiary b where b.status in (1, 2) and b.verification = 'VERIFIED' "
            + "and ((:tenantId is not null and b.tenantId = :tenantId) "
            + "  or (:institutionId is not null and b.institutionId = :institutionId) "
            + "  or (b.tenantId is null and b.institutionId is null)) "
            + "order by b.name")
    List<Beneficiary> findPayable(@Param("tenantId") Long tenantId, @Param("institutionId") Long institutionId);

    /** How many beneficiaries each type has, for the admin screen and for refusing to suspend one in use. */
    @Query("select b.typeId, count(b) from Beneficiary b where b.status <> 5 group by b.typeId")
    List<Object[]> countByType();
}
