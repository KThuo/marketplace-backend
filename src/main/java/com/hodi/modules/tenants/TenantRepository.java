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

    /** By the public reference, which is how a rating names its subject (M7). */
    java.util.Optional<Tenant> findByTenantRef(String tenantRef);

    List<Tenant> findByStatusNotOrderByNameAsc(Integer status);

    long countByOnboardingStatus(String onboardingStatus);
}
