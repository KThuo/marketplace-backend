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
     * Every organisation's owner group, for the seeder's top-up.
     *
     * <p>Includes the global system groups (platform, buyer); the caller skips those, because each has its
     * own rule about what it may hold and neither is an organisation's.
     */
    @Query("select g from UserGroup g where g.system = true and g.status <> 5")
    List<UserGroup> findSystemGroups();

    /**
     * Groups a caller may assign to a user of one type: their own organisation's, plus what is global.
     *
     * <p>One query rather than two calls the caller merges, because "which groups can I pick" is one
     * question and answering it in two places is how the two answers diverge.
     *
     * <p><strong>Platform staff have no organisation, and that used to mean no groups at all.</strong> The
     * first two branches compare against a null tenant and a null institution, which in SQL matches nothing,
     * so a platform administrator only ever saw the global <em>templates</em> — never "Platform Super Admin",
     * the very group the seeder creates for them. Creating platform staff was therefore impossible without
     * first cloning a template, and nothing said so. A caller with no organisation now sees the global groups
     * whether or not they are templates; assigning a template is still refused downstream, because a template
     * is a shape to clone rather than a role to hold.
     */
    @Query("select g from UserGroup g where g.status <> 5 and g.userTypeCode = :userTypeCode "
            + "and ((:tenantId is not null and g.tenantId = :tenantId) "
            + "     or (:institutionId is not null and g.institutionId = :institutionId) "
            + "     or (g.tenantId is null and g.institutionId is null "
            + "         and (g.template = true "
            + "              or (:tenantId is null and :institutionId is null)))) "
            + "order by g.name")
    List<UserGroup> findAssignable(@Param("userTypeCode") String userTypeCode,
                                   @Param("tenantId") Long tenantId,
                                   @Param("institutionId") Long institutionId);
}
