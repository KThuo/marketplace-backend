package com.hodi.modules.appmodules;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface AppModuleRepository
        extends JpaRepository<AppModule, Long>, JpaSpecificationExecutor<AppModule> {

    Optional<AppModule> findByCode(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<AppModule> findByStatusNotOrderBySortOrderAsc(Integer status);

    /** Core modules, for the onboarding top-up that switches them on for a new seller. */
    List<AppModule> findByCoreTrueAndStatusNot(Integer status);
}
