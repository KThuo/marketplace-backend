package com.hodi.modules.agents;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentAgreementRepository extends JpaRepository<AgentAgreement, Long> {

    Optional<AgentAgreement> findByReference(String reference);

    List<AgentAgreement> findByAgentProfileIdOrderByGeneratedAtDesc(Long agentProfileId);
}
