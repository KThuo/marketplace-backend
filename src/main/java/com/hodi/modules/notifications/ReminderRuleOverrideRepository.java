package com.hodi.modules.notifications;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ReminderRuleOverrideRepository extends JpaRepository<ReminderRuleOverride, Long> {

    @Query("select o from ReminderRuleOverride o where o.ruleCode = :code "
            + "and ((:tenantId is not null and o.tenantId = :tenantId) or (:institutionId is not null and o.institutionId = :institutionId))")
    Optional<ReminderRuleOverride> findFor(@Param("code") String code, @Param("tenantId") Long tenantId,
                                           @Param("institutionId") Long institutionId);

    @Query("select o from ReminderRuleOverride o where "
            + "(:tenantId is not null and o.tenantId = :tenantId) or (:institutionId is not null and o.institutionId = :institutionId)")
    List<ReminderRuleOverride> findAllFor(@Param("tenantId") Long tenantId, @Param("institutionId") Long institutionId);
}
