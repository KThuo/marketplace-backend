package com.hodi.modules.configurations;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ConfigurationRepository
        extends JpaRepository<Configuration, Long>, JpaSpecificationExecutor<Configuration> {

    Optional<Configuration> findByConfigKey(String configKey);

    List<Configuration> findByCategoryOrderByConfigKeyAsc(String category);

    /** The keys a seller is allowed to shadow, for the overrides screen. */
    List<Configuration> findByOverridableTrueAndStatusNotOrderByCategoryAscConfigKeyAsc(Integer status);

    @Query("select distinct c.category from Configuration c where c.status <> 5 order by c.category")
    List<String> findCategories();
}
