package com.hodi.modules.banks;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BankRepository
        extends JpaRepository<Bank, Long>, JpaSpecificationExecutor<Bank> {

    Optional<Bank> findBySlug(String slug);

    boolean existsBySlugIgnoreCase(String slug);

    boolean existsByInstitutionRef(String institutionRef);

    List<Bank> findByStatusNotOrderByNameAsc(Integer status);
}
