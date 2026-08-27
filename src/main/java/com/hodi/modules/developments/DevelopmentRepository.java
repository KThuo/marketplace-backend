package com.hodi.modules.developments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DevelopmentRepository
        extends JpaRepository<Development, Long>, JpaSpecificationExecutor<Development> {

    boolean existsByReference(String reference);

    Optional<Development> findByReference(String reference);

    /**
     * By reference, and only if it is live.
     *
     * <p>The public page's lookup, scoped in the query rather than checked after loading — "fetch it, then
     * decide whether to show it" is the shape that leaks a draft the day somebody forgets the second half.
     * PRIVATE is excluded by construction here, which is what keeps a bank's financed project off every
     * public surface without a second flag to remember.
     */
    @Query("select d from Development d where d.reference = :reference "
            + "and d.listingState = 'LIVE' and d.status <> 5")
    Optional<Development> findLiveByReference(@Param("reference") String reference);

    @Query("select count(d) from Development d where d.tenantId = :tenantId and d.status <> 5")
    long countForTenant(@Param("tenantId") Long tenantId);

    @Query("select count(d) from Development d where d.institutionId = :institutionId and d.status <> 5")
    long countForInstitution(@Param("institutionId") Long institutionId);

    /** Re-stamps the denormalised owner name after a rename, mirroring PropertyRepository's. */
    @Modifying
    @Query("update Development d set d.tenantName = :name where d.tenantId = :tenantId")
    int renameTenantLabel(@Param("tenantId") Long tenantId, @Param("name") String name);

    @Modifying
    @Query("update Development d set d.institutionName = :name where d.institutionId = :institutionId")
    int renameInstitutionLabel(@Param("institutionId") Long institutionId, @Param("name") String name);
}
