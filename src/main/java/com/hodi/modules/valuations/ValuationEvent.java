package com.hodi.modules.valuations;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * One thing that happened to a valuation: who did what, when, and what they said.
 *
 * <p>Its own row rather than a column on the job, because a job can be handed back twice and cancelled once,
 * and a single {@code declined_reason} column kept only the last of them. The timeline on the detail page is
 * this table read in order.
 */
@Entity
@Table(name = "valuation_events")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ValuationEvent {

    public static final String RAISED = "RAISED";
    public static final String ASSIGNED = "ASSIGNED";
    public static final String ACCEPTED = "ACCEPTED";
    public static final String HANDED_BACK = "HANDED_BACK";
    public static final String REPORTED = "REPORTED";
    public static final String APPROVED = "APPROVED";
    public static final String SENT_BACK = "SENT_BACK";
    public static final String CANCELLED = "CANCELLED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false) private Long requestId;
    @Column(nullable = false, length = 16) private String action;
    /** The job's state once this had happened. */
    @Column(nullable = false, length = 16) private String state;
    @Column(length = 64) private String actor;
    /** PLATFORM, SELLER, VALUER… — who they were acting as. */
    @Column(name = "actor_role", length = 16) private String actorRole;
    @Column(columnDefinition = "TEXT") private String note;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}
