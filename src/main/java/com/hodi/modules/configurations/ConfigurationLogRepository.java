package com.hodi.modules.configurations;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ConfigurationLogRepository
        extends JpaRepository<ConfigurationLog, Long>, JpaSpecificationExecutor<ConfigurationLog> {
}
