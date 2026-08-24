package com.hodi.modules.usergroups;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserGroupRepository
        extends JpaRepository<UserGroup, Long>, JpaSpecificationExecutor<UserGroup> {

    /** The seeded global templates every organisation can see and clone. */
    @Query("select g from UserGroup g where g.tenantId is null and g.institutionId is null "
            + "and g.template = true and g.status <> 5 order by g.userTypeCode, g.name")
    List<UserGroup> findTemplates();

    /** How many live groups are bound to one user type — the user-type list's second usage column. */
    @Query("select count(g) from UserGroup g where g.userTypeCode = :code "
            + "and g.status <> 5 and g.status <> 4")
    long countLiveByUserTypeCode(@Param("code") String code);

    Optional<UserGroup> findByNameAndTenantId(String name, Long tenantId);

    Optional<UserGroup> findByNameAndInstitutionId(String name, Long institutionId);

    /** A platform-owned group by name — templates and platform roles both live with no organisation. */
    @Query("select g from UserGroup g where g.name = :name "
            + "and g.tenantId is null and g.institutionId is null")
    Optional<UserGroup> findGlobalByName(@Param("name") String name);

    /**
     * The system group for one organisation — the one that carries everything and must always retain a
     * live member.
     */
    @Query("select g from UserGroup g where g.system = true and g.tenantId = :tenantId")
    Optional<UserGroup> findSystemGroupForTenant(@Param("tenantId") Long tenantId);

    @Query("select g from UserGroup g where g.system = true and g.institutionId = :institutionId")
    Optional<UserGroup> findSystemGroupForInstitution(@Param("institutionId") Long institutionId);

    /**
     * Groups an organisation may assign to a user: their own, plus the global templates.
     *
     * <p>One query rather than two calls the caller merges, because "which groups can I pick" is one
     * question and answering it in two places is how the two answers diverge.
     */
    @Query("select g from UserGroup g where g.status <> 5 and g.userTypeCode = :userTypeCode "
            + "and (g.tenantId = :tenantId or g.institutionId = :institutionId "
            + "     or (g.tenantId is null and g.institutionId is null and g.template = true)) "
            + "order by g.name")
    List<UserGroup> findAssignable(@Param("userTypeCode") String userTypeCode,
                                   @Param("tenantId") Long tenantId,
                                   @Param("institutionId") Long institutionId);
}
