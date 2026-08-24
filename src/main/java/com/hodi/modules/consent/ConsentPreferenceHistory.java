package com.hodi.modules.consent;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Every movement of a consent preference, in the order it happened.
 *
 * <p><strong>Read-only from Java.</strong> Rows arrive by trigger and the table refuses UPDATE and DELETE,
 * so there is no setter here and no repository method that writes one. If this entity ever gains a save
 * path, the database will reject it — which is the point: the answer to "prove they opted in on this date"
 * has to be a record rather than a claim, and a record an application can edit is a claim.
 */
@Entity
@Table(name = "consent_preference_history")
@Getter @NoArgsConstructor
public class ConsentPreferenceHistory {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consent_preference_id", nullable = false) private Long consentPreferenceId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(nullable = false, length = 16) private String channel;
    @Column(nullable = false, length = 32) private String purpose;

    /** Null on the first row for a preference — no previous position, which is not the same as "false". */
    @Column(name = "previous_granted") private Boolean previousGranted;
    @Column(name = "new_granted", nullable = false) private boolean newGranted;

    @Column(nullable = false, length = 32) private String source;

    @Column(name = "changed_at", nullable = false) private OffsetDateTime changedAt;
    @Column(name = "changed_by", length = 64) private String changedBy;
    @Column(name = "captured_ip", length = 64) private String capturedIp;
    @Column(name = "captured_user_agent", columnDefinition = "TEXT") private String capturedUserAgent;
}
