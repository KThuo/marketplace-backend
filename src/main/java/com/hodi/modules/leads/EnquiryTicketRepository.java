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
public interface EnquiryTicketRepository
        extends JpaRepository<EnquiryTicket, Long>, JpaSpecificationExecutor<EnquiryTicket> {

    boolean existsByReference(String reference);

    /** The seller's, or the platform's. Scope is applied by the caller's specification, not here. */
    Optional<EnquiryTicket> findByReference(String reference);

    @Query("select t from EnquiryTicket t where t.reference = :reference and t.userId = :userId "
            + "and t.status <> 5")
    Optional<EnquiryTicket> findMineByReference(@Param("reference") String reference,
                                                @Param("userId") Long userId);

    @Query("select t from EnquiryTicket t where t.userId = :userId and t.status <> 5")
    Page<EnquiryTicket> findMine(@Param("userId") Long userId, Pageable pageable);

    long countByUserId(Long userId);

    /** The badge on the seller's inbox: conversations still waiting on them. */
    @Query("select count(t) from EnquiryTicket t where t.tenantId = :tenantId "
            + "and t.awaitingSeller = true and t.status <> 5 and t.state <> 'CLOSED'")
    long countAwaiting(@Param("tenantId") Long tenantId);
}
