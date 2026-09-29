package com.hodi.modules.valuations;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ValuationRequestRepository
        extends JpaRepository<ValuationRequest, Long>, JpaSpecificationExecutor<ValuationRequest> {

    boolean existsByReference(String reference);

    /**
     * By reference, unscoped.
     *
     * <p>Every caller of this must follow it with {@code ValuationScope.assertVisible} — the scope lives one
     * line away rather than in the query because the rule needs the caller's valuer profile, which a
     * repository has no business resolving.
     */
    Optional<ValuationRequest> findByReference(String reference);

    @Query("select count(r) from ValuationRequest r where r.state = 'REQUESTED' and r.status <> 5")
    long countUnassigned();

    @Query("select count(r) from ValuationRequest r where r.valuerProfileId = :valuerProfileId "
            + "and r.state in ('ASSIGNED', 'IN_PROGRESS') and r.status <> 5")
    long countOpenForValuer(@Param("valuerProfileId") Long valuerProfileId);

    /** Completed valuations of one property, newest first. The caller takes the first. */
    @Query("select r from ValuationRequest r where r.propertyId = :propertyId and r.state = 'COMPLETED' "
            + "and r.status <> 5 order by r.completedAt desc")
    List<ValuationRequest> findLatestCompletedForProperty(@Param("propertyId") Long propertyId);

    /** Open jobs whose due date has passed and nobody has yet been told. */
    @Query("select r from ValuationRequest r where r.dueOn < :today and r.overdueNoticedOn is null "
            + "and r.state in ('REQUESTED', 'ASSIGNED', 'IN_PROGRESS') and r.status <> 5 order by r.dueOn asc")
    List<ValuationRequest> findOverdueUnnoticed(@Param("today") LocalDate today);
}
