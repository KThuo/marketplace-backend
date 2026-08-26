package com.hodi.modules.ratings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RatingRepository extends JpaRepository<Rating, Long>, JpaSpecificationExecutor<Rating> {

    boolean existsByReference(String reference);

    Optional<Rating> findByReference(String reference);

    Optional<Rating> findByUserIdAndSubjectTypeAndSubjectIdAndStatusNot(
            Long userId, String subjectType, Long subjectId, Integer status);

    /** Everything counted towards one subject's average. The recompute reads exactly this. */
    @Query("select r from Rating r where r.subjectType = :type and r.subjectId = :id "
            + "and r.state = 'PUBLISHED' and r.status <> 5")
    List<Rating> findCounted(@Param("type") String type, @Param("id") Long id);

    /**
     * Everything published about one organisation, across every subject type.
     *
     * <p>An organisation's headline figure is not any one subject's: a seller is rated on their listings and
     * on themselves, an agent on both plus their own record. Summing them is the only number that answers
     * "how are we doing".
     */
    @Query("select r from Rating r where r.subjectTenantId = :tenantId "
            + "and r.state = 'PUBLISHED' and r.status <> 5")
    List<Rating> findCountedForTenant(@Param("tenantId") Long tenantId);

    /** Everything published, anywhere. The platform's own headline figure. */
    @Query("select r from Rating r where r.state = 'PUBLISHED' and r.status <> 5")
    List<Rating> findAllCounted();

    @Query("select count(r) from Rating r where (r.state = 'HELD' or r.reportCount > 0) and r.status <> 5")
    long countNeedingModeration();
}
