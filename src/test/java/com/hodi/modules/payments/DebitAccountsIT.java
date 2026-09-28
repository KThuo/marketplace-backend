package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.auth.OtpChallenge;
import com.hodi.modules.auth.OtpChallengeRepository;
import com.hodi.modules.auth.OtpChallengeService;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentMoneySettingsService;
import com.hodi.modules.developments.DevelopmentMoneySettingsService.SaveMoneySettingsRequest;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.payments.PaymentTypeDtos.AccountResponse;
import com.hodi.modules.payments.PaymentTypeDtos.SaveAccountRequest;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The accounts a development pays from — the owner's own money, set up under a permission of its own.
 *
 * <p>Three rules worth a database: a paying-out account answers to the debit permission and never to the
 * collection setting; scoped to a development it is set up by whichever side manages that development's
 * spending; and what a development may pay from is the owner's accounts that reach it, plus the bank's where
 * the bank manages — and never a till.
 */
@SpringBootTest
@Transactional
class DebitAccountsIT {

    @Autowired PaymentAccountService service;
    @Autowired PaymentAccountRepository accounts;
    @Autowired PaymentTypeRepository types;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentMoneySettingsService settings;
    @Autowired OtpChallengeRepository challenges;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.hodi.modules.configurations.ConfigurationService configs;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager em;

    private Long tenantId;
    private Long userId;
    private Development development;

