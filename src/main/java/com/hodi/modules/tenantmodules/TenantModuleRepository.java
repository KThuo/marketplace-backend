package com.hodi.modules.tenantmodules;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TenantModuleRepository extends JpaRepository<TenantModule, Long> {

    /**
     * The module codes enabled for one seller — read by {@code EffectivePermissionResolver} on every
     * principal build, which is once per request.
     *
     * <p>Returns codes rather than rows because that is all the resolver compares, and a projection keeps
     * the hottest query in the application from materialising entities it will not use.
     */
    @Query("select m.moduleCode from TenantModule m "
            + "where m.tenantId = :tenantId and m.status <> 5 and m.status <> 4")
    List<String> findEnabledModuleCodes(@Param("tenantId") Long tenantId);

    List<TenantModule> findByTenantId(Long tenantId);

    Optional<TenantModule> findByTenantIdAndAppModuleId(Long tenantId, Long appModuleId);
}
