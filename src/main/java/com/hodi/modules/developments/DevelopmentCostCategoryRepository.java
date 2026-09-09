package com.hodi.modules.developments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DevelopmentCostCategoryRepository extends JpaRepository<DevelopmentCostCategory, Long> {

    Optional<DevelopmentCostCategory> findByCode(String code);

    /** Everything not archived, in the platform's own order — the admin screen. */
    @Query("select c from DevelopmentCostCategory c where c.status <> 5 order by c.sortOrder, c.name")
    List<DevelopmentCostCategory> findAllLive();

    /** What the form offers: switched on, in order. */
    @Query("select c from DevelopmentCostCategory c where c.status in (1, 2) order by c.sortOrder, c.name")
    List<DevelopmentCostCategory> findAvailable();

    @Query("select count(c) from DevelopmentCostCategory c where c.code = :code and c.status <> 5 "
            + "and c.id <> :exceptId")
    long countByCodeExcept(@Param("code") String code, @Param("exceptId") Long exceptId);
}
