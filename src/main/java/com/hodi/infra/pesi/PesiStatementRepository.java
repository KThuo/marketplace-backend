package com.hodi.infra.pesi;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PesiStatementRepository extends JpaRepository<PesiStatement, Long> {

    /**
     * Whether this notification has already been recorded.
     *
     * <p>Checked for the fast path and for the message; the unique index is what actually guarantees it. Pesi
     * retries anything it does not get a clean answer to within thirty seconds, so two deliveries of the same
     * money can be in flight at once and both will pass this check.
     */
    @Query("select s from PesiStatement s where s.refNo = :refNo and s.status <> 5")
    Optional<PesiStatement> findByRefNo(@Param("refNo") String refNo);

    /** The ops queue: what arrived and could not be placed, oldest first — the oldest is being chased. */
    @Query("select s from PesiStatement s where s.state = 'UNMAPPED' and s.status <> 5 "
            + "order by s.createdAt")
    Page<PesiStatement> findUnmapped(Pageable pageable);

    @Query("select count(s) from PesiStatement s where s.state = 'UNMAPPED' and s.status <> 5")
    long countUnmapped();
}
