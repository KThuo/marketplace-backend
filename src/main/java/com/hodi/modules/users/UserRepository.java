package com.hodi.modules.users;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    /**
     * Login accepts a username or an email, which is what the form offers.
     *
     * <p>Both are unique across the whole table, so one query serves all four populations — a buyer, a
     * seller's agent and a super admin sign in through the same path.
     */
    @Query("select u from User u where lower(u.username) = lower(:username) or u.email = :email")
    Optional<User> findByUsernameIgnoreCaseOrEmail(@Param("username") String username,
                                                   @Param("email") String email);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByUsernameIgnoreCase(String username);

    /**
     * How many members of one group are still in force, for the lock-out guard.
     *
     * <p>{@code status <> 5} plus {@code enabled} rather than {@code status = 1}: every update stamps
     * STATUS_EDITED, so counting only ACTIVE rows would report zero the moment somebody edited the last
     * owner's phone number, and the guard would then refuse a legitimate edit.
     */
    @Query("select count(u) from User u where u.userGroupId = :groupId "
            + "and u.enabled = true and u.status <> 5 and u.status <> 4")
    long countLiveMembers(@Param("groupId") Long groupId);

    /**
     * How many live users hold one user type — for the user-type list's usage column and its
     * in-use guard.
     *
     * <p>A query rather than a filter over {@code findAll()}: the list renders a page of types and this is
     * evaluated once per row, so the scan-per-row version turned one screen into a full table scan per type.
     */
    @Query("select count(u) from User u where u.userTypeCode = :code "
            + "and u.status <> 5 and u.status <> 4")
    long countLiveByUserTypeCode(@Param("code") String code);

    long countByTenantIdAndStatusNot(Long tenantId, Integer status);

    long countByInstitutionIdAndStatusNot(Long institutionId, Integer status);

    /**
     * Every staff account belonging to one seller, for the suspension cascade.
     *
     * <p>Suspending a seller has to reach their people: leaving the accounts signed-in-able would make
     * "suspended" a label on a row rather than a state of the business.
     */
    @Query("select u from User u where u.tenantId = :tenantId and u.status <> 5")
    java.util.List<User> findLiveByTenant(@Param("tenantId") Long tenantId);

    /** The same, for a lending institution — used when an institution is deactivated. */
    @Query("select u from User u where u.institutionId = :institutionId and u.status <> 5")
    java.util.List<User> findLiveByInstitution(@Param("institutionId") Long institutionId);

    /**
     * Re-stamps the denormalised group name on every member after a group is renamed.
     *
     * <p>One writer for this label cache, living in {@code UserGroupService}'s update path — the rule from
     * the denormalisation convention. An ad-hoc UPDATE elsewhere is how the copy starts disagreeing with
     * the owner.
     */
    @Modifying
    @Query("update User u set u.userGroupName = :name where u.userGroupId = :groupId")
    int renameGroupLabel(@Param("groupId") Long groupId, @Param("name") String name);

    /** The same, for a renamed seller organisation. */
    @Modifying
    @Query("update User u set u.tenantName = :name where u.tenantId = :tenantId")
    int renameTenantLabel(@Param("tenantId") Long tenantId, @Param("name") String name);

    /** The same, for a renamed lending institution. */
    @Modifying
    @Query("update User u set u.institutionName = :name where u.institutionId = :institutionId")
    int renameInstitutionLabel(@Param("institutionId") Long institutionId, @Param("name") String name);
}
