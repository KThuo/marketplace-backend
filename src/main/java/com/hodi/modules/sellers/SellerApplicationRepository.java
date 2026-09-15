package com.hodi.modules.sellers;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SellerApplicationRepository
        extends JpaRepository<SellerApplication, Long>, JpaSpecificationExecutor<SellerApplication> {

    Optional<SellerApplication> findByReference(String reference);

    /**
     * The one application this person has in flight.
     *
     * <p>At most one can exist — a partial unique index enforces it — so this is an Optional. A second open
     * application is either a mistake or an attempt to get a different answer from a different reviewer.
     */
    @Query("select a from SellerApplication a where a.userId = :userId "
            + "and a.state in ('DRAFT', 'SUBMITTED', 'MORE_INFO')")
    Optional<SellerApplication> findOpenFor(@Param("userId") Long userId);

    /** Their most recent, whatever state it reached — for somebody returning after a decision. */
    Optional<SellerApplication> findFirstByUserIdOrderByIdDesc(Long userId);

    @Query("select count(a) from SellerApplication a where a.state = 'SUBMITTED'")
    long countWaiting();
}
