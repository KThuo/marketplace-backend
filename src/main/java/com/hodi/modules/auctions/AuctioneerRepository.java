package com.hodi.modules.auctions;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface AuctioneerRepository
        extends JpaRepository<Auctioneer, Long>, JpaSpecificationExecutor<Auctioneer> {

    boolean existsByReference(String reference);

    Optional<Auctioneer> findByReference(String reference);
}
