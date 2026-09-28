package com.hodi.modules.agents;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentPayoutAccountRepository extends JpaRepository<AgentPayoutAccount, Long> {

    @Query("select a from AgentPayoutAccount a where a.agentProfileId = :agentId and a.status <> 5 "
            + "order by a.defaultAccount desc, a.id")
    List<AgentPayoutAccount> findLiveFor(@Param("agentId") Long agentId);

    @Query("select a from AgentPayoutAccount a where a.agentProfileId = :agentId and a.status <> 5 "
            + "and a.bankCode = :bankCode and a.accountNo = :accountNo")
    Optional<AgentPayoutAccount> findLive(@Param("agentId") Long agentId, @Param("bankCode") String bankCode,
                                          @Param("accountNo") String accountNo);

    @Query("select a from AgentPayoutAccount a where a.agentProfileId = :agentId and a.status <> 5 "
            + "and a.defaultAccount = true")
    Optional<AgentPayoutAccount> findDefault(@Param("agentId") Long agentId);
}
