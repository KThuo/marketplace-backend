package com.hodi.modules.agents;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface AgentProfileRepository
        extends JpaRepository<AgentProfile, Long>, JpaSpecificationExecutor<AgentProfile> {

    Optional<AgentProfile> findByReference(String reference);

    Optional<AgentProfile> findByProfileId(Long profileId);

    Optional<AgentProfile> findByTenantId(Long tenantId);

    /**
     * The person, not the profile.
     *
     * <p>Somebody can hold a buyer profile and an agent profile on one login, and the agent screens are
     * reached from whichever profile is active — so the lookup that matters most often is by user.
     */
    Optional<AgentProfile> findFirstByUserIdOrderByIdDesc(Long userId);

    long countByState(String state);

    @Query("select count(a) from AgentProfile a where a.state = 'PENDING' and a.status <> 5")
    long countAwaitingDecision();
}
