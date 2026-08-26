package com.hodi.modules.ratings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RatingSummaryRepository extends JpaRepository<RatingSummary, RatingSummary.Key> {

    Optional<RatingSummary> findBySubjectTypeAndSubjectId(String subjectType, Long subjectId);

    /** For a list of cards: one query for the whole page rather than one per card. */
    List<RatingSummary> findBySubjectTypeAndSubjectIdIn(String subjectType, List<Long> subjectIds);
}
