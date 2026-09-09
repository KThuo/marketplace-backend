package com.hodi.modules.developments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FacilityDrawdownRepository extends JpaRepository<FacilityDrawdown, Long> {

    boolean existsByReference(String reference);

    /** A development's drawdowns, newest first, voided ones included and marked. */
    @Query("select f from FacilityDrawdown f where f.developmentId = :developmentId and f.status <> 5 "
            + "order by f.drawnOn desc, f.id desc")
    List<FacilityDrawdown> findForDevelopment(@Param("developmentId") Long developmentId);
}
