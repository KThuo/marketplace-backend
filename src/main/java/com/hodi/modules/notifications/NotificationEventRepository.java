package com.hodi.modules.notifications;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationEventRepository extends JpaRepository<NotificationEvent, String> {
    List<NotificationEvent> findAllByOrderBySortOrderAscCodeAsc();
}
