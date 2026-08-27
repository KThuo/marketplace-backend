package com.hodi.infra.pesi;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PesiSuperTypeRepository extends JpaRepository<PesiSuperType, Long> {

    @Query("select t from PesiSuperType t where t.code = :code and t.status <> 5")
    Optional<PesiSuperType> findByCode(@Param("code") String code);

    /** The catalogue, for a settings screen that adds a till without a deploy. */
    @Query("select t from PesiSuperType t where t.status <> 5 order by t.category, t.code")
    List<PesiSuperType> findLive();
}
