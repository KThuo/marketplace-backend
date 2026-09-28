package com.hodi.modules.beneficiaries;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.approvals.ApprovalService.DecisionRequest;
import com.hodi.modules.beneficiaries.BeneficiaryDtos.BeneficiaryListRequest;
import com.hodi.modules.beneficiaries.BeneficiaryDtos.BeneficiaryResponse;
import com.hodi.modules.beneficiaries.BeneficiaryDtos.SaveBeneficiaryRequest;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.users.User;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The people a development pays: confirmed with the bank, approved by a second person, owned by one
 * organisation.
 *
 * <p>The bank is a fake that answers by account number, so a test picks its verdict by what it registers:
 * an account ending {@code 99} is one the bank cannot confirm, everything else is held by "CONFIRMED HOLDER".
 */
@SpringBootTest
@Transactional
class BeneficiariesIT {

    @TestConfiguration
    static class FakeBank {
        @Bean @Primary
        PayoutAccountCheck payoutAccountCheck() {
            return (bankCode, accountNo) -> accountNo != null && accountNo.endsWith("99")
                    ? new PayoutAccountCheck.Answer(accountNo, bankCode, null, "No such account at that bank.")
                    : new PayoutAccountCheck.Answer(accountNo, "0011", "CONFIRMED HOLDER", null);
        }
    }

    @Autowired BeneficiaryService service;
    @Autowired BeneficiaryTypeService typeService;
    @Autowired BeneficiaryRepository beneficiaries;
    @Autowired BeneficiaryTypeRepository types;
    @Autowired ApprovalService approvals;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private Long makerId;
    private Long checkerId;

