package com.hodi.modules.buyerportal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SavedListingRepository extends JpaRepository<SavedListing, Long> {

    /** Ordering comes from the {@link Pageable}, so the caller and the query cannot disagree about it. */
    Page<SavedListing> findByUserId(Long userId, Pageable pageable);

    Optional<SavedListing> findByUserIdAndPropertyId(Long userId, Long propertyId);

    long countByUserId(Long userId);

    /**
     * Which of these properties this person has already saved.
     *
     * <p>One query for a whole page of search results, so the marketplace can draw the hearts filled without
     * asking twenty times. Returns ids rather than rows because the caller only needs membership.
     */
    @Query("select s.propertyId from SavedListing s "
            + "where s.userId = :userId and s.propertyId in :propertyIds")
    List<Long> savedAmong(@Param("userId") Long userId,
                          @Param("propertyIds") Collection<Long> propertyIds);
}
