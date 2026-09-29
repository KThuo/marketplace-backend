package com.hodi.modules.bookings;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * What one booking was agreed under: the template's version and the figures it was filled with that day.
 *
 * <p>Not the text — the page renders that version with these figures whenever it is asked, and the same
 * page renders it for a listing nobody has booked yet. What is kept is exactly what could change under
 * the buyer's feet: the rate, the windows, the wording's version.
 */
@Entity
@Table(name = "booking_terms")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BookingTerms {

    public static final String CHANNEL_PORTAL = "PORTAL";
    public static final String CHANNEL_PAPER = "PAPER";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "booking_id", nullable = false) private Long bookingId;
    @Column(name = "template_version", nullable = false) private Integer templateVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> figures;

    @Column(name = "presented_at", nullable = false)
    @Builder.Default private OffsetDateTime presentedAt = OffsetDateTime.now();
    @Column(name = "accepted_at") private OffsetDateTime acceptedAt;
    @Column(name = "declined_at") private OffsetDateTime declinedAt;
    @Column(name = "decline_reason", columnDefinition = "TEXT") private String declineReason;
    @Column(length = 8) private String channel;
    @Column(name = "decided_by_user_id") private Long decidedByUserId;
    @Column(name = "decided_by_name", length = 160) private String decidedByName;
    @Column(name = "document_id") private Long documentId;
    @Column(name = "confirmed_at") private OffsetDateTime confirmedAt;
    @Column(name = "superseded_at") private OffsetDateTime supersededAt;
    @Column(name = "created_by", length = 64) private String createdBy;

    public boolean isDecided() {
        return acceptedAt != null || declinedAt != null;
    }
}
