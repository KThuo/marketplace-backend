package com.hodi.modules.bookings;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * One version of the booking terms: the wording, with placeholders the figures fill.
 *
 * <p>Never edited — a change is a new version, and the bookings agreed under the old one keep pointing at
 * it. The placeholders are {@code {{home}}, {{seller}}, {{buyer}}, {{price}}, {{deposit}}, {{plan}},
 * {{holdDays}}, {{expiresOn}}, {{penalty}}, {{refundWindow}}, {{reviveWindow}}, {{policyNote}}}.
 */
@Entity
@Table(name = "booking_terms_templates")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class BookingTermsTemplate {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true) private Integer version;
    @Column(nullable = false, columnDefinition = "TEXT") private String body;
    @Column(length = 255) private String note;

    @Column(name = "created_at", nullable = false)
    @Builder.Default private OffsetDateTime createdAt = OffsetDateTime.now();
    @Column(name = "created_by", length = 64) private String createdBy;
}
