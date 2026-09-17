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
public interface EnquiryMessageRepository extends JpaRepository<EnquiryMessage, Long> {

    List<EnquiryMessage> findByTicketIdOrderByCreatedAtAsc(Long ticketId);

    /**
     * The most recent message on each of a page of tickets, in one query.
     *
     * <p>Exists because a list of conversations that shows a count going up and not a word of what was
     * said is the complaint "display the chat" is about. The alternative — fetching each thread from the
     * client — is fifty round trips for one screen, and the alternative to that is what was there before,
     * which is nothing.
     *
     * <p>Newest first and the caller keeps the first per ticket, rather than a correlated subquery for the
     * maximum: the rows are small, a page is at most fifty tickets, and the version of this with
     * {@code max(created_at)} in a subquery ties whenever two messages share a timestamp.
     */
    @Query("""
            select m from EnquiryMessage m
            where m.ticketId in :ticketIds
            order by m.createdAt desc, m.id desc
            """)
    List<EnquiryMessage> latestForTickets(@Param("ticketIds") List<Long> ticketIds);
}
