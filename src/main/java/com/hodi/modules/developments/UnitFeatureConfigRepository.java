package com.hodi.modules.developments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface UnitFeatureConfigRepository extends JpaRepository<UnitFeatureConfig, Long> {

    /** The catalogue, grouped the way a screen shows it. */
    @Query("select c from UnitFeatureConfig c where c.status <> 5 order by c.sortOrder, c.name")
    List<UnitFeatureConfig> findLive();
}
