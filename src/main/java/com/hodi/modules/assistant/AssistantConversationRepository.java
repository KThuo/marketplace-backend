package com.hodi.modules.assistant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AssistantConversationRepository extends JpaRepository<AssistantConversation, Long> {

    Optional<AssistantConversation> findByReference(String reference);

    @Query("select c from AssistantConversation c where c.userId = :userId and c.status <> 5 "
            + "order by c.lastMessageAt desc nulls last")
    Page<AssistantConversation> findMine(@Param("userId") Long userId, Pageable pageable);

    /** Hands a visitor's conversations to the account they just signed in to, and forgets the visitor. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update AssistantConversation c set c.userId = :userId, c.visitorHash = null, c.updatedBy = :by "
            + "where c.visitorHash = :visitorHash and c.userId is null")
    int claim(@Param("visitorHash") String visitorHash, @Param("userId") Long userId, @Param("by") String by);
}
