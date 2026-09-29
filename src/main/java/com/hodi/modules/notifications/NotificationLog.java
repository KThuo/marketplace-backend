package com.hodi.modules.notifications;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One message on one channel: what was said, to whom, and whether the gateway took it.
 *
 * <p>The recipient is kept in full because a retry has to send somewhere; it is shown masked. The payload
 * is the body as composed, so a retry says what was said on the day and not what the facts have since
 * become.
 */
@Entity
@Table(name = "notification_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class NotificationLog {

    public static final String QUEUED = "QUEUED";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED = "SKIPPED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id") private Long userId;
    @Column(name = "event_code", nullable = false, length = 40) private String eventCode;
    @Column(nullable = false, length = 32) private String purpose;
    @Column(nullable = false, length = 16) private String channel;
    @Column(nullable = false, length = 160) private String recipient;
    @Column(name = "recipient_masked", nullable = false, length = 160) private String recipientMasked;
    @Column(length = 255) private String subject;
    @Column(columnDefinition = "TEXT") private String payload;
    @Column(nullable = false, length = 12) @Builder.Default private String status = QUEUED;
    @Column(name = "provider_reference", length = 64) private String providerReference;
    @Column(columnDefinition = "TEXT") private String error;
    @Column(nullable = false) @Builder.Default private Integer attempts = 0;
    @Column(name = "next_attempt_at") private OffsetDateTime nextAttemptAt;
    @Column(name = "about_type", length = 32) private String aboutType;
    @Column(name = "about_id") private Long aboutId;
    @Column(name = "about_ref", length = 32) private String aboutRef;
    @Column(name = "created_at", nullable = false) @Builder.Default private OffsetDateTime createdAt = OffsetDateTime.now();
    @Column(name = "last_attempt_at") private OffsetDateTime lastAttemptAt;
    @Column(name = "sent_at") private OffsetDateTime sentAt;
}
