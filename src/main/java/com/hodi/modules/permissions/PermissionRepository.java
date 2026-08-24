package com.hodi.modules.permissions;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface PermissionRepository
        extends JpaRepository<Permission, Long>, JpaSpecificationExecutor<Permission> {

    Optional<Permission> findByActionCode(String actionCode);

    List<Permission> findByActionCodeIn(Set<String> actionCodes);

    List<Permission> findByModuleCode(String moduleCode);

    /**
     * Everything an organisation may ever be granted.
     *
     * <p>Excludes {@code platform_only} rows, which is what makes "give the owner everything" safe to
     * implement as a query rather than as a hand-maintained list. Both things that hand out permissions
     * go through this — the seeded templates and the system-group top-up.
     */
    List<Permission> findByPlatformOnlyFalseAndStatusNot(Integer status);

    List<Permission> findByStatusNot(Integer status);
}
