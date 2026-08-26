package com.hodi.modules.ratings;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * The maintained aggregate for one subject (M7).
 *
 * <p>Its own table rather than columns on the four subject tables, so that maintaining it is one method
 * rather than four. The histogram is here because a subject page wants the distribution and five small
 * integers beat a GROUP BY per page view.
 *
 * <p><strong>One writer.</strong> {@code RatingService.recompute} is the only thing that touches these rows,
 * and it recomputes from the ratings rather than incrementing — an increment that is missed once is wrong
 * for ever, and a recompute over one subject's ratings is cheap.
 */
@Entity
@Table(name = "rating_summaries")
@IdClass(RatingSummary.Key.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class RatingSummary {

    @Id @Column(name = "subject_type", length = 24) private String subjectType;
    @Id @Column(name = "subject_id") private Long subjectId;

    @Column(name = "rating_count", nullable = false) @Builder.Default private Integer ratingCount = 0;
    @Column(name = "average_score", precision = 3, scale = 2) private BigDecimal averageScore;

    @Column(name = "score_1", nullable = false) @Builder.Default private Integer score1 = 0;
    @Column(name = "score_2", nullable = false) @Builder.Default private Integer score2 = 0;
    @Column(name = "score_3", nullable = false) @Builder.Default private Integer score3 = 0;
    @Column(name = "score_4", nullable = false) @Builder.Default private Integer score4 = 0;
    @Column(name = "score_5", nullable = false) @Builder.Default private Integer score5 = 0;

    @Column(name = "updated_at", nullable = false) @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @EqualsAndHashCode
    public static class Key implements Serializable {
        private String subjectType;
        private Long subjectId;
    }
}
