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
public interface SiteVisitRepository
        extends JpaRepository<SiteVisit, Long>, JpaSpecificationExecutor<SiteVisit> {

    boolean existsByReference(String reference);

    Optional<SiteVisit> findByReference(String reference);

    @Query("select v from SiteVisit v where v.reference = :reference and v.userId = :userId "
            + "and v.status <> 5")
    Optional<SiteVisit> findMineByReference(@Param("reference") String reference,
                                            @Param("userId") Long userId);

    @Query("select v from SiteVisit v where v.userId = :userId and v.status <> 5")
    Page<SiteVisit> findMine(@Param("userId") Long userId, Pageable pageable);

    long countByUserId(Long userId);

    @Query("select count(v) from SiteVisit v where v.tenantId = :tenantId "
            + "and v.state = 'REQUESTED' and v.status <> 5")
    long countPending(@Param("tenantId") Long tenantId);
}
