package com.hodi.modules.banks;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface BankRepository
        extends JpaRepository<Bank, Long>, JpaSpecificationExecutor<Bank> {

    Optional<Bank> findBySlug(String slug);

    /** By the reference the catalogue quotes — what a product's author names a bank by. */
    Optional<Bank> findByInstitutionRef(String institutionRef);

    boolean existsBySlugIgnoreCase(String slug);

    boolean existsByInstitutionRef(String institutionRef);

    List<Bank> findByStatusNotOrderByNameAsc(Integer status);
}
