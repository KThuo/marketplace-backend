package com.hodi.modules.ratings;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;

/**
 * A complaint about a rating (M7).
 *
 * <p>Rows rather than a counter alone, because "three people reported this" and "one person reported it
 * three times" are different facts and only one of them means anything. The counter on the rating is the
 * cached count of these.
 */
@Entity
@Table(name = "rating_reports")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RatingReport {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rating_id", nullable = false) private Long ratingId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(nullable = false, length = 32) private String reason;
    @Column(columnDefinition = "TEXT") private String detail;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
}
