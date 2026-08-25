package com.hodi.modules.auctions;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AuctionLotRepository
        extends JpaRepository<AuctionLot, Long>, JpaSpecificationExecutor<AuctionLot> {

    boolean existsByReference(String reference);

    Optional<AuctionLot> findByReference(String reference);

    /**
     * By reference, and only if it is in the public catalogue.
     *
     * <p>The auction surface's own {@code findLiveByReference}. Scoped in the query rather than checked
     * afterwards, for the same reason the marketplace's is: "load it, then decide whether to show it" leaks
     * a draft the day somebody forgets the second half.
     */
    @Query("select l from AuctionLot l where l.reference = :reference "
            + "and l.state = 'SCHEDULED' and l.status <> 5")
    Optional<AuctionLot> findPublicByReference(@Param("reference") String reference);

    @Query("select count(l) from AuctionLot l where l.state = 'SCHEDULED' and l.status <> 5")
    long countScheduled();

    /** The distinct counties with lots coming up, for the catalogue's filter. */
    @Query("select distinct l.county from AuctionLot l where l.state = 'SCHEDULED' and l.status <> 5 "
            + "and l.county is not null order by l.county")
    List<String> publicCounties();

    /** Re-stamps the denormalised auctioneer name after a rename. One writer, in AuctioneerService. */
    @Modifying
    @Query("update AuctionLot l set l.auctioneerName = :name where l.auctioneerId = :auctioneerId")
    int renameAuctioneerLabel(@Param("auctioneerId") Long auctioneerId, @Param("name") String name);
}
