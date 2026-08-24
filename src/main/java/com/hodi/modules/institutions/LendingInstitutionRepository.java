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

    /** Lenders a seller could ask to partner with — active, and not already partnered. */
    @Query("select i from LendingInstitution i where i.status <> 5 and i.status <> 4 "
            + "and i.id not in (select p.institutionId from Partnership p "
            + "                 where p.tenantId = :tenantId and p.revokedAt is null and p.status <> 5) "
            + "order by i.name")
    List<LendingInstitution> findPartnerableBy(Long tenantId);
}
