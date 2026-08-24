package com.hodi.modules.finance;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface AffordabilityCheckRepository
        extends JpaRepository<AffordabilityCheck, Long>, JpaSpecificationExecutor<AffordabilityCheck> {

    boolean existsByReference(String reference);

    /**
     * The person's own, by reference.
     *
     * <p>The user id is in the query rather than checked after loading — the shape that leaks somebody else's
     * row the day a branch is refactored. A wrong reference and another person's reference are both a 404,
     * which is also the answer that says least.
     */
    @Query("select c from AffordabilityCheck c where c.reference = :reference and c.userId = :userId "
            + "and c.status <> 5")
    Optional<AffordabilityCheck> findMineByReference(@Param("reference") String reference,
                                                     @Param("userId") Long userId);

    @Query("select c from AffordabilityCheck c where c.userId = :userId and c.status <> 5")
    Page<AffordabilityCheck> findMine(@Param("userId") Long userId, Pageable pageable);

    long countByUserId(Long userId);
}
