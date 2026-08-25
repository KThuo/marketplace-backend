package com.hodi.modules.leads;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/*
 * One rule these three share: every lookup takes the side asking as part of the query — a buyer's methods
 * carry {@code userId}, a seller's carry {@code tenantId} — so "load it, then decide whether they may see
 * it" is not a shape anything here offers.
 *
 * They were briefly nested inside one class. Spring Data scans top-level types only, so the application
 * would not start; three files is also the convention everywhere else in this codebase.
 */
public interface PurchaseRequestRepository
        extends JpaRepository<PurchaseRequest, Long>, JpaSpecificationExecutor<PurchaseRequest> {

    boolean existsByReference(String reference);

    Optional<PurchaseRequest> findByReference(String reference);

    @Query("select p from PurchaseRequest p where p.reference = :reference and p.userId = :userId "
            + "and p.status <> 5")
    Optional<PurchaseRequest> findMineByReference(@Param("reference") String reference,
                                                  @Param("userId") Long userId);

    @Query("select p from PurchaseRequest p where p.userId = :userId and p.status <> 5")
    Page<PurchaseRequest> findMine(@Param("userId") Long userId, Pageable pageable);

    long countByUserId(Long userId);

    /**
     * The one that stops a second offer while the first is outstanding.
     *
     * <p>The partial unique index says the same thing and is what actually enforces it; this exists so
     * the refusal is a sentence rather than a constraint violation surfacing as a 500.
     */
    @Query("select p from PurchaseRequest p where p.userId = :userId and p.propertyId = :propertyId "
            + "and p.state in ('SUBMITTED', 'UNDER_REVIEW') and p.status <> 5")
    Optional<PurchaseRequest> findLiveFor(@Param("userId") Long userId,
                                          @Param("propertyId") Long propertyId);

    @Query("select count(p) from PurchaseRequest p where p.tenantId = :tenantId "
            + "and p.state in ('SUBMITTED', 'UNDER_REVIEW') and p.status <> 5")
    long countLive(@Param("tenantId") Long tenantId);
}
