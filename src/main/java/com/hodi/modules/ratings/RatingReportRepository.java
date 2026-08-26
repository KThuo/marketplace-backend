package com.hodi.modules.ratings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RatingReportRepository extends JpaRepository<RatingReport, Long> {

    boolean existsByRatingIdAndUserId(Long ratingId, Long userId);

    List<RatingReport> findByRatingIdOrderByCreatedAtDesc(Long ratingId);

    long countByRatingId(Long ratingId);
}
