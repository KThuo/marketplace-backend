package com.hodi.modules.users;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.approvals.ApprovalService.DecisionRequest;
import com.hodi.modules.approvals.ApprovalWorkflow;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.users.dto.UserDtos.CreateUserRequest;
import com.hodi.modules.users.dto.UserDtos.TemporaryPasswordResponse;
import com.hodi.modules.users.dto.UserDtos.UpdateUserRequest;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A created account cannot sign in until the bank says so.
 *
 * <p>What is worth testing here is not that a flag flips — it is the set of ways the gate could be got
 * round. {@code activate} would enable the account outright with a much commoner permission;
 * {@code deactivate} and {@code archive} would dispose of the account and leave a request pointing at it;
 * and the queue's own maker/checker rule has to hold for a user exactly as it does for a project.
 *
 * <p>Two principals are put in the context by hand, because the whole feature turns on who the caller is:
 * one platform user creates, another decides.
 */
@SpringBootTest
@Transactional
class UserApprovalIT {

    @Autowired UserService service;
    @Autowired UserRepository users;
    @Autowired ApprovalService approvals;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── the context ───────────────────────────────────────────────────────────

    /** A platform group whose user type is not the buyer's — anything creatable through this module. */
    private String staffGroupHash() {
        Long id = jdbc.queryForObject(
                "select g.id from user_groups g join user_types t on t.code = g.user_type_code "
                        + "where g.status <> 5 and g.tenant_id is null and g.institution_id is null "
                        + "and t.actor_class = 'PLATFORM' and coalesce(g.is_template, false) = false "
                        + "order by g.id limit 1", Long.class);
        assertNotNull(id, "no global platform group to create staff into");
        return HashIdUtil.encodeId(id);
    }

    private void signInAsPlatform(long id, String username) {
        signInAsPlatform(id, username, "BANK_ADMIN");
    }

