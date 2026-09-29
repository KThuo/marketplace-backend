package com.hodi.modules.profiles;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Profiles, and the counts and label rewrites that used to live on {@code UserRepository}.
 *
 * <p>Everything here filters on {@code status <> 5} and usually {@code <> 4} as well, for the reason the
 * whole codebase does: every update stamps STATUS_EDITED, so a query written against {@code status = 1}
 * reports zero the first time somebody edits a row.
 */
public interface UserProfileRepository
        extends JpaRepository<UserProfile, Long>, JpaSpecificationExecutor<UserProfile> {

    /**
     * Every profile a person holds, newest last.
     *
     * <p>Ordered by id so the switcher's list does not reshuffle between calls — a menu whose entries move
     * is a menu people mis-click.
     */
    @Query("select p from UserProfile p where p.userId = :userId and p.status <> 5 "
            + "order by p.defaultProfile desc, p.id asc")
    List<UserProfile> findLiveForUser(@Param("userId") Long userId);

    /** Every live profile of a page of people, in one statement. */
    @Query("select p from UserProfile p where p.userId in :userIds and p.status <> 5 "
            + "order by p.userId asc, p.defaultProfile desc, p.id asc")
    List<UserProfile> findLiveForUsers(@Param("userIds") java.util.Collection<Long> userIds);

    @Query("select p from UserProfile p where p.userId = :userId and p.defaultProfile = true "
            + "and p.status <> 5")
    Optional<UserProfile> findDefaultForUser(@Param("userId") Long userId);

    Optional<UserProfile> findByIdAndUserId(Long id, Long userId);

    /** How many members of one group are still in force, for the lock-out guard. */
    @Query("select count(p) from UserProfile p where p.userGroupId = :groupId "
            + "and p.status <> 5 and p.status <> 4")
    long countLiveMembers(@Param("groupId") Long groupId);

    /** The live members of one group, oldest first — what round-robin assignment walks (M12). */
    @Query("select p from UserProfile p where p.userGroupId = :groupId and p.status not in (4, 5) "
            + "order by p.userId asc")
    List<UserProfile> findLiveByGroup(@Param("groupId") Long groupId);

    /** How many live profiles hold one user type — the type list's usage column and its in-use guard. */
    @Query("select count(p) from UserProfile p where p.userTypeCode = :code "
            + "and p.status <> 5 and p.status <> 4")
    long countLiveByUserTypeCode(@Param("code") String code);

    @Query("select count(p) from UserProfile p where p.tenantId = :tenantId and p.status <> :status")
    long countByTenant(@Param("tenantId") Long tenantId, @Param("status") Integer status);

    @Query("select count(p) from UserProfile p where p.institutionId = :institutionId "
            + "and p.status <> :status")
    long countByInstitution(@Param("institutionId") Long institutionId,
                            @Param("status") Integer status);

    /**
     * The people to sign out when an organisation is suspended.
     *
     * <p>User ids rather than profiles: revoking sessions is done per person, and a profile is not a session.
     * Distinct because one person could hold two profiles in the same organisation once agents arrive.
     */
    @Query("select distinct p.userId from UserProfile p where p.tenantId = :tenantId and p.status <> 5")
    List<Long> findLiveUserIdsByTenant(@Param("tenantId") Long tenantId);

    @Query("select distinct p.userId from UserProfile p where p.institutionId = :institutionId "
            + "and p.status <> 5")
    List<Long> findLiveUserIdsByInstitution(@Param("institutionId") Long institutionId);

    /**
     * The platform's own people who hold one permission — the reviewers, the assigners, the panel's managers.
     *
     * <p>Through the group rather than the resolver, because this is a fan-out for a notice, not an access
     * decision: a live platform profile whose live group grants the action. The module axis does not apply to
     * the platform, so nothing is lost by not consulting it here.
     */
    @Query("select distinct p.userId from UserProfile p, com.hodi.modules.usergroups.UserGroup g "
            + "join g.permissions perm where g.id = p.userGroupId and perm.actionCode = :actionCode "
            + "and p.tenantId is null and p.institutionId is null and p.profileType = 'PLATFORM' "
            + "and p.status not in (4, 5) and g.status not in (4, 5) and perm.status not in (4, 5)")
    List<Long> findLivePlatformUserIdsHolding(@Param("actionCode") String actionCode);

    /** An organisation's people who hold one permission — its checkers, for a reminder that a decision waits. */
    @Query("select distinct p.userId from UserProfile p, com.hodi.modules.usergroups.UserGroup g "
            + "join g.permissions perm where g.id = p.userGroupId and perm.actionCode = :actionCode "
            + "and ((:tenantId is not null and p.tenantId = :tenantId) or (:institutionId is not null and p.institutionId = :institutionId)) "
            + "and p.status not in (4, 5) and g.status not in (4, 5) and perm.status not in (4, 5)")
    List<Long> findLiveOrganisationUserIdsHolding(@Param("tenantId") Long tenantId, @Param("institutionId") Long institutionId,
                                                  @Param("actionCode") String actionCode);

    /**
     * Every live profile of one seller organisation.
     *
     * <p>KYC's writer: a decision about an organisation's pack lands on all of its people, not only the one
     * who assembled it — a colleague should be able to list a property the moment Compliance clears their
     * employer, and stop the moment it does not.
     */
    @Query("select p from UserProfile p where p.tenantId = :tenantId and p.status <> 4 and p.status <> 5")
    List<UserProfile> findLiveByTenant(@Param("tenantId") Long tenantId);

    /**
     * Re-stamps a renamed group's label on every profile holding it.
     *
     * <p>One writer per label cache, in the service that owns the renamed thing. An ad-hoc UPDATE elsewhere
     * is how a copy starts disagreeing with its owner.
     */
    @Modifying
    @Query("update UserProfile p set p.userGroupName = :name where p.userGroupId = :groupId")
    int renameGroupLabel(@Param("groupId") Long groupId, @Param("name") String name);

    @Modifying
    @Query("update UserProfile p set p.tenantName = :name where p.tenantId = :tenantId")
    int renameTenantLabel(@Param("tenantId") Long tenantId, @Param("name") String name);

    @Modifying
    @Query("update UserProfile p set p.institutionName = :name "
            + "where p.institutionId = :institutionId")
    int renameInstitutionLabel(@Param("institutionId") Long institutionId,
                               @Param("name") String name);

    /** Re-stamps a renamed user type's label. The code is part of the type's identity and never changes. */
    @Modifying
    @Query("update UserProfile p set p.userTypeName = :name where p.userTypeId = :typeId")
    int renameUserTypeLabel(@Param("typeId") Long typeId, @Param("name") String name);
}
