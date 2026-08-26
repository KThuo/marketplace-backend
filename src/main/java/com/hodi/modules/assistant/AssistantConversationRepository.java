package com.hodi.modules.assistant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AssistantConversationRepository extends JpaRepository<AssistantConversation, Long> {

    Optional<AssistantConversation> findByReference(String reference);

    @Query("select c from AssistantConversation c where c.userId = :userId and c.status <> 5 "
            + "order by c.lastMessageAt desc nulls last")
    Page<AssistantConversation> findMine(@Param("userId") Long userId, Pageable pageable);
}