    @BeforeEach
    void signIn() {
        tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        makerId = jdbc.queryForObject("select id from users order by id limit 1", Long.class);
        // A second *user row*: ck_approval_maker_checker is a database CHECK barring the submitter from deciding.
        checkerId = jdbc.queryForObject("select id from users order by id offset 1 limit 1", Long.class);
        asMaker();
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    private void asMaker() { signIn(makerId, AppConstant.ACTOR_SELLER, "SELLER_OWNER", tenantId, false,
            "BENEFICIARIES_VIEW", "BENEFICIARIES_MANAGE"); }

    private void asChecker() { signIn(checkerId, AppConstant.ACTOR_SELLER, "SELLER_OWNER", tenantId, false,
            "BENEFICIARIES_APPROVE", "APPROVALS_VIEW"); }

    private void asStranger(Long otherTenant) { signIn(checkerId, AppConstant.ACTOR_SELLER, "SELLER_OWNER",
            otherTenant, false, "BENEFICIARIES_VIEW", "BENEFICIARIES_MANAGE", "BENEFICIARIES_APPROVE"); }

    private void asBank() { signIn(makerId, AppConstant.ACTOR_PLATFORM, "SUPER_ADMIN", null, true,
            "BENEFICIARIES_VIEW", "BENEFICIARIES_MANAGE", "BENEFICIARY_TYPES_MANAGE"); }

    private void signIn(Long userId, String actor, String userType, Long tenant, boolean platform, String... perms) {
        User user = User.builder().id(userId).username("bn-" + userId).password("x")
                .email("b" + userId + "@example.invalid").firstName("Ben").lastName("Eficiary")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(userId).userId(userId)
                .profileType(actor).userTypeCode(userType).tenantId(tenant).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile, Set.of(perms),
                tenant == null ? List.of() : List.of(tenant), platform, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private Long anotherTenant() {
        String ref = RrnGenerator.generate("TN");
        return jdbc.queryForObject(
                "insert into tenants (name, slug, tenant_ref, created_by) values (?, ?, ?, 'test') returning id",
                Long.class, "Elsewhere Ltd " + ref, "elsewhere-" + ref.toLowerCase(), ref);
    }

    private String typeId(String code) {
        return HashIdUtil.encodeId(types.findAll().stream().filter(t -> t.getCode().equals(code)).findFirst()
                .orElseThrow().getId());
    }

    private static String account(String suffix) {
        return "01" + RrnGenerator.generate("A").substring(0, 8) + suffix;
    }

    private SaveBeneficiaryRequest supplier(String name, String accountNo) {
        return new SaveBeneficiaryRequest(null, null, typeId("SUPPLIER"), name, "A001234567Z",
                "Jane", "+254700000002", "jane@example.invalid", "11", accountNo, null);
    }

    /**
     * Ids are hashed with a salt per signed-in user, so an id the maker was given means nothing to the
     * checker. Decoded while the maker is still signed in, decided as the checker by the raw id.
     */
    private void approve(BeneficiaryResponse row) {
        Long id = HashIdUtil.decodeId(row.id());
        asChecker();
        approvals.decideFor(AppConstant.APPROVAL_ENTITY_BENEFICIARY, id,
                AppConstant.APPROVAL_ACTION_CREATE, new DecisionRequest("APPROVED", "Known supplier."));
        asMaker();
    }

    // ── registering ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a beneficiary is registered against the bank's name, and waits for a second person")
    void registeredThenApproved() {
        BeneficiaryResponse saved = service.create(supplier("Mwangi Hardware", account("00")));

        assertTrue(saved.reference().startsWith("BN"));
        assertEquals("VERIFIED", saved.verification());
        assertEquals("CONFIRMED HOLDER", saved.confirmedName(), "the bank's name, not the typed one");
        assertEquals("0011", saved.bankCode(), "as the bank wants it quoted");
        assertEquals("SUPPLIER", saved.typeCode());
        assertEquals(AppConstant.STATUS_NEW, saved.status());
        assertFalse(saved.payable(), "confirmed, but nobody has agreed yet");
        assertTrue(service.payable(null, null).isEmpty());

        approve(saved);
        BeneficiaryResponse live = service.find(saved.id());
        assertEquals(AppConstant.STATUS_ACTIVE, live.status());
        assertTrue(live.payable());
        assertEquals(1, service.payable(null, null).size());
    }

    @Test
    @DisplayName("an account the bank cannot confirm is kept, unverified, and cannot be paid even once approved")
    void unconfirmedIsKeptButNotPayable() {
        BeneficiaryResponse saved = service.create(supplier("Ghost Ltd", account("99")));
        assertEquals("UNVERIFIED", saved.verification());
        assertNull(saved.confirmedName());
        assertEquals("No such account at that bank.", saved.verificationNote());

        approve(saved);
        BeneficiaryResponse live = service.find(saved.id());
        assertEquals(AppConstant.STATUS_ACTIVE, live.status());
        assertFalse(live.payable(), "approved is not the same as confirmed");
        assertTrue(service.payable(null, null).isEmpty());

        // Asked again, the bank still says no; the row says why.
        BeneficiaryResponse again = service.verify(saved.id());
        assertEquals("UNVERIFIED", again.verification());
    }

    @Test
    @DisplayName("the same account twice for one owner is refused by name; another owner may register it")
    void oneAccountOncePerOwner() {
        String shared = account("00");
        service.create(supplier("Mwangi Hardware", shared));
        HodiException twice = assertThrows(HodiException.class,
                () -> service.create(supplier("Mwangi H/W", shared)));
        assertTrue(twice.getMessage().contains("already registered as Mwangi Hardware"));

        asStranger(anotherTenant());
        assertNotNull(service.create(supplier("Mwangi Hardware", shared)).id(), "their supplier too");
    }

    @Test
    @DisplayName("a suspended type is not offered and is refused")
    void suspendedTypeRefused() {
        asBank();
        String labour = typeId("LABOUR");
        typeService.setStatus(labour, false);
        assertTrue(typeService.available().stream().noneMatch(t -> t.code().equals("LABOUR")));

        asMaker();
        HodiException refused = assertThrows(HodiException.class, () -> service.create(
                new SaveBeneficiaryRequest(null, null, labour, "Casuals", null, null, null, null, "11", account("00"), null)));
        assertTrue(refused.getMessage().contains("available"));
    }

    // ── changing ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a contact change on a live beneficiary is direct; a change of account goes back to the bank and the checker")
    void payoutChangesNeedApprovalAndContactsDoNot() {
        BeneficiaryResponse saved = service.create(supplier("Mwangi Hardware", account("00")));
        approve(saved);

        BeneficiaryResponse renamed = service.update(saved.id(), new SaveBeneficiaryRequest(null, null,
                typeId("SUPPLIER"), "Mwangi Hardware Ltd", null, "Joseph", "+254700000003", null, saved.bankCode(),
                saved.accountNo(), "Cement and steel."));
        assertEquals(AppConstant.STATUS_ACTIVE, renamed.status(), "nothing about where the money goes changed");
        assertEquals("Joseph", renamed.contactName());
        assertTrue(renamed.payable());

        BeneficiaryResponse moved = service.update(saved.id(), new SaveBeneficiaryRequest(null, null,
                typeId("SUPPLIER"), "Mwangi Hardware Ltd", null, "Joseph", null, null, "68", account("99"), null));
        assertEquals(AppConstant.STATUS_NEW, moved.status(), "out of use until somebody approves the new account");
        assertEquals("UNVERIFIED", moved.verification());
        assertFalse(moved.payable());
        assertTrue(approvals.pendingFor(AppConstant.APPROVAL_ENTITY_BENEFICIARY, HashIdUtil.decodeId(saved.id()),
                AppConstant.ACTION_UPDATE).isPresent(), "a fresh request, since the last approved state was live");
    }

    @Test
    @DisplayName("deactivating needs nobody; a row waiting for approval cannot be activated by hand")
    void statusRules() {
        BeneficiaryResponse saved = service.create(supplier("Mwangi Hardware", account("00")));
        assertThrows(HodiException.class, () -> service.setStatus(saved.id(), true));

        approve(saved);
        assertTrue(service.setStatus(saved.id(), false).contains("deactivated"));
        assertFalse(service.find(saved.id()).payable());
        assertTrue(service.setStatus(saved.id(), true).contains("can be paid again"));
        assertTrue(service.find(saved.id()).payable());
    }

    // ── whose ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("another organisation's beneficiary is not found, and the maker cannot decide their own")
    void ownershipAndMakerChecker() {
        BeneficiaryResponse saved = service.create(supplier("Mwangi Hardware", account("00")));
        Long id = HashIdUtil.decodeId(saved.id());

        assertThrows(HodiException.class, () -> approvals.decideFor(AppConstant.APPROVAL_ENTITY_BENEFICIARY, id,
                AppConstant.APPROVAL_ACTION_CREATE, new DecisionRequest("APPROVED", "me")), "not two pairs of eyes");

        asStranger(anotherTenant());
        // Their own encoding of the same row: the salt is theirs, the row is still not theirs.
        assertThrows(ResourceNotFoundException.class, () -> service.find(HashIdUtil.encodeId(id)));
        assertTrue(service.list(new BeneficiaryListRequest()).getContent().isEmpty());
        assertThrows(HodiException.class, () -> approvals.decideFor(AppConstant.APPROVAL_ENTITY_BENEFICIARY, id,
                AppConstant.APPROVAL_ACTION_CREATE, new DecisionRequest("APPROVED", "not mine")));

        asChecker();
        assertFalse(service.list(new BeneficiaryListRequest()).getContent().isEmpty());
    }

    @Test
    @DisplayName("the bank registers a beneficiary for an owner, naming the owner; an owner cannot name one")
    void theBankRegistersForOwners() {
        asBank();
        assertThrows(HodiException.class, () -> service.create(supplier("Nobody's", account("00"))),
                "the bank must say whose");
        BeneficiaryResponse forTenant = service.create(new SaveBeneficiaryRequest(HashIdUtil.encodeId(tenantId), null,
                typeId("CONTRACTOR"), "Jenga Builders", null, null, null, null, "11", account("00"), null));
        assertEquals("TENANT", forTenant.ownerKind());
        assertTrue(forTenant.mayChange());

        Long id = HashIdUtil.decodeId(forTenant.id());
        asMaker();
        assertTrue(service.find(HashIdUtil.encodeId(id)).mayChange(), "it is the owner's, whoever registered it");
        HodiException refused = assertThrows(HodiException.class, () -> service.create(new SaveBeneficiaryRequest(
                HashIdUtil.encodeId(anotherTenant()), null, typeId("SUPPLIER"), "Theirs", null, null, null, null,
                "11", account("00"), null)));
        assertTrue(refused.getMessage().contains("not your organisation"));
    }
}
