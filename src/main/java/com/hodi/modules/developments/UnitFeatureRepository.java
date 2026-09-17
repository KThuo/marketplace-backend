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
     * What the estate itself comes with.
     *
     * <p>The borehole, the gate, the clubhouse. Before the column existed the only way to record one gate
     * was to tick it on every listing behind it, which made ninety claims out of one fact and left nothing
     * to correct when the gate came down.
     */
    @Query("select f from UnitFeature f where f.developmentId = :developmentId and f.status <> 5")
    List<UnitFeature> findForDevelopment(@Param("developmentId") Long developmentId);

    /** Typology features for a set of typologies, in one query. See {@link #findForUnits}. */
    @Query("select f from UnitFeature f where f.unitTypeId in :unitTypeIds and f.status <> 5")
    List<UnitFeature> findForUnitTypes(@Param("unitTypeIds") Collection<Long> unitTypeIds);

    /**
     * Features for a set of units, in one query.
     *
     * <p>For a list of twenty-four units where each shows what it has: a lookup each would be twenty-four
     * queries behind one panel.
     */
    @Query("select f from UnitFeature f where f.unitId in :unitIds and f.status <> 5")
    List<UnitFeature> findForUnits(@Param("unitIds") Collection<Long> unitIds);
}
