package com.hodi.modules.developments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface DevelopmentUnitTypeRepository extends JpaRepository<DevelopmentUnitType, Long> {

    boolean existsByReference(String reference);

    Optional<DevelopmentUnitType> findByReference(String reference);

    @Query("select t from DevelopmentUnitType t where t.developmentId = :developmentId and t.status <> 5 "
            + "order by t.sortOrder, t.id")
    List<DevelopmentUnitType> findForDevelopment(@Param("developmentId") Long developmentId);

    @Query("select count(t) from DevelopmentUnitType t where t.developmentId = :developmentId "
            + "and upper(t.code) = upper(:code) and t.status <> 5 and t.id <> :exceptId")
    long countWithCode(@Param("developmentId") Long developmentId,
                       @Param("code") String code,
                       @Param("exceptId") Long exceptId);

    /**
     * The cheapest price a buyer could pay for this typology, and the development's own "from" figure.
     *
     * <p>Coalesces each unit's own price against the typology's, because a unit only carries a price where it
     * differs — so ignoring the null would report the corner flat's premium as the entry price.
     */
    @Query("select min(coalesce(u.listPrice, t.listPrice)) from DevelopmentUnit u "
            + "join DevelopmentUnitType t on t.id = u.unitTypeId "
            + "where u.unitTypeId = :unitTypeId and u.status <> 5 "
            + "and u.saleState in ('AVAILABLE', 'HELD', 'RESERVED')")
    BigDecimal cheapestAvailable(@Param("unitTypeId") Long unitTypeId);
}
