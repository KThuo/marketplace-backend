package com.hodi.modules.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReminderRuleRepository extends JpaRepository<ReminderRule, String> {
    List<ReminderRule> findAllByOrderBySortOrderAscCodeAsc();
}
