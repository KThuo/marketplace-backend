package com.hodi.modules.sellerops;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PropertyTypeConfigRepository extends JpaRepository<PropertyTypeConfig, Long> {

    Optional<PropertyTypeConfig> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    @Query("select c from PropertyTypeConfig c where c.status <> 5 order by c.sortOrder asc, c.name asc")
    List<PropertyTypeConfig> findAllLive();

    @Query("select c from PropertyTypeConfig c where c.status not in (4, 5) "
            + "order by c.sortOrder asc, c.name asc")
    List<PropertyTypeConfig> findAllActive();
}
