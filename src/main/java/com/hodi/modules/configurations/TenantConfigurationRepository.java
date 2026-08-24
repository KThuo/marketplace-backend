package com.hodi.modules.configurations;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TenantConfigurationRepository extends JpaRepository<TenantConfiguration, Long> {

    Optional<TenantConfiguration> findByTenantIdAndConfigKey(Long tenantId, String configKey);

    List<TenantConfiguration> findByTenantId(Long tenantId);

    void deleteByTenantIdAndConfigKey(Long tenantId, String configKey);
}
