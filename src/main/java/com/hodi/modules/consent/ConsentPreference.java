package com.hodi.modules.consent;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * One person's current answer to "may we contact you this way, about this?"
 *
 * <p>Keyed on the <em>person</em> rather than the profile, unlike almost everything since Phase 0a. Consent
 * is a fact about a human being and the inbox or handset they answer on; somebody who is a buyer and a
 * seller's owner has one of each and gave one answer. See the migration header for the longer form.
 *
 * <p>The history is not written here. A database trigger copies every movement of this row into
 * {@link ConsentPreferenceHistory}, so the trail survives code that forgets — including code nobody has
 * written yet. What this entity owes the trigger is provenance: {@link #capturedIp} and
 * {@link #capturedUserAgent} are set on every write so the history row records where the answer came from.
 */
@Entity
@Table(name = "consent_preferences")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConsentPreference {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false) private Long userId;

    /** {@code EMAIL} or {@code SMS}. */
    @Column(nullable = false, length = 16) private String channel;

    /** {@code TRANSACTIONAL}, {@code PROPERTY_ALERTS} or {@code PROMOTIONAL}. */
    @Column(nullable = false, length = 32) private String purpose;

    @Column(nullable = false) private boolean granted;

    @Column(nullable = false, length = 32)
    @Builder.Default private String source = AppConstant.CONSENT_SOURCE_PREFERENCES;

    @Column(name = "captured_at", nullable = false)
    @Builder.Default private OffsetDateTime capturedAt = OffsetDateTime.now();

    @Column(name = "captured_ip", length = 64) private String capturedIp;
    @Column(name = "captured_user_agent", columnDefinition = "TEXT") private String capturedUserAgent;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    /**
     * Whether this purpose can be declined at all.
     *
     * <p>The database says the same thing with a CHECK. This is the copy the user interface reads, so a
     * switch that would be refused is rendered as a fact rather than as a control.
     */
    public boolean isMandatory() {
        return AppConstant.CONSENT_TRANSACTIONAL.equals(purpose);
    }
}
