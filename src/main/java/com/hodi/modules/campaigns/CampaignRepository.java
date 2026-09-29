package com.hodi.modules.campaigns;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface CampaignRepository extends JpaRepository<Campaign, Long>, JpaSpecificationExecutor<Campaign> {

    boolean existsByReference(String reference);

    Optional<Campaign> findByReference(String reference);

    /** Approved and due, or already going: what the sweep works on. */
    @Query("select c from Campaign c where c.state in ('APPROVED', 'SENDING') and c.status <> 5 "
            + "and (c.scheduledFor is null or c.scheduledFor <= :now) order by c.scheduledFor asc nulls first, c.id asc")
    List<Campaign> findDue(@Param("now") OffsetDateTime now);
}
