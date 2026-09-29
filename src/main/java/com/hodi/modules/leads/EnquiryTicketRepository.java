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

    /** The buyer's own unanswered questions. Read by the assistant (M11). */
    /** Open enquiries whose last word was the buyer's, that long ago — the unanswered reminder's list. */
    @Query("select t from EnquiryTicket t where t.state = 'OPEN' and t.status <> 5 and t.lastMessageSide = 'BUYER' "
            + "and t.lastMessageAt <= :before order by t.lastMessageAt asc")
    List<EnquiryTicket> findUnansweredSince(@Param("before") java.time.OffsetDateTime before);

    @Query("select count(e) from EnquiryTicket e where e.userId = :userId "
            + "and e.state <> 'CLOSED' and e.status <> 5")
    long countOpenForUser(@Param("userId") Long userId);

    /** Evidence that this person and this listing have met on the platform (M7). */
    boolean existsByUserIdAndPropertyId(Long userId, Long propertyId);

    /** The buyer's most recent enquiry on a home that names who brought them — what an offer inherits. */
    @Query("select t from EnquiryTicket t where t.userId = :userId and t.propertyId = :propertyId "
            + "and t.introducedByAgentId is not null and t.status <> 5 order by t.createdAt desc")
    List<EnquiryTicket> findIntroducedFor(@Param("userId") Long userId, @Param("propertyId") Long propertyId);

    /** The same, one level up: this person has dealt with this organisation about something. */
    boolean existsByUserIdAndTenantId(Long userId, Long tenantId);

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
