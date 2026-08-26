package com.hodi.modules.sellerops;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ListingPromotionRepository
        extends JpaRepository<ListingPromotion, Long>, JpaSpecificationExecutor<ListingPromotion> {

    Optional<ListingPromotion> findByReference(String reference);

    /** The one a listing is already holding, if any. The unique index allows at most one. */
    @Query("select p from ListingPromotion p where p.propertyId = :propertyId "
            + "and p.state in ('REQUESTED', 'ACTIVE') and p.status <> 5")
    Optional<ListingPromotion> findLiveForProperty(@Param("propertyId") Long propertyId);

    /** Running promotions whose window has closed. What the sweeper expires. */
    @Query("select p from ListingPromotion p where p.state = 'ACTIVE' and p.endsAt < :now "
            + "and p.status <> 5")
    List<ListingPromotion> findFinished(@Param("now") OffsetDateTime now);

    long countByState(String state);
}
