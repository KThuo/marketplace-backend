package com.hodi.modules.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReminderSentRepository extends JpaRepository<ReminderSent, Long> {
    Optional<ReminderSent> findByRuleCodeAndSubjectTypeAndSubjectIdAndSubjectKey(String ruleCode, String subjectType,
                                                                                Long subjectId, String subjectKey);
}