    /**
     * @param userTypeCode the caller's kind. It decides the outcome of {@code create}: a SUPER_ADMIN's
     *                     creations go live immediately, everybody else's wait for the bank. The default
     *                     above is BANK_ADMIN, because that is the path these tests are about.
     */
    private void signInAsPlatform(long id, String username, String userTypeCode) {
        User user = User.builder().id(id).username(username).password("x")
                .email(username + "@example.invalid").firstName("Pat").lastName("Platform")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(id).userId(id)
                .profileType(AppConstant.ACTOR_PLATFORM).userTypeCode(userTypeCode)
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("USERS_CREATE", "USERS_UPDATE", "USERS_ACTIVATE", "USERS_DEACTIVATE",
                        "USERS_DELETE", "USERS_APPROVE"),
                List.of(), true, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /** The bank's second pair of eyes: platform staff, and not the person who submitted. */
    private void signInAsChecker() {
        signInAsPlatform(4242L, "checker");
    }

    private void signInAsMaker() {
        signInAsPlatform(4141L, "maker");
    }

    private CreateUserRequest newStaff(String handle) {
        return new CreateUserRequest("Ada", "Wanjiru", handle + "@example.invalid", handle,
                "+254700000000", staffGroupHash(), null, null);
    }

    /** The profile hash, which is what every endpoint on the user list is keyed by. */
    private String profileHashOf(String username) {
        Long profileId = jdbc.queryForObject(
                "select p.id from user_profiles p join users u on u.id = p.user_id "
                        + "where u.username = ?", Long.class, username);
        return HashIdUtil.encodeId(profileId);
    }

    private User reload(String username) {
        return users.findByUsernameIgnoreCaseOrEmail(username, username).orElseThrow();
    }

    private ApprovalWorkflow waitingFor(String username) {
        return approvals.pendingFor(AppConstant.APPROVAL_ENTITY_USER,
                reload(username).getId(), AppConstant.APPROVAL_ACTION_CREATE).orElseThrow();
    }

    // ── creation ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a created account is disabled and waiting, and the maker still gets the password")
    void createLeavesItWaiting() {
        signInAsMaker();

        TemporaryPasswordResponse issued = service.create(newStaff("ada.waiting"));

        // The credential is handed over regardless: issuing it and letting it through the door are two acts.
        assertNotNull(issued.temporaryPassword());
        assertFalse(issued.temporaryPassword().isBlank());
        assertTrue(issued.awaitingApproval());

        User created = reload("ada.waiting");
        assertFalse(created.isEnabled(), "a new account must not be able to sign in");
        assertEquals(AppConstant.STATUS_NEW, created.getStatus(),
                "NEW, not INACTIVE — never approved and switched off are different facts");
        assertTrue(created.isMustChangePassword());

        ApprovalWorkflow waiting = waitingFor("ada.waiting");
        assertEquals(AppConstant.APPROVAL_PENDING, waiting.getState());
        assertTrue(waiting.getSubjectLabel().contains("Ada Wanjiru"));
    }

    // ── the decision ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("approval lets them in")
    void approvalEnables() {
        signInAsMaker();
        service.create(newStaff("ada.approved"));
        ApprovalWorkflow waiting = waitingFor("ada.approved");

        signInAsChecker();
        approvals.decide(HashIdUtil.encodeId(waiting.getId()),
                new DecisionRequest(AppConstant.APPROVAL_APPROVED, null));

        User approved = reload("ada.approved");
        assertTrue(approved.isEnabled());
        assertEquals(AppConstant.STATUS_ACTIVE, approved.getStatus());
        // Approval is permission to use the temporary credential once, not permission to keep it.
        assertTrue(approved.isMustChangePassword());
    }

    @Test
    @DisplayName("rejection archives the account rather than leaving it switchable")
    void rejectionArchives() {
        signInAsMaker();
        service.create(newStaff("ada.rejected"));
        ApprovalWorkflow waiting = waitingFor("ada.rejected");

        signInAsChecker();
        approvals.decide(HashIdUtil.encodeId(waiting.getId()),
                new DecisionRequest(AppConstant.APPROVAL_REJECTED, "Not a member of staff."));

        User refused = reload("ada.rejected");
        assertFalse(refused.isEnabled());
        assertEquals(AppConstant.STATUS_DELETED, refused.getStatus(),
                "a refused account left merely disabled is one USERS_ACTIVATE could switch on");
        assertEquals("Not a member of staff.", refused.getDeactivationReason());
    }

    @Test
    @DisplayName("a send-back leaves the account alive, and editing it resubmits")
    void sendBackThenResubmit() {
        signInAsMaker();
        service.create(newStaff("ada.sentback"));
        ApprovalWorkflow first = waitingFor("ada.sentback");

        signInAsChecker();
        approvals.decide(HashIdUtil.encodeId(first.getId()),
                new DecisionRequest(AppConstant.APPROVAL_SENT_BACK, "Wrong group — she is an analyst."));

        User held = reload("ada.sentback");
        assertFalse(held.isEnabled());
        assertEquals(AppConstant.STATUS_NEW, held.getStatus(),
                "sent back is not rejected: there has to be something left to fix");
        assertTrue(approvals.pendingFor(AppConstant.APPROVAL_ENTITY_USER, held.getId(),
                AppConstant.APPROVAL_ACTION_CREATE).isEmpty());

        signInAsMaker();
        service.update(profileHashOf("ada.sentback"), new UpdateUserRequest(
                "Ada", "Wanjiru Njoroge", "ada.sentback@example.invalid", "+254700000000", null));

        ApprovalWorkflow again = waitingFor("ada.sentback");
        assertEquals(AppConstant.APPROVAL_PENDING, again.getState());
        assertTrue(reload("ada.sentback").getStatus() == AppConstant.STATUS_NEW,
                "an edit while waiting must not stamp EDITED over NEW");
    }

    // ── who may decide ────────────────────────────────────────────────────────

    @Test
    @DisplayName("the person who created the account cannot approve it")
    void makerCannotApproveTheirOwn() {
        signInAsMaker();
        service.create(newStaff("ada.selfcheck"));
        String hash = HashIdUtil.encodeId(waitingFor("ada.selfcheck").getId());

        HodiException refused = assertThrows(HodiException.class, () ->
                approvals.decide(hash, new DecisionRequest(AppConstant.APPROVAL_APPROVED, null)));
        assertTrue(refused.getMessage().contains("You submitted this"));
        assertFalse(reload("ada.selfcheck").isEnabled());
    }

    @Test
    @DisplayName("a seller cannot approve their own new staff")
    void onlyTheBankDecides() {
        signInAsMaker();
        service.create(newStaff("ada.sellercheck"));
        // The numeric id, not the hash. HashIdUtil salts with the current username, so a hash minted as
        // the maker decodes to a different number once the seller is signed in — which is how this test
        // first failed, and only in a full run where the sequence had climbed high enough to overflow.
        Long workflowId = waitingFor("ada.sellercheck").getId();

        Long tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        User user = User.builder().id(7777L).username("seller-owner").password("x")
                .email("owner@example.invalid").firstName("Sam").lastName("Seller")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(7777L).userId(7777L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenantId).status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal seller = UserPrincipal.of(user, profile,
                Set.of("USERS_APPROVE"), List.of(tenantId), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(seller, null, seller.getAuthorities()));

        assertThrows(HodiException.class, () -> approvals.decide(
                HashIdUtil.encodeId(workflowId), new DecisionRequest(AppConstant.APPROVAL_APPROVED, null)));
        assertFalse(reload("ada.sellercheck").isEnabled());
    }

    // ── where the rule runs out ───────────────────────────────────────────────

    /**
     * The super administrator's own creations go live, and raise nothing.
     *
     * <p>The case that forces it: on a fresh platform there is one account. It creates the first bank
     * user, and {@code ck_approval_maker_checker} means it cannot approve that user — so without this the
     * account sits disabled with nobody in existence who could let it in.
     */
    @Test
    @DisplayName("a super administrator's new user is live immediately")
    void superAdminCreationsAreLive() {
        signInAsPlatform(9001L, "superadmin-test", "SUPER_ADMIN");

        TemporaryPasswordResponse issued = service.create(newStaff("ada.super"));

        assertFalse(issued.awaitingApproval(), "the screen must not tell them to wait for an approval");
        User created = reload("ada.super");
        assertTrue(created.isEnabled());
        assertEquals(AppConstant.STATUS_ACTIVE, created.getStatus());
        // Still a temporary credential: going live is not the same as keeping the password they were given.
        assertTrue(created.isMustChangePassword());
        assertTrue(approvals.pendingFor(AppConstant.APPROVAL_ENTITY_USER, created.getId(),
                AppConstant.APPROVAL_ACTION_CREATE).isEmpty(),
                "nothing should be queued that nobody could ever decide");
    }

    @Test
    @DisplayName("a bank administrator's new user still waits")
    void bankAdminCreationsStillWait() {
        signInAsPlatform(9002L, "bankadmin-test", "BANK_ADMIN");

        service.create(newStaff("ada.bank"));

        assertFalse(reload("ada.bank").isEnabled());
        assertEquals(AppConstant.APPROVAL_PENDING, waitingFor("ada.bank").getState());
    }

    // ── the doors round the gate ──────────────────────────────────────────────

    @Test
    @DisplayName("activate does not let a waiting account in by the side entrance")
    void activateRefusesWhileWaiting() {
        signInAsMaker();
        service.create(newStaff("ada.sideentrance"));
        String hash = profileHashOf("ada.sideentrance");

        HodiException refused = assertThrows(HodiException.class, () -> service.activate(hash));
        assertTrue(refused.getMessage().contains("waiting for the bank"));
        assertFalse(reload("ada.sideentrance").isEnabled());
    }

    @Test
    @DisplayName("a waiting account cannot be quietly deleted out from under the request")
    void archiveRefusesWhileWaiting() {
        signInAsMaker();
        service.create(newStaff("ada.vanish"));
        String hash = profileHashOf("ada.vanish");

        assertThrows(HodiException.class, () -> service.archive(hash));
        assertThrows(HodiException.class, () -> service.deactivate(hash, "changed my mind"));
        assertEquals(AppConstant.STATUS_NEW, reload("ada.vanish").getStatus());
        assertNotNull(waitingFor("ada.vanish"));
    }
}
