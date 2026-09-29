package com.hodi.modules.campaigns;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface CampaignSendRepository extends JpaRepository<CampaignSend, Long> {

    @Query("select s from CampaignSend s where s.campaignId = :campaignId and s.state = 'PENDING' order by s.id asc")
    List<CampaignSend> findPending(@Param("campaignId") Long campaignId, Pageable pageable);

    long countByCampaignIdAndState(Long campaignId, String state);

    /** How many went out today, across every campaign — the daily cap's question. */
    @Query("select count(s) from CampaignSend s where s.state = 'SENT' and s.sentAt >= :since")
    long countSentSince(@Param("since") OffsetDateTime since);

    List<CampaignSend> findByCampaignIdOrderByIdAsc(Long campaignId);
}
