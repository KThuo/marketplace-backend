package com.hodi.modules.developments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface UnitFeatureRepository extends JpaRepository<UnitFeature, Long> {

    @Query("select f from UnitFeature f where f.unitId = :unitId and f.status <> 5")
    List<UnitFeature> findForUnit(@Param("unitId") Long unitId);

    @Query("select f from UnitFeature f where f.unitTypeId = :unitTypeId and f.status <> 5")
    List<UnitFeature> findForUnitType(@Param("unitTypeId") Long unitTypeId);

    /**
     * Features for a set of units, in one query.
     *
     * <p>For a list of twenty-four units where each shows what it has: a lookup each would be twenty-four
     * queries behind one panel.
     */
    @Query("select f from UnitFeature f where f.unitId in :unitIds and f.status <> 5")
    List<UnitFeature> findForUnits(@Param("unitIds") Collection<Long> unitIds);
}
