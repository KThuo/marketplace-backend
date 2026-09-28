package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.auth.OtpChallenge;
import com.hodi.modules.auth.OtpChallengeRepository;
import com.hodi.modules.auth.OtpChallengeService;
import com.hodi.modules.developments.DevelopmentFinanceDtos.RecordExpenditureRequest;
import com.hodi.modules.developments.DevelopmentMoneySettingsService.SaveMoneySettingsRequest;
import com.hodi.modules.payments.PaymentAccount;
import com.hodi.modules.payments.PaymentAccountRepository;
import com.hodi.modules.payments.PaymentAccountService;
import com.hodi.modules.payments.PaymentTypeDtos.AccountResponse;
import com.hodi.modules.payments.PaymentTypeDtos.SaveAccountRequest;
import com.hodi.modules.payments.PaymentTypeRepository;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who collects a development's money and who manages its spending — each development's own answer, and the
 * bank's to give.
 *
 * <p>Collection: under BANK only an account the bank configured may collect for the development, and an owner
 * may neither set one up nor change the bank's; under OWNER the owner may. Spending: whichever side the
 * development names records its costs, and the other only reads.
 */
@SpringBootTest
@Transactional
class DevelopmentMoneySettingsIT {

    @Autowired DevelopmentMoneySettingsService settings;
    @Autowired DevelopmentFinanceService finance;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentCostCategoryRepository categories;
    @Autowired PaymentAccountService accountService;
    @Autowired PaymentAccountRepository accounts;
    @Autowired PaymentTypeRepository types;
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
        jdbc.update("update payment_types set status = 1 where provider_type = 'BUNI_IPN_TILL'");
        platformScope("PLATFORM");
        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Money Heights").developmentType("APARTMENT").currency("KES")
                .listingState(AppConstant.LISTING_DRAFT).build());
        asOwner();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        platformScope("PLATFORM");
    }

    // ── signing in ───────────────────────────────────────────────────────────

    private void asOwner() {
        signIn(AppConstant.ACTOR_SELLER, "SELLER_OWNER", tenantId, false);
    }

    private void asBank() {
        signIn(AppConstant.ACTOR_PLATFORM, "SUPER_ADMIN", null, true);
    }

    private void signIn(String actor, String userType, Long tenant, boolean platform) {
        User user = User.builder().id(userId).username("money-test").password("x")
                .email("m@example.invalid").firstName("Mo").lastName("Money")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(userId)
                .profileType(actor).userTypeCode(userType)
                .tenantId(tenant).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("PAYMENT_TYPES_VIEW", "PAYMENT_TYPES_MANAGE", "DEVELOPMENTS_FINANCE_VIEW",
                        "DEVELOPMENTS_FINANCE_RECORD", "DEVELOPMENT_FINANCE_SETTINGS"),
                tenant == null ? List.of() : List.of(tenant), platform, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String id(Development d) { return HashIdUtil.encodeId(d.getId()); }

    private void setModes(String collection, String spending) {
        asBank();
        settings.save(id(development), new SaveMoneySettingsRequest(collection, spending));
        development = developments.findById(development.getId()).orElseThrow();
    }

    private void platformScope(String scope) {
        jdbc.update("update configurations set config_value = ? where config_key = ?", scope,
                "payments.collection.scope");
        em.flush();
        em.clear();
        configs.evictAll();
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
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A till collecting for this development, owned by the tenant; the tenant is named only by the bank. */
    private SaveAccountRequest tillForTheDevelopment(boolean nameTheTenant) {
        String[] code = code();
        String channel = HashIdUtil.encodeId(types.findByProviderType("BUNI_IPN_TILL").orElseThrow().getId());
        return new SaveAccountRequest(channel, nameTheTenant ? HashIdUtil.encodeId(tenantId) : null, null,
                id(development), "522522", "T" + RrnGenerator.generate("A").substring(0, 9), "Test Seller Ltd",
                null, null, code[0], code[1]);
    }

    private SaveAccountRequest tillForEveryDevelopment() {
        String[] code = code();
        String channel = HashIdUtil.encodeId(types.findByProviderType("BUNI_IPN_TILL").orElseThrow().getId());
        return new SaveAccountRequest(channel, null, null, null, "522522",
                "T" + RrnGenerator.generate("A").substring(0, 9), "Test Seller Ltd", null, null, code[0], code[1]);
    }

    /** Approved and live, as the checker would leave it. Straight to the row: the approval flow has its own tests. */
    private PaymentAccount live(AccountResponse saved) {
        PaymentAccount row = accounts.findById(HashIdUtil.decodeId(saved.id())).orElseThrow();
        row.setStatus(AppConstant.STATUS_ACTIVE);
        row.setStatusFlag(AppConstant.FLAG_ACTIVE);
        return accounts.saveAndFlush(row);
    }

    private List<String> offeredAccountNos() {
        return accountService.offeredFor(developments.findById(development.getId()).orElseThrow()).stream()
                .map(a -> a.accountNo()).toList();
    }

    private RecordExpenditureRequest aCost() {
        String category = HashIdUtil.encodeId(categories.findAllLive().getFirst().getId());
        return new RecordExpenditureRequest(category, null, "SPENT", new BigDecimal("125000"), null,
                "Mwangi Hardware", "INV-1", null);
    }

    // ── the settings ─────────────────────────────────────────────────────────

    /*
     * The mapping, not the live setting. Configuration is cached in Redis, which a running dev server shares:
     * it re-caches the committed value the moment a test evicts, so a test that flips the setting and reads it
     * back races that server. The mapping is what this piece adds; the lookup is the configuration module's.
     */
    @Test
    @DisplayName("a new development starts with whatever the platform-wide setting said until now")
    void theDefaultFollowsThePlatformSetting() {
        assertEquals(Development.COLLECTED_BY_BANK, DevelopmentMoneySettingsService.collectionModeFor("PLATFORM"));
        assertEquals(Development.COLLECTED_BY_BANK, DevelopmentMoneySettingsService.collectionModeFor(null));
        assertEquals(Development.COLLECTED_BY_OWNER, DevelopmentMoneySettingsService.collectionModeFor("organisation"));
    }

    @Test
    @DisplayName("only the bank changes them, and the owner is told who does")
    void onlyTheBankDecides() {
        HodiException refused = assertThrows(HodiException.class, () -> settings.save(id(development),
                new SaveMoneySettingsRequest("OWNER", "OWNER")));
        assertTrue(refused.getMessage().startsWith("Only the bank"));
        assertFalse(settings.find(id(development)).mayChange());

        setModes("OWNER", "BANK");
        assertEquals("OWNER", development.getCollectionMode());
        assertEquals("BANK", development.getSpendingManagedBy());
        assertThrows(HodiException.class, () -> settings.save(id(development),
                new SaveMoneySettingsRequest("SOMEBODY", "OWNER")), "only BANK or OWNER");
    }

    // ── collection ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("under BANK collection an owner cannot set up an account for the development")
    void anOwnerCannotCollectWhereTheBankDoes() {
        platformScope("ORGANISATION"); // even with organisations allowed
        HodiException refused = assertThrows(HodiException.class,
                () -> accountService.assign(tillForTheDevelopment(false)));
        assertTrue(refused.getMessage().contains("The bank collects the money for Money Heights"));
        assertTrue(accountService.developmentOptions(null, null).isEmpty(),
                "and the form does not offer the development to begin with");
    }

    @Test
    @DisplayName("under OWNER collection the owner may, even while the platform collects everything else")
    void anOwnerMayCollectWhereTheDevelopmentSaysSo() {
        setModes("OWNER", "OWNER");
        asOwner();
        assertEquals(1, accountService.setupContext().ownCollectingDevelopments());
        assertFalse(accountService.assignable(null, null).isEmpty(), "there is now somewhere to collect");

        PaymentAccount mine = live(accountService.assign(tillForTheDevelopment(false)));
        assertFalse(mine.isConfiguredByBank());
        assertTrue(offeredAccountNos().contains(mine.getAccountNo()));
    }

    @Test
    @DisplayName("the bank sets one up for the owner's development, and the owner cannot change or withdraw it")
    void theBanksAccountIsTheBanks() {
        asBank();
        PaymentAccount banks = live(accountService.assign(tillForTheDevelopment(true)));
        assertTrue(banks.isConfiguredByBank());
        assertTrue(offeredAccountNos().contains(banks.getAccountNo()), "it collects for a BANK development");

        asOwner();
        assertThrows(HodiException.class,
                () -> accountService.setStatus(HashIdUtil.encodeId(banks.getId()), false),
                "stopping the bank collecting is the bank's decision");
        assertThrows(HodiException.class,
                () -> accountService.update(HashIdUtil.encodeId(banks.getId()), tillForTheDevelopment(false)));
    }

    @Test
    @DisplayName("switching a development to BANK stops an owner-configured account collecting for it")
    void anOwnersAccountStopsWhenTheBankTakesOver() {
        setModes("OWNER", "OWNER");
        asOwner();
        PaymentAccount mine = live(accountService.assign(tillForTheDevelopment(false)));
        assertTrue(offeredAccountNos().contains(mine.getAccountNo()));

        setModes("BANK", "OWNER");
        assertFalse(offeredAccountNos().contains(mine.getAccountNo()),
                "an account the owner configured never collects where the bank collects");
    }

    @Test
    @DisplayName("an owner's account for every development collects only where the owner collects")
    void anEveryDevelopmentAccountFollowsEachDevelopment() {
        platformScope("ORGANISATION");
        PaymentAccount mine = live(accountService.assign(tillForEveryDevelopment()));
        assertFalse(mine.isConfiguredByBank());
        assertFalse(offeredAccountNos().contains(mine.getAccountNo()), "this development is the bank's to collect");

        setModes("OWNER", "OWNER");
        assertTrue(offeredAccountNos().contains(mine.getAccountNo()));
    }

    @Test
    @DisplayName("the list tells an owner which accounts are the bank's, and offers nothing on them")
    void theListSaysWhatAnOwnerMayChange() {
        asBank();
        AccountResponse banks = accountService.assign(tillForTheDevelopment(true));
        assertTrue(banks.configuredByBank());
        assertTrue(banks.mayChange(), "the bank may change its own");

        asOwner();
        AccountResponse seen = accountService.find(banks.id());
        assertTrue(seen.configuredByBank());
        assertFalse(seen.mayChange());
    }

    // ── spending ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("under OWNER the owner records costs and the bank only reads them")
    void theOwnerManagesTheirOwnSpending() {
        assertTrue(settings.find(id(development)).mayManageSpending());
        finance.recordExpenditure(id(development), aCost());

        asBank();
        assertFalse(settings.find(id(development)).mayManageSpending());
        HodiException refused = assertThrows(HodiException.class,
                () -> finance.recordExpenditure(id(development), aCost()));
        assertTrue(refused.getMessage().startsWith("The owner manages spending"));
        assertEquals(1, finance.expenditures(id(development),
                new DevelopmentFinanceDtos.ExpenditureListRequest()).getContent().size(),
                "but sees the owner's line");
    }

    /**
     * The owner of a bank-owned project is a bank, and a bank's staff are the platform's. The first version of
     * the rule refused the platform-wide administrator here with "only the owner records and pays them" —
     * describing the caller as the wrong side of a project whose owner is their own organisation.
     */
    @Test
    @DisplayName("a bank-owned development under OWNER is recorded by the bank's staff, institution or not")
    void aBankOwnedDevelopmentIsTheBanksToManage() {
        Long bankId = jdbc.queryForObject("select id from banks where status <> 5 order by id limit 1", Long.class);
        Development banks = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).institutionId(bankId).sellingTenantId(tenantId)
                .name("Lender's Court").developmentType("APARTMENT").currency("KES")
                .listingState(AppConstant.LISTING_DRAFT).build());
        assertEquals(Development.MANAGED_BY_OWNER, banks.getSpendingManagedBy());

        asBank();
        assertTrue(settings.find(id(banks)).mayManageSpending());
        finance.recordExpenditure(id(banks), aCost());

        asOwner();
        assertFalse(settings.find(id(banks)).mayManageSpending(), "a seller is not the owner of a bank's project");
    }

    @Test
    @DisplayName("under BANK the bank records costs and the owner only reads them")
    void theBankManagesSpendingWhenItSaysSo() {
        setModes("BANK", "BANK");
        finance.recordExpenditure(id(development), aCost());

        asOwner();
        HodiException refused = assertThrows(HodiException.class,
                () -> finance.recordExpenditure(id(development), aCost()));
        assertTrue(refused.getMessage().startsWith("The bank manages spending"));
        assertEquals(1, finance.expenditures(id(development),
                new DevelopmentFinanceDtos.ExpenditureListRequest()).getContent().size());
    }
}
