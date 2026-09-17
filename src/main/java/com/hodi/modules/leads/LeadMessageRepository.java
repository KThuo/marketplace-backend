package com.hodi.modules.leads;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Threads for viewings and offers.
 *
 * <p>The lookups take the lead type as well as the id because the ids are per-table: site visit 12 and
 * purchase request 12 both exist, and a query on the id alone would braid two conversations together.
 */
public interface LeadMessageRepository extends JpaRepository<LeadMessage, Long> {

    @Query("""
            select m from LeadMessage m
            where m.leadType = :leadType and m.leadId = :leadId and m.status <> 5
            order by m.createdAt asc, m.id asc
            """)
    List<LeadMessage> thread(@Param("leadType") String leadType, @Param("leadId") Long leadId);

    /**
     * Every thread for a page of leads, in one query.
     *
     * <p>A list of twenty viewings each fetching its own history is twenty-one queries for a screen that
     * shows the last line of each; the caller groups by {@code leadId}.
     */
    @Query("""
            select m from LeadMessage m
            where m.leadType = :leadType and m.leadId in :leadIds and m.status <> 5
            order by m.createdAt asc, m.id asc
            """)
    List<LeadMessage> threads(@Param("leadType") String leadType, @Param("leadIds") List<Long> leadIds);
}
