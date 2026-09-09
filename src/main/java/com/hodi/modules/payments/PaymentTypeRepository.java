package com.hodi.modules.payments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/** The channels. */
public interface PaymentTypeRepository
        extends JpaRepository<PaymentType, Long>, JpaSpecificationExecutor<PaymentType> {

    Optional<PaymentType> findByCode(String code);

    Optional<PaymentType> findByPesiProviderType(String pesiProviderType);

    /** Every channel that has not been archived, in the platform's own order. */
    @Query("select t from PaymentType t where t.status <> 5 order by t.sortOrder, t.id")
    List<PaymentType> findAllLive();

    /** The channels switched on — the ones an organisation may still be given. */
    @Query("select t from PaymentType t where t.status in (1, 2) order by t.sortOrder, t.id")
    List<PaymentType> findAvailable();
}
