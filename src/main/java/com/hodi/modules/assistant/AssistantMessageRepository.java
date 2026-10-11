package com.hodi.modules.assistant;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface AssistantMessageRepository extends JpaRepository<AssistantMessage, Long> {

    List<AssistantMessage> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    /** Questions asked from one browser or one address since a moment: the visitor's hourly limit. */
    @Query(value = "SELECT count(*) FROM assistant_messages m JOIN assistant_conversations c ON c.id = m.conversation_id "
            + "WHERE m.side = 'USER' AND m.created_at >= :since "
            + "AND (c.visitor_hash = :visitorHash OR (CAST(:clientHash AS varchar) IS NOT NULL AND c.client_hash = CAST(:clientHash AS varchar)))",
            nativeQuery = true)
    long countVisitorQuestionsSince(@Param("visitorHash") String visitorHash, @Param("clientHash") String clientHash,
                                    @Param("since") OffsetDateTime since);

    /** Answers by what they were about, since a moment, for the insights page. */
    @Query("select m.intent, count(m), sum(case when m.rating = 1 then 1 else 0 end), "
            + "sum(case when m.rating = -1 then 1 else 0 end) from AssistantMessage m "
            + "where m.side = 'ASSISTANT' and m.intent is not null and m.createdAt >= :since group by m.intent")
    List<Object[]> tallyByIntent(@Param("since") OffsetDateTime since);

    /** What it could not answer, or answered and was told it was wrong, newest first. */
    @Query("select m from AssistantMessage m where m.side = 'ASSISTANT' and m.createdAt >= :since "
            + "and (m.intent = 'UNKNOWN' or m.rating = -1) order by m.createdAt desc")
    Page<AssistantMessage> findFailingSince(@Param("since") OffsetDateTime since, Pageable pageable);

    /** The question an answer was given to: the last thing the person said before it. */
    Optional<AssistantMessage> findFirstByConversationIdAndSideAndCreatedAtLessThanEqualAndIdLessThanOrderByIdDesc(
            Long conversationId, String side, OffsetDateTime at, Long id);
}
