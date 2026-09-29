package com.hodi.modules.notifications;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface NotificationEventOverrideRepository extends JpaRepository<NotificationEventOverride, Long> {

    @Query("select o from NotificationEventOverride o where o.eventCode = :code "
            + "and ((:tenantId is not null and o.tenantId = :tenantId) or (:institutionId is not null and o.institutionId = :institutionId))")
    Optional<NotificationEventOverride> findFor(@Param("code") String code, @Param("tenantId") Long tenantId,
                                                @Param("institutionId") Long institutionId);

    @Query("select o from NotificationEventOverride o where "
            + "(:tenantId is not null and o.tenantId = :tenantId) or (:institutionId is not null and o.institutionId = :institutionId)")
    List<NotificationEventOverride> findAllFor(@Param("tenantId") Long tenantId, @Param("institutionId") Long institutionId);
}
