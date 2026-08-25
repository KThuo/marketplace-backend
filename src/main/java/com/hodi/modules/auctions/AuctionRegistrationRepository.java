package com.hodi.modules.auctions;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AuctionRegistrationRepository
        extends JpaRepository<AuctionRegistration, Long>, JpaSpecificationExecutor<AuctionRegistration> {

    boolean existsByReference(String reference);

    Optional<AuctionRegistration> findByReference(String reference);

    /** The bidder's own, by reference. Their identity is in the query, not checked after loading. */
    @Query("select r from AuctionRegistration r where r.reference = :reference and r.userId = :userId "
            + "and r.status <> 5")
    Optional<AuctionRegistration> findMineByReference(@Param("reference") String reference,
                                                      @Param("userId") Long userId);

    @Query("select r from AuctionRegistration r where r.userId = :userId and r.status <> 5")
    Page<AuctionRegistration> findMine(@Param("userId") Long userId, Pageable pageable);

    /** The one the partial unique index enforces: no second live registration on the same lot. */
    @Query("select r from AuctionRegistration r where r.userId = :userId and r.lotId = :lotId "
            + "and r.state in ('REGISTERED', 'APPROVED') and r.status <> 5")
    Optional<AuctionRegistration> findLiveFor(@Param("userId") Long userId, @Param("lotId") Long lotId);

    @Query("select count(r) from AuctionRegistration r where r.lotId = :lotId "
            + "and r.state = 'APPROVED' and r.status <> 5")
    long countApprovedForLot(@Param("lotId") Long lotId);
}
