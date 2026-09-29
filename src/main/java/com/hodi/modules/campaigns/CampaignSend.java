package com.hodi.modules.campaigns;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/** One recipient of one campaign, fixed at approval and worked through by the sweep. */
@Entity
@Table(name = "campaign_sends")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CampaignSend {

    public static final String PENDING = "PENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false) private Long campaignId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(nullable = false, length = 12) @Builder.Default private String state = PENDING;
    @Column(length = 64) private String channels;
    @Column(name = "sent_at") private OffsetDateTime sentAt;
    @Column(columnDefinition = "TEXT") private String error;
}
