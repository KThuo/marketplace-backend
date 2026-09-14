package com.hodi.modules.institutions;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface LendingInstitutionRepository
        extends JpaRepository<LendingInstitution, Long>, JpaSpecificationExecutor<LendingInstitution> {

    Optional<LendingInstitution> findBySlug(String slug);

    boolean existsBySlugIgnoreCase(String slug);

    boolean existsByInstitutionRef(String institutionRef);

    List<LendingInstitution> findByStatusNotOrderByNameAsc(Integer status);
}
