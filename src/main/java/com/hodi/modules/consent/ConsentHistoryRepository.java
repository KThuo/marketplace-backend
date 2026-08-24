package com.hodi.modules.consent;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Reads only.
 *
 * <p>{@code JpaRepository} brings save and delete with it and both would be refused by the table's own
 * triggers. They are left unhidden rather than papered over with a narrower interface, because the failure
 * mode should be a database error naming the rule, not a compile error that a future author works around.
 */
public interface ConsentHistoryRepository extends JpaRepository<ConsentPreferenceHistory, Long> {

    Page<ConsentPreferenceHistory> findByUserIdOrderByChangedAtDesc(Long userId, Pageable pageable);
}
