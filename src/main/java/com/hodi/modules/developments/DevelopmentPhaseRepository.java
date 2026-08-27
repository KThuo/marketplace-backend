package com.hodi.modules.developments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DevelopmentPhaseRepository extends JpaRepository<DevelopmentPhase, Long> {

    boolean existsByReference(String reference);

    /** Every live phase, in build order. What the timeline renders and what the percentage is derived from. */
    @Query("select p from DevelopmentPhase p where p.developmentId = :developmentId and p.status <> 5 "
            + "order by p.sequenceNo")
    List<DevelopmentPhase> findForDevelopment(@Param("developmentId") Long developmentId);

    Optional<DevelopmentPhase> findByReference(String reference);

    @Query("select count(p) from DevelopmentPhase p where p.developmentId = :developmentId "
            + "and p.sequenceNo = :sequenceNo and p.status <> 5 and p.id <> :exceptId")
    long countAtSequence(@Param("developmentId") Long developmentId,
                         @Param("sequenceNo") short sequenceNo,
                         @Param("exceptId") Long exceptId);
}
