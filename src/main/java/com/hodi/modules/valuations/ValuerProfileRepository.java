package com.hodi.modules.valuations;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ValuerProfileRepository
        extends JpaRepository<ValuerProfile, Long>, JpaSpecificationExecutor<ValuerProfile> {

    boolean existsByReference(String reference);

    Optional<ValuerProfile> findByReference(String reference);

    /** The caller's own panel row, resolved from the profile their token names. */
    Optional<ValuerProfile> findByProfileId(Long profileId);

    /**
     * Everybody who could take work, least loaded first.
     *
     * <p>The database narrows to panel membership and lifecycle; the professional-indemnity and county rules
     * are applied in the service, because both are per-job comparisons rather than properties of the row.
     * Ordered by open assignments then by who has waited longest, which is the whole of the round-robin.
     */
    @Query("select v from ValuerProfile v where v.onPanel = true and v.status <> 4 and v.status <> 5 "
            + "order by v.openAssignments asc, v.lastAssignedAt asc nulls first")
    List<ValuerProfile> findAvailable();

    /**
     * Panel members whose cover or registration runs out on or before the horizon.
     *
     * <p>Whether each has already been warned about that date is the service's question, because it is a
     * comparison between two columns of the same row and reads better said in Java than in JPQL.
     */
    @Query("select v from ValuerProfile v where v.onPanel = true and v.status <> 4 and v.status <> 5 "
            + "and (v.piExpiresOn <= :horizon or v.registeredUntil <= :horizon)")
    List<ValuerProfile> findLapsingBy(@Param("horizon") LocalDate horizon);
}
