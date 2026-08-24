package com.hodi.modules.buyerportal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface SearchAlertRepository extends JpaRepository<SearchAlert, Long> {

    @Query("select a from SearchAlert a where a.userId = :userId and a.status <> 5 "
            + "order by a.createdAt desc")
    Page<SearchAlert> findMine(@Param("userId") Long userId, Pageable pageable);

    @Query("select a from SearchAlert a where a.id = :id and a.userId = :userId and a.status <> 5")
    Optional<SearchAlert> findMineById(@Param("id") Long id, @Param("userId") Long userId);

    @Query("select count(a) from SearchAlert a where a.userId = :userId and a.status not in (4, 5)")
    long countRunning(@Param("userId") Long userId);

    /**
     * What the dispatcher has to look at this pass.
     *
     * <p>Bounded by {@code Pageable} rather than returning everything due: a backlog after an outage should
     * be worked through over several passes at a rate the notify gateway can absorb, not sent as one burst
     * that trips its rate limit and loses the lot.
     */
    @Query("select a from SearchAlert a where a.nextRunAt <= :now and a.status not in (4, 5) "
            + "order by a.nextRunAt asc")
    List<SearchAlert> findDue(@Param("now") OffsetDateTime now, Pageable pageable);
}
