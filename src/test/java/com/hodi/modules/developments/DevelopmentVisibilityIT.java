package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.users.User;
import com.hodi.security.principal.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who can see whose development.
 *
 * <p>The most consequential test in this module. Every other table in the schema gets its row scoping from
 * {@code TenantScope} — one choke point, hard to forget. A development cannot use it, because a lending
 * institution may own one and the bank has no visible-tenant set. So the protection is a rule somebody has to
 * apply, and a repository query written without it reads across organisations.
 *
 * <p>Which is why the assertions here are mostly about what is <em>not</em> visible. A test that only proves an
 * owner can see their own would pass against a specification that returns everything.
 */
@SpringBootTest
@Transactional
class DevelopmentVisibilityIT {

    @Autowired DevelopmentVisibility visibility;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentCollaboratorRepository collaborators;
    @Autowired JdbcTemplate jdbc;

    private List<Long> tenantIds() {
        return jdbc.queryForList(
                "select id from tenants where status <> 5 order by id limit 3", Long.class);
    }

    private Long institutionId() {
        return jdbc.queryForObject("select id from banks order by id limit 1", Long.class);
    }

    private Development owned(Long tenantId, Long institutionId, Long sellingTenantId, String state) {
        return developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV"))
                .tenantId(tenantId)
                .institutionId(institutionId)
                .sellingTenantId(sellingTenantId)
                .name("Project " + RrnGenerator.payCode())
                .developmentType("APARTMENT")
                .listingState(state)
                .build());
    }

    /**
     * A principal, assembled the way the application assembles one.
     *
     * <p>{@code UserPrincipal} has no builder and no setters on purpose — it is derived from a user and a
     * profile, and the actor class it authorises on comes from {@code profile.profileType}. Building one here
     * from those two rather than mocking the getters means the test exercises the same derivation the login
     * path does, including the fact that the bank's tenantId is null because their profile carries an
     * institution instead.
     */
    private UserPrincipal principal(Long userId, String profileType, String userTypeCode,
                                    Long tenantId, Long institutionId) {
        User user = User.builder()
                .id(userId).username("test-" + userId).password("x").email("t@example.invalid")
                .firstName("Test").lastName("Caller").status(AppConstant.STATUS_ACTIVE)
                .enabled(true).build();
        UserProfile profile = UserProfile.builder()
                .id(userId).userId(userId).profileType(profileType).userTypeCode(userTypeCode)
                .tenantId(tenantId).institutionId(institutionId)
                .status(AppConstant.STATUS_ACTIVE).build();
        return UserPrincipal.of(user, profile, Set.of(),
                tenantId == null ? List.of() : List.of(tenantId), false, true);
    }

    private UserPrincipal seller(Long tenantId) {
        return principal(1L, AppConstant.ACTOR_SELLER, "SELLER_OWNER", tenantId, null);
    }

    /**
     * A caller bound to the bank that owns the project.
     *
     * <p>PLATFORM with `unrestricted` false, which is a shape no sign-in produces — and that is the point.
     * What these tests exercise is the institution ownership axis, and DevelopmentVisibility decides it on
     * `institutionId` rather than on an actor class, so the axis is still covered now that no user type is
     * bound to an institution.
     */
    private UserPrincipal bankStaff(Long institutionId) {
        return principal(2L, AppConstant.ACTOR_PLATFORM, "SUPER_ADMIN", null, institutionId);
    }

    private UserPrincipal platform() {
        return principal(3L, AppConstant.ACTOR_PLATFORM, "SUPER_ADMIN", null, null);
    }

    private UserPrincipal buyer() {
        return principal(4L, AppConstant.ACTOR_BUYER, "BUYER", null, null);
    }

    private long visibleCount(UserPrincipal caller) {
        var spec = visibility.mine(caller);
        return spec == null ? developments.count() : developments.count(spec);
    }

    @Test
    @DisplayName("a seller sees their own development and not another seller's")
    void sellerSeesOwnOnly() {
        List<Long> tenants = tenantIds();
        Long mine = tenants.getFirst();
        Long theirs = tenants.get(1);

        Development ours = owned(mine, null, mine, AppConstant.LISTING_DRAFT);
        Development notOurs = owned(theirs, null, theirs, AppConstant.LISTING_DRAFT);

        var spec = visibility.mine(seller(mine));
        List<Development> visible = developments.findAll(spec);

        assertTrue(visible.stream().anyMatch(d -> d.getId().equals(ours.getId())));
        assertFalse(visible.stream().anyMatch(d -> d.getId().equals(notOurs.getId())),
                "another organisation's development must not be in the list");
        assertTrue(visibility.mayRead(ours, seller(mine)));
        assertFalse(visibility.mayRead(notOurs, seller(mine)));
    }

    @Test
    @DisplayName("a bank sees the project it financed; the developer who is not on it does not")
    void institutionOwned() {
        List<Long> tenants = tenantIds();
        Long bankProjectDeveloper = tenants.getFirst();
        Long unrelated = tenants.get(1);
        Long bank = institutionId();

        Development financed = owned(null, bank, null, AppConstant.DEV_STATE_PRIVATE);

        assertTrue(visibility.mayRead(financed, bankStaff(bank)));
        assertFalse(visibility.mayRead(financed, seller(unrelated)),
                "a private financed project is not visible to an unrelated seller");
        assertFalse(visibility.mayRead(financed, seller(bankProjectDeveloper)),
                "not even to the developer, until they are granted something");
    }

    @Test
    @DisplayName("a granted collaborator sees the bank's project — and only the one they were granted")
    void collaboratorGrant() {
        List<Long> tenants = tenantIds();
        Long developer = tenants.getFirst();
        Long bank = institutionId();

        Development granted = owned(null, bank, null, AppConstant.DEV_STATE_PRIVATE);
        Development otherBankProject = owned(null, bank, null, AppConstant.DEV_STATE_PRIVATE);

        collaborators.save(DevelopmentCollaborator.builder()
                .developmentId(granted.getId())
                .tenantId(developer)
                .rights(AppConstant.COLLAB_PROGRESS_WRITE)
                .build());

        assertTrue(visibility.mayRead(granted, seller(developer)));
        assertFalse(visibility.mayRead(otherBankProject, seller(developer)),
                "a grant is per development, not per bank");

        List<Development> visible = developments.findAll(visibility.mine(seller(developer)));
        assertTrue(visible.stream().anyMatch(d -> d.getId().equals(granted.getId())));
        assertFalse(visible.stream().anyMatch(d -> d.getId().equals(otherBankProject.getId())));
    }

    @Test
    @DisplayName("revoking a grant takes effect on the next query, with nothing to clean up")
    void revokedGrant() {
        List<Long> tenants = tenantIds();
        Long developer = tenants.getFirst();
        Long bank = institutionId();
        Development project = owned(null, bank, null, AppConstant.DEV_STATE_PRIVATE);

        DevelopmentCollaborator grant = collaborators.save(DevelopmentCollaborator.builder()
                .developmentId(project.getId()).tenantId(developer)
                .rights(AppConstant.COLLAB_PROGRESS_WRITE).build());
        assertTrue(visibility.mayRead(project, seller(developer)));

        grant.setRevokedAt(java.time.OffsetDateTime.now());
        grant.setRevokedReason("Contract ended");
        collaborators.save(grant);

        assertFalse(visibility.mayRead(project, seller(developer)),
                "a revoked grant is not a grant");
    }

    @Test
    @DisplayName("the seller marketing somebody else's units sees the development behind them")
    void sellingTenantSees() {
        List<Long> tenants = tenantIds();
        Long marketer = tenants.getFirst();
        Long bank = institutionId();

        Development financedAndMarketed = owned(null, bank, marketer, AppConstant.LISTING_DRAFT);
        assertTrue(visibility.mayRead(financedAndMarketed, seller(marketer)));
    }

    @Test
    @DisplayName("platform staff see everything; a buyer sees nothing")
    void platformAndBuyer() {
        List<Long> tenants = tenantIds();
        owned(tenants.getFirst(), null, tenants.getFirst(), AppConstant.LISTING_DRAFT);

        assertEquals(null, visibility.mine(platform()), "unrestricted is expressed as no predicate");

        // A buyer belongs to no organisation, so the honest answer to "which are yours" is none — and it must
        // be none rather than an unscoped query, which is what an empty predicate list would have produced.
        assertEquals(0, visibleCount(buyer()), "a buyer sees no developments at all");
        assertFalse(visibility.mayRead(
                owned(tenants.getFirst(), null, null, AppConstant.LISTING_DRAFT), buyer()));
    }

    @Test
    @DisplayName("managing is ownership, and a collaborator with progress rights does not have it")
    void manageIsNarrowerThanRead() {
        List<Long> tenants = tenantIds();
        Long developer = tenants.getFirst();
        Long bank = institutionId();
        Development project = owned(null, bank, null, AppConstant.DEV_STATE_PRIVATE);
        collaborators.save(DevelopmentCollaborator.builder()
                .developmentId(project.getId()).tenantId(developer)
                .rights(AppConstant.COLLAB_PROGRESS_WRITE).build());

        // May post progress…
        visibility.assertMayWriteProgress(project, seller(developer));
        // …and may not rename the project or touch its budget.
        assertThrows(HodiException.class, () -> visibility.assertMayManage(project, seller(developer)));
        // …and a progress grant is not a units grant.
        assertThrows(HodiException.class, () -> visibility.assertMayWriteUnits(project, seller(developer)));

        // The owner may do all three.
        visibility.assertMayManage(project, bankStaff(bank));
        visibility.assertMayWriteUnits(project, bankStaff(bank));
        visibility.assertMayWriteProgress(project, bankStaff(bank));
    }

    @Test
    @DisplayName("a private or draft development may not publish progress")
    void publishGate() {
        List<Long> tenants = tenantIds();
        Long bank = institutionId();
        assertFalse(visibility.mayPublishProgress(
                owned(null, bank, null, AppConstant.DEV_STATE_PRIVATE)),
                "a tracked project is nobody's business but its owner's");
        assertFalse(visibility.mayPublishProgress(
                owned(tenants.getFirst(), null, tenants.getFirst(), AppConstant.LISTING_DRAFT)));
        assertTrue(visibility.mayPublishProgress(
                owned(tenants.getFirst(), null, tenants.getFirst(), AppConstant.LISTING_PENDING)));
    }
}
