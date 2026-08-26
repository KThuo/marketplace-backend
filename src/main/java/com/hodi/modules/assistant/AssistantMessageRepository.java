package com.hodi.modules.assistant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, Long> {

    List<AssistantMessage> findByConversationIdOrderByCreatedAtAsc(Long conversationId);
}