    @BeforeEach
    void build() {
        tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        userId = jdbc.queryForObject("select id from users order by id limit 1", Long.class);
        // PesaLink ships switched off until somebody has an account on it; the till too. Rolled back with the test.
        jdbc.update("update payment_types set status = 1 where provider_type in ('COOP_PESALINK', 'BUNI_IPN_TILL')");
        platformScope("PLATFORM");
        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Debit Heights").developmentType("APARTMENT").currency("KES")
                .listingState(AppConstant.LISTING_DRAFT).build());
        asOwner("DEBIT_ACCOUNTS_MANAGE", "PAYMENT_TYPES_VIEW");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        platformScope("PLATFORM");
    }

    // ── signing in ───────────────────────────────────────────────────────────

    private void asOwner(String... perms) { signIn(AppConstant.ACTOR_SELLER, "SELLER_OWNER", tenantId, false, perms); }

    private void asBank(String... perms) { signIn(AppConstant.ACTOR_PLATFORM, "SUPER_ADMIN", null, true, perms); }

    private void signIn(String actor, String userType, Long tenant, boolean platform, String... perms) {
        User user = User.builder().id(userId).username("debit-test").password("x")
                .email("d@example.invalid").firstName("Deb").lastName("It")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(userId)
                .profileType(actor).userTypeCode(userType).tenantId(tenant).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile, Set.of(perms),
                tenant == null ? List.of() : List.of(tenant), platform, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String id(Development d) { return HashIdUtil.encodeId(d.getId()); }

    private void platformScope(String scope) {
        jdbc.update("update configurations set config_value = ? where config_key = ?", scope, "payments.collection.scope");
        em.flush();
        em.clear();
        configs.evictAll();
    }

    private void bankManagesSpending(boolean bank) {
        var was = SecurityContextHolder.getContext().getAuthentication();
        asBank("DEVELOPMENT_FINANCE_SETTINGS", "DEVELOPMENTS_FINANCE_VIEW");
        settings.save(id(development), new SaveMoneySettingsRequest("BANK", bank ? "BANK" : "OWNER"));
        development = developments.findById(development.getId()).orElseThrow();
        SecurityContextHolder.getContext().setAuthentication(was);
    }

    private String[] code() {
        String token = "test-" + RrnGenerator.generate("OT");
        challenges.save(OtpChallenge.builder()
                .challengeToken(token).userId(userId)
                .purpose(OtpChallengeService.PURPOSE_PAYMENT_ACCOUNT).channel(OtpChallengeService.CHANNEL_SMS)
                .codeHash(sha256("123456")).sentToMasked("•••0001")
                .expiresAt(OffsetDateTime.now().plusMinutes(10)).build());
        return new String[]{token, "123456"};
    }

    private static String sha256(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private String channel(String providerType) {
        return HashIdUtil.encodeId(types.findByProviderType(providerType).orElseThrow().getId());
    }

    /** A PesaLink account to pay from. The channel describes its own one field: the account money leaves. */
    private SaveAccountRequest pesalink(String tenantHash, String developmentHash) {
        String[] code = code();
        return new SaveAccountRequest(channel("COOP_PESALINK"), tenantHash, null, developmentHash,
                null, null, null, null, Map.of("accountNumber", "01" + RrnGenerator.generate("A").substring(0, 10)),
                code[0], code[1]);
    }

    private PaymentAccount live(AccountResponse saved) {
        PaymentAccount row = accounts.findById(HashIdUtil.decodeId(saved.id())).orElseThrow();
        row.setStatus(AppConstant.STATUS_ACTIVE);
        row.setStatusFlag(AppConstant.FLAG_ACTIVE);
        return accounts.saveAndFlush(row);
    }

    private List<String> offeredDevelopments(String providerType) {
        return service.developmentOptions(null, null, channel(providerType)).stream().map(d -> d.id()).toList();
    }

    private List<String> paysFrom() {
        return service.debitAccountsFor(developments.findById(development.getId()).orElseThrow())
                .stream().map(a -> a.accountNo()).toList();
    }

    // ── the tests ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an owner sets up an account to pay from while the bank collects everything, and buyers are never offered it")
    void anOwnerPaysFromTheirOwnAccount() {
        var offered = service.assignable(null, null);
        assertTrue(offered.stream().anyMatch(c -> c.sends()), "PesaLink is offered under the debit permission");
        assertTrue(offered.stream().noneMatch(c -> !c.sends()), "and no way of collecting, which needs another");
        assertTrue(service.setupContext().mayDebit());
        assertFalse(service.setupContext().mayCollect());

        AccountResponse saved = service.assign(pesalink(null, id(development)));
        assertTrue(saved.sends());
        assertEquals(AppConstant.STATUS_NEW, saved.status(), "a second person still has to agree");
        assertFalse(accounts.findById(HashIdUtil.decodeId(saved.id())).orElseThrow().isConfiguredByBank());
        assertTrue(paysFrom().isEmpty(), "not until approved");

        PaymentAccount row = live(saved);
        assertEquals(List.of(row.getAccountNo()), paysFrom());
        assertTrue(service.offeredFor(development).stream().noneMatch(a -> a.accountNo().equals(row.getAccountNo())),
                "money does not go out of a till, and buyers are not offered a way of paying out");
    }

    @Test
    @DisplayName("without the debit permission a paying-out account is neither offered nor accepted")
    void thePermissionIsTheGate() {
        platformScope("ORGANISATION");
        asOwner("PAYMENT_TYPES_MANAGE", "PAYMENT_TYPES_VIEW");
        assertTrue(service.assignable(null, null).stream().noneMatch(c -> c.sends()));
        HodiException refused = assertThrows(HodiException.class, () -> service.assign(pesalink(null, null)));
        assertTrue(refused.getMessage().contains("debit-accounts permission"));

        // And the other way round: the debit permission alone does not set up a way of collecting.
        asOwner("DEBIT_ACCOUNTS_MANAGE", "PAYMENT_TYPES_VIEW");
        String[] code = code();
        HodiException till = assertThrows(HodiException.class, () -> service.assign(new SaveAccountRequest(
                channel("BUNI_IPN_TILL"), null, null, null, "522522", "T" + RrnGenerator.generate("A").substring(0, 9),
                "Test Seller Ltd", null, null, code[0], code[1])));
        assertTrue(till.getMessage().contains("payment-types permission"));
    }

    @Test
    @DisplayName("scoped to a development, it is set up by whichever side manages that development's spending")
    void theManagingSideSetsItUp() {
        bankManagesSpending(true);
        // Among whatever else this organisation owns in the database, not this one.
        assertFalse(offeredDevelopments("COOP_PESALINK").contains(id(development)),
                "the form does not offer the owner a development the bank manages");
        HodiException refused = assertThrows(HodiException.class, () -> service.assign(pesalink(null, id(development))));
        assertTrue(refused.getMessage().startsWith("The bank manages spending"));

        asBank("DEBIT_ACCOUNTS_MANAGE", "PAYMENT_TYPES_MANAGE");
        AccountResponse banks = service.assign(pesalink(HashIdUtil.encodeId(tenantId), id(development)));
        assertEquals("TENANT", banks.ownerKind(), "the owner's account, set up by the bank on their behalf");

        bankManagesSpending(false);
        asOwner("DEBIT_ACCOUNTS_MANAGE", "PAYMENT_TYPES_VIEW");
        assertTrue(offeredDevelopments("COOP_PESALINK").contains(id(development)), "theirs to manage again");
        assertFalse(offeredDevelopments("BUNI_IPN_TILL").contains(id(development)),
                "for a till the same development is not offered: the bank collects there");
    }

    @Test
    @DisplayName("an account for every development reaches each of them; the bank's own pays where the bank manages")
    void whatADevelopmentMayPayFrom() {
        PaymentAccount everywhere = live(service.assign(pesalink(null, null)));
        Development another = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Second Court").developmentType("APARTMENT").currency("KES")
                .listingState(AppConstant.LISTING_DRAFT).build());
        assertTrue(paysFrom().contains(everywhere.getAccountNo()));
        assertTrue(service.debitAccountsFor(another).stream().anyMatch(a -> a.accountNo().equals(everywhere.getAccountNo())));

        asBank("DEBIT_ACCOUNTS_MANAGE", "PAYMENT_TYPES_MANAGE");
        PaymentAccount platforms = live(service.assign(pesalink(null, null)));
        assertFalse(paysFrom().contains(platforms.getAccountNo()), "the owner manages: the bank's account is not theirs to pay from");

        bankManagesSpending(true);
        assertTrue(paysFrom().contains(platforms.getAccountNo()), "the bank manages: it pays from its own");
        assertTrue(paysFrom().contains(everywhere.getAccountNo()), "and may still use the owner's");
    }
}
