package com.hodi.modules.assistant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AssistantKnowledgeRepository
        extends JpaRepository<AssistantKnowledge, Long>, JpaSpecificationExecutor<AssistantKnowledge> {

    @Query("select k from AssistantKnowledge k where k.active = true and k.status <> 5")
    List<AssistantKnowledge> findLive();

    @Query("select k from AssistantKnowledge k where k.reference = :reference and k.status <> 5")
    Optional<AssistantKnowledge> findLiveByReference(@Param("reference") String reference);

    @Query("select count(k) > 0 from AssistantKnowledge k where lower(k.term) = lower(:term) and k.status <> 5 "
            + "and (:exceptId is null or k.id <> :exceptId)")
    boolean termTaken(@Param("term") String term, @Param("exceptId") Long exceptId);
}
