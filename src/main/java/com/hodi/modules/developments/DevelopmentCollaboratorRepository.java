package com.hodi.modules.developments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface DevelopmentCollaboratorRepository extends JpaRepository<DevelopmentCollaborator, Long> {

    /** Every grant on a development, revoked ones included — the panel shows history, not just the live set. */
    @Query("select c from DevelopmentCollaborator c where c.developmentId = :developmentId "
            + "and c.status <> 5 order by c.grantedAt desc")
    List<DevelopmentCollaborator> findForDevelopment(@Param("developmentId") Long developmentId);

    /**
     * The authorisation lookup: has this organisation been granted anything on this development?
     *
     * <p>Live grants only, and the query says so rather than the caller filtering — a revoked grant that
     * reaches a permission check is a right somebody took away and got back.
     */
    @Query("select c from DevelopmentCollaborator c where c.developmentId = :developmentId "
            + "and c.tenantId = :tenantId and c.revokedAt is null and c.status <> 5")
    Optional<DevelopmentCollaborator> findLiveGrant(@Param("developmentId") Long developmentId,
                                                    @Param("tenantId") Long tenantId);

    /** The developments an organisation may reach through a grant. Feeds the visibility specification. */
    @Query("select c.developmentId from DevelopmentCollaborator c where c.tenantId = :tenantId "
            + "and c.revokedAt is null and c.status <> 5")
    List<Long> developmentIdsFor(@Param("tenantId") Long tenantId);
}
