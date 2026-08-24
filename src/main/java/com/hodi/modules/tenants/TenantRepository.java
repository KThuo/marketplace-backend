package com.hodi.modules.tenants;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TenantRepository extends JpaRepository<Tenant, Long>, JpaSpecificationExecutor<Tenant> {

    Optional<Tenant> findBySlug(String slug);

    boolean existsBySlugIgnoreCase(String slug);

    boolean existsByTenantRef(String tenantRef);

    List<Tenant> findByStatusNotOrderByNameAsc(Integer status);

    long countByOnboardingStatus(String onboardingStatus);

    /** Sellers a lender could ask to partner with — active, and not already partnered. */
    @Query("select t from Tenant t where t.onboardingStatus = 'ACTIVE' and t.status <> 5 "
            + "and t.id not in (select p.tenantId from Partnership p "
            + "                 where p.institutionId = :institutionId and p.revokedAt is null "
            + "                   and p.status <> 5) "
            + "order by t.name")
    List<Tenant> findPartnerableBy(Long institutionId);
}
