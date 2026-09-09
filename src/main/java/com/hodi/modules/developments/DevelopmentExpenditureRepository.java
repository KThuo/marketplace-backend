package com.hodi.modules.developments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface DevelopmentExpenditureRepository
        extends JpaRepository<DevelopmentExpenditure, Long>, JpaSpecificationExecutor<DevelopmentExpenditure> {

    boolean existsByReference(String reference);

    /** What a phase has committed or spent, from recorded lines only. The authority on the phase's column. */
    @Query("select coalesce(sum(e.amount), 0) from DevelopmentExpenditure e "
            + "where e.phaseId = :phaseId and e.kind = :kind and e.status = 1")
    BigDecimal sumForPhase(@Param("phaseId") Long phaseId, @Param("kind") String kind);

    /** How many lines sit under a category, so suspending one can say what it affects. */
    @Query("select e.categoryId, count(e) from DevelopmentExpenditure e where e.status <> 5 "
            + "group by e.categoryId")
    List<Object[]> countByCategory();
}
