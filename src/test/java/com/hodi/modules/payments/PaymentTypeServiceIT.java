package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.infra.coop.CoopRoutes;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.auth.OtpChallenge;
import com.hodi.modules.auth.OtpChallengeRepository;
import com.hodi.modules.auth.OtpChallengeService;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.payments.PaymentTypeDtos.AccountListRequest;
import com.hodi.modules.payments.PaymentTypeDtos.AccountResponse;
import com.hodi.modules.payments.PaymentTypeDtos.AssignableChannel;
import com.hodi.modules.payments.PaymentTypeDtos.ChannelListRequest;
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
 * Where an organisation's money lands.
 *
 * <p>What is worth a test: the channel deciding which fields an account must carry, because a mandatory column
 * is how a live system ends up with placeholders in every phone-prompt row; the one-time code, because a
 * refusal must never cost a text message and a write must never succeed without one; the account number being
 * unique across the platform, because it is what an inbound credit is matched on; and the catalogue being the
 * platform's alone to switch.
 */
@SpringBootTest
@Transactional
class PaymentTypeServiceIT {

    @Autowired PaymentAccountService service;
    @Autowired PaymentTypeService catalogue;
    @Autowired PaymentTypeRepository types;
    @Autowired PaymentAccountRepository accounts;
    @Autowired OtpChallengeRepository challenges;
    @Autowired DevelopmentRepository developments;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApprovalService approvals;
    @Autowired com.hodi.modules.configurations.ConfigurationService configs;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager em;

    private Long tenantId;
    private Long userId;

    @BeforeEach
    void signIn() {
        tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        // The code is bound to a real user row: the challenge table's foreign key insists on one.
        userId = jdbc.queryForObject("select id from users order by id limit 1", Long.class);
        // The organisation has a number to text.
        jdbc.update("update tenants set contact_phone = coalesce(contact_phone, '+254700000001') where id = ?",
                tenantId);
        // The KCB till channel ships switched off until somebody has a till on it. Rolled back with the test.
        jdbc.update("update payment_types set status = 1 where provider_type = 'BUNI_IPN_TILL'");
        // Every test below attaches an account to an organisation, which the platform only permits while
        // payments.collection.scope says so. Stated here rather than assumed, because the shipped default
        // is the other answer.
        organisationsMayCollect(true);
        signInAs(AppConstant.ACTOR_SELLER, "SELLER_OWNER", tenantId, "PAYMENT_TYPES_VIEW",
                "PAYMENT_TYPES_MANAGE");
    }

    /** A second organisation, so ownership can be tested against a real row. Rolled back with the test. */
    private Long anotherTenant() {
        String ref = RrnGenerator.generate("TN");
        return jdbc.queryForObject(
                "insert into tenants (name, slug, tenant_ref, created_by) values (?, ?, ?, 'test') returning id",
                Long.class, "Elsewhere Ltd " + ref, "elsewhere-" + ref.toLowerCase(), ref);
    }

    /**
     * A second person, holding the approve permission and nothing else.
     *
     * <p>A second <em>user row</em>, not just a second permission: ck_approval_maker_checker is a database
     * CHECK barring the submitter from deciding their own request, and that is the control being tested.
     */
    private void signInAsChecker() {
        Long checkerId = jdbc.queryForObject(
                "select id from users order by id offset 1 limit 1", Long.class);
        User user = User.builder().id(checkerId).username("checker-test").password("x")
                .email("checker@example.invalid").firstName("Chi").lastName("Checker")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(2L).userId(checkerId)
                .profileType(AppConstant.ACTOR_PLATFORM).userTypeCode("SUPER_ADMIN")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("PAYMENTS_ACCOUNT_APPROVE", "APPROVALS_VIEW"), List.of(), true, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void signInAs(String actorClass, String userType, Long tenant, String... permissions) {
        User user = User.builder().id(userId).username("types-test").password("x")
                .email("t@example.invalid").firstName("Ty").lastName("Types")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(userId)
                .profileType(actorClass).userTypeCode(userType)
                .tenantId(tenant).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile, Set.of(permissions),
                tenant == null ? List.of() : List.of(tenant), tenant == null, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        // The row is rolled back with the test; the cache is not, so it is put back by hand.
        organisationsMayCollect(false);
    }

    /** Flips {@code payments.collection.scope} and evicts the cache that would otherwise hide the change. */
    private void organisationsMayCollect(boolean may) {
        jdbc.update("update configurations set config_value = ? where config_key = ?",
                may ? PaymentAccountService.SCOPE_ORGANISATION : "PLATFORM", "payments.collection.scope");
        // Two caches stand between that row and the next read, and both have to go. The Spring cache is
        // the obvious one; the persistence context is the one that cost an hour — the update above is
        // plain JDBC, so Hibernate goes on serving the row it already loaded and the flip looks ignored.
        em.flush();
        em.clear();
        configs.evictAll();
    }

    /**
     * A code the test knows. Written straight to the table rather than issued through the service, because
     * the service texts it and the test must not depend on a gateway.
     */
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

    private String channel(String providerCode) {
        return HashIdUtil.encodeId(types.findByProviderType(providerCode).orElseThrow().getId());
    }

    private SaveAccountRequest till(String accountNo, String[] code) {
        return new SaveAccountRequest(channel("BUNI_IPN_TILL"), null, null, null,
                "522522", accountNo, "Test Seller Ltd", null, null, code[0], code[1]);
    }

    // ── the fields are the channel's ─────────────────────────────────────────

    @Test
    @DisplayName("an inbound channel demands the account and its name, and a code seals it")
    void inboundChannelNeedsAnAccount() {
        String[] code = code();
        String accountNo = "T" + RrnGenerator.generate("A").substring(0, 9);

        AccountResponse saved = service.assign(till(accountNo, code));

        assertEquals("KCB Till", saved.name());
        assertEquals("TENANT", saved.ownerKind());
        assertEquals("All developments", saved.developmentLabel());
        assertEquals(accountNo, saved.accountNo());
        assertEquals(AppConstant.PAY_BANK_TRANSFER, saved.method(), "what a payment through it will record");

        PaymentAccount row = accounts.findById(HashIdUtil.decodeId(saved.id())).orElseThrow();
        assertEquals("BUNI_IPN_TILL", row.getProviderCode(), "the channel's identity is copied onto the row");
        assertEquals(AppConstant.CHANNEL_VALIDATE, row.getCategory());
    }

    @Test
    @DisplayName("without the account fields the refusal names what is missing — and costs no code")
    void missingFieldsRefusedBeforeTheCodeIsSpent() {
        String[] code = code();
        HodiException e = assertThrows(HodiException.class, () -> service.assign(new SaveAccountRequest(
                channel("BUNI_IPN_TILL"), null, null, null, null, null, null, null, null, code[0], code[1])));
        assertTrue(e.getMessage().contains("account"), e.getMessage());

        OtpChallenge challenge = challenges.findByChallengeToken(code[0]).orElseThrow();
        assertNull(challenge.getConsumedAt(), "the code is still good: a refusal must never cost a text");
    }

    @Test
    @DisplayName("cash carries no account, and is refused one")
    void manualChannelRefusesAnAccount() {
        String[] code = code();
        String cash = HashIdUtil.encodeId(types.findByCode("CASH").orElseThrow().getId());

        HodiException e = assertThrows(HodiException.class, () -> service.assign(new SaveAccountRequest(
                cash, null, null, null, null, "12345", "Somebody", null, null, code[0], code[1])));
        assertTrue(e.getMessage().contains("recorded by hand"), e.getMessage());

        AccountResponse saved = service.assign(new SaveAccountRequest(
                cash, null, null, null, null, null, null, null, null, code[0], code[1]));
        assertNull(saved.accountNo());

        // And once per owner: cash twice would be two ways to record the same thing.
        String[] again = code();
        HodiException dup = assertThrows(HodiException.class, () -> service.assign(new SaveAccountRequest(
                cash, null, null, null, null, null, null, null, null, again[0], again[1])));
        assertTrue(dup.getMessage().contains("already set up"), dup.getMessage());
        assertTrue(service.assignable(null, null).stream()
                .noneMatch(c -> c.name().equals("Cash")), "and the form no longer offers it");
    }

    // ── the code ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no code, no write; a wrong code, no write")
    void codeIsRequired() {
        String accountNo = "T" + RrnGenerator.generate("A").substring(0, 9);
        HodiException none = assertThrows(HodiException.class, () -> service.assign(
                till(accountNo, new String[]{null, null})));
        assertTrue(none.getMessage().toLowerCase().contains("code"), none.getMessage());

        String[] code = code();
        HodiException wrong = assertThrows(HodiException.class, () -> service.assign(
                till(accountNo, new String[]{code[0], "000000"})));
        assertTrue(wrong.getMessage().toLowerCase().contains("code"), wrong.getMessage());
        assertTrue(accounts.findByAccountNoNotArchived(accountNo).isEmpty(), "nothing was written");
    }

    @Test
    @DisplayName("withdrawing needs no code, and a withdrawn account no longer matches an inbound credit")
    void withdrawingNeedsNoCode() {
        String accountNo = "T" + RrnGenerator.generate("A").substring(0, 9);
        AccountResponse saved = service.assign(till(accountNo, code()));

        String said = service.setStatus(saved.id(), false);
        assertTrue(said.contains("withdrawn"), said);
        assertTrue(accounts.findLiveByAccountNo(accountNo).isEmpty(),
                "the notification handler must not place money on a withdrawn account");
        assertTrue(accounts.findByAccountNoNotArchived(accountNo).size() == 1,
                "but the row is kept, so the number cannot be re-registered by somebody else");
    }

    // ── uniqueness ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an account number is unique across the platform, because it is the inbound match key")
    void accountNumberIsUnique() {
        String accountNo = "T" + RrnGenerator.generate("A").substring(0, 9);
        service.assign(till(accountNo, code()));

        assertTrue(service.checkAccount(accountNo, null).taken(), "the form's early check says so");

        String[] again = code();
        HodiException e = assertThrows(HodiException.class, () -> service.assign(till(accountNo, again)));
        assertTrue(e.getMessage().contains("already registered"), e.getMessage());
    }

    @Test
    @DisplayName("a per-development account must name one of the owner's developments")
    void developmentMustBeTheOwners() {
        Development other = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(anotherTenant())
                .name("Somebody Else's").developmentType("APARTMENT").build());
        String[] code = code();
        HodiException e = assertThrows(HodiException.class, () -> service.assign(new SaveAccountRequest(
                channel("BUNI_IPN_TILL"), null, null, HashIdUtil.encodeId(other.getId()),
                null, "T" + RrnGenerator.generate("A").substring(0, 9), "Name", null, null, code[0], code[1])));
        assertTrue(e.getMessage().contains("does not belong"), e.getMessage());
    }

    // ── the catalogue ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a seller reads the catalogue and cannot switch a channel off; the platform can")
    void catalogueIsThePlatforms() {
        String kcbTill = channel("BUNI_IPN_TILL");
        assertFalse(catalogue.list(new ChannelListRequest()).getContent().isEmpty());

        HodiException e = assertThrows(HodiException.class, () -> catalogue.setStatus(kcbTill, false));
        assertTrue(e.getMessage().contains("platform administrator"), e.getMessage());

        signInAs(AppConstant.ACTOR_PLATFORM, "SUPER_ADMIN", null, "PAYMENT_TYPES_VIEW",
                "PAYMENT_CATALOGUE_MANAGE", "PAYMENT_TYPES_MANAGE");
        String said = catalogue.setStatus(kcbTill, false);
        assertTrue(said.contains("off"), said);
        assertEquals(AppConstant.STATUS_INACTIVE,
                types.findById(HashIdUtil.decodeId(kcbTill)).orElseThrow().getStatus());

        // And an owner is no longer offered it.
        signInAs(AppConstant.ACTOR_SELLER, "SELLER_OWNER", tenantId, "PAYMENT_TYPES_MANAGE");
        List<AssignableChannel> offered = service.assignable(null, null);
        assertTrue(offered.stream().noneMatch(c -> c.name().equals("KCB Till")));
    }

    @Test
    @DisplayName("the accounts list is the caller's own organisation's, and only that")
    void listIsScopedToTheOwner() {
        String mine = "T" + RrnGenerator.generate("A").substring(0, 9);
        service.assign(till(mine, code()));

        AccountListRequest request = new AccountListRequest();
        assertTrue(service.list(request).getContent().stream().anyMatch(a -> mine.equals(a.accountNo())));

        signInAs(AppConstant.ACTOR_SELLER, "SELLER_OWNER", anotherTenant(), "PAYMENT_TYPES_VIEW");
        assertTrue(service.list(request).getContent().stream().noneMatch(a -> mine.equals(a.accountNo())),
                "another organisation's till is not visible");
    }

    // ── what the receive form may offer ──────────────────────────────────────

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("only cash and cheque are offered to an organisation with no channel set up")
    void withNothingConfiguredOnlyCounterMethodsAreOffered() {
        // Nothing configured anywhere — not on this organisation and not on the platform behind it.
        // Without the second half the assertion below would pass or fail on whatever somebody had set
        // up on this shared database, which is how it used to read.
        jdbc.update("update payment_accounts set status = 5 "
                + "where tenant_id is null and institution_id is null and status <> 5");

        var offered = service.methodsOnOffer().stream()
                .map(com.hodi.modules.payments.PaymentDtos.MethodOption::value).toList();

        org.junit.jupiter.api.Assertions.assertTrue(offered.contains(AppConstant.PAY_CASH));
        org.junit.jupiter.api.Assertions.assertTrue(offered.contains(AppConstant.PAY_CHEQUE));
        org.junit.jupiter.api.Assertions.assertFalse(offered.contains(AppConstant.PAY_CARD),
                "a form offering a card channel nobody configured is a payment recorded against nothing");
        org.junit.jupiter.api.Assertions.assertFalse(offered.contains(AppConstant.PAY_MOBILE_MONEY),
                "mobile money needs a till or a paybill behind it, and nobody has one");
    }

    @Test
    @DisplayName("a configured channel does not become something a clerk may type in")
    void aConfiguredChannelIsNotAHandKeyMethod() {
        /*
         * This used to assert the opposite: configure a mobile-money channel and "mobile money" appears on
         * the receive form. That was the hole — a phone payment keyed from memory, with no prompt and no
         * notification behind it. The channel still decides what a payer is offered (that is offered(), per
         * booking); it decides nothing about what may be written down by hand.
         */
        PaymentType channel = types.findAllLive().stream()
                .filter(t -> t.channelCategory().isReceivable())
                .filter(t -> AppConstant.PAY_MOBILE_MONEY.equals(t.getMethod()))
                .findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(channel != null,
                "no mobile-money channel in the catalogue to configure");
        accounts.save(PaymentAccount.builder()
                .paymentTypeId(channel.getId()).tenantId(tenantId)
                .category(channel.getCategory())
                .accountNo("TEST-OFFERED-1").accountName("Test offered account")
                .status(AppConstant.STATUS_ACTIVE).statusFlag(AppConstant.FLAG_ACTIVE).build());

        var offered = service.methodsOnOffer().stream()
                .map(com.hodi.modules.payments.PaymentDtos.MethodOption::value).toList();

        org.junit.jupiter.api.Assertions.assertEquals(
                java.util.List.of(AppConstant.PAY_CASH, AppConstant.PAY_CHEQUE), offered,
                "cash and a cheque are the only things somebody asserts; everything else the bank tells us");
    }

    // ── who is allowed to collect at all ─────────────────────────────────────

    /*
     * payments.collection.scope decides whether an organisation may hold an account of its own, and
     * set-up is the only moment it can be applied: afterwards money has been routed through the account
     * and a setting cannot unwind a payment already taken.
     */

    @Test
    @DisplayName("while the platform collects everything, an organisation cannot be given an account")
    void organisationRefusedWhilePlatformCollects() {
        organisationsMayCollect(false);
        String[] code = code();

        HodiException refused = assertThrows(HodiException.class,
                () -> service.assign(till("T" + RrnGenerator.generate("A").substring(0, 9), code)));

        assertTrue(refused.getMessage().contains("Who collects payments"),
                "the refusal names the setting that would change the answer, not just the answer");
    }

    @Test
    @DisplayName("and the form offers it nothing, rather than refusing after it is filled in")
    void nothingIsAssignableWhilePlatformCollects() {
        organisationsMayCollect(false);

        assertTrue(service.assignable(null, null).isEmpty(),
                "a channel offered is a channel somebody will fill a form in for");
    }

    /*
     * This used to assert the opposite — that platform staff could not attach an account for an organisation
     * while the platform collected — on the reasoning that a control the operator can step around only
     * documents an intention. The model changed underneath it: the bank *is* the operator, and it sets up the
     * accounts that collect for an owner's developments, which the owner may not. What the platform-wide
     * setting still controls is whether such an account collects for a house with no development; each
     * development now says for itself who collects (see DevelopmentMoneySettingsIT).
     */
    @Test
    @DisplayName("the bank may set an account up for an organisation, and it is marked as the bank's")
    void theBankConfiguresForOwners() {
        organisationsMayCollect(false);
        String[] code = code();
        signInAs(AppConstant.ACTOR_PLATFORM, "SUPER_ADMIN", null, "PAYMENT_TYPES_VIEW",
                "PAYMENT_TYPES_MANAGE");

        SaveAccountRequest forTheTenant = new SaveAccountRequest(channel("BUNI_IPN_TILL"),
                HashIdUtil.encodeId(tenantId), null, null, "522522",
                "T" + RrnGenerator.generate("A").substring(0, 9), "Test Seller Ltd", null,
                null, code[0], code[1]);

        AccountResponse saved = service.assign(forTheTenant);
        assertEquals("TENANT", saved.ownerKind());
        assertTrue(accounts.findById(HashIdUtil.decodeId(saved.id())).orElseThrow().isConfiguredByBank());
    }

    @Test
    @DisplayName("the platform's own account is unaffected — it is the one collecting")
    void thePlatformMayAlwaysCollect() {
        organisationsMayCollect(false);
        String[] code = code();
        signInAs(AppConstant.ACTOR_PLATFORM, "SUPER_ADMIN", null, "PAYMENT_TYPES_VIEW",
                "PAYMENT_TYPES_MANAGE");

        AccountResponse saved = service.assign(new SaveAccountRequest(channel("BUNI_IPN_TILL"),
                null, null, null, "522522", "T" + RrnGenerator.generate("A").substring(0, 9),
                "Hodi Platform", null, null, code[0], code[1]));

        assertEquals("PLATFORM", saved.ownerKind());
    }

    @Test
    @DisplayName("an account that already exists can still be withdrawn, but not brought back")
    void existingAccountsAreNotStranded() {
        String[] code = code();
        AccountResponse saved = service.assign(till("T" + RrnGenerator.generate("A").substring(0, 9), code));

        // The platform takes collection back in-house after the account was attached.
        organisationsMayCollect(false);

        assertDoesNotThrow(() -> service.setStatus(saved.id(), false),
                "stopping collection needs no permission");
        assertThrows(HodiException.class, () -> service.setStatus(saved.id(), true),
                "bringing it back is the same act as attaching one");
    }

    // ── the channel describes its own account ────────────────────────────────

    /*
     * The four fixed columns asked every channel for a paybill, an account number, a name and a short
     * code. A Co-op phone prompt has none of those: it has an operator code and two credentials. These
     * prove the descriptor is what is read, that a secret never comes back out, and that the code an
     * inbound notification will be matched on is composed from the fields the descriptor names.
     */

    /** Co-op channels ship switched off until somebody points them at a host. Rolled back with the test. */
    private PaymentType coop(String providerType) {
        PaymentType type = types.findByProviderType(providerType).orElseThrow();
        jdbc.update("update payment_types set status = 1 where id = ?", type.getId());
        em.flush();
        em.clear();
        return types.findById(type.getId()).orElseThrow();
    }

    @Test
    @DisplayName("a phone prompt asks for an operator code and its credentials, and no paybill")
    void thePromptAsksForWhatItActuallyNeeds() {
        PaymentType prompt = coop("COOP_STK_PUSH");
        String[] code = code();
        // Unique per run: this suite shares a database with the running application, so a fixed code
        // collides with whatever somebody has genuinely set up through the screens.
        String operator = "OP" + RrnGenerator.generate("A").substring(0, 8);

        AccountResponse saved = service.assign(new SaveAccountRequest(
                HashIdUtil.encodeId(prompt.getId()), null, null, null,
                null, null, null, null,
                Map.of("accountNumber", operator),
                code[0], code[1]));

        assertEquals(operator.toLowerCase(java.util.Locale.ROOT), saved.accountNo(),
                "the code is composed from the descriptor's accountKey, lowercased for matching");
        assertNull(saved.payBillNo(), "a prompt has no paybill, so none is stored");
        assertNull(saved.accountName(), "nor a name — no Co-op channel has that field");

        Map<String, String> byKey = saved.config().stream()
                .collect(java.util.stream.Collectors.toMap(ChannelConfig.Field::key,
                        f -> f.value() == null ? "" : f.value()));
        assertEquals(operator, byKey.get("accountNumber"), "a plain field reads back as itself");
        assertEquals(1, byKey.size(),
                "and nothing else is asked for: the OAuth credentials are the platform's, held once");
    }

    @Test
    @DisplayName("a missing field is refused by name, before the code is spent")
    void missingDescriptorFieldIsNamed() {
        PaymentType prompt = coop("COOP_STK_PUSH");
        String[] code = code();

        HodiException refused = assertThrows(HodiException.class, () -> service.assign(
                new SaveAccountRequest(HashIdUtil.encodeId(prompt.getId()), null, null, null,
                        null, null, null, null,
                        Map.of(), code[0], code[1])));

        assertTrue(refused.getMessage().contains("Operator code"), refused.getMessage());
        assertTrue(challenges.findByChallengeToken(code[0]).isPresent(),
                "a refusal must never cost a text message");
    }

    @Test
    @DisplayName("a biller is keyed on two fields composed, because its advice carries no id")
    void aBillerIsKeyedOnThePairItIsAdvisedWith() {
        PaymentType biller = coop("COOP_BILLER");
        String[] code = code();
        String institution = "21" + RrnGenerator.generate("A").substring(0, 7);

        AccountResponse saved = service.assign(new SaveAccountRequest(
                HashIdUtil.encodeId(biller.getId()), null, null, null,
                null, null, null, null,
                Map.of("institutionCode", institution, "serviceName", "Breeze Estate",
                       "institutionName", "Breeze Estate Ltd", "connectionID", "conn-1",
                       "connectionPassword", "conn-pass"),
                code[0], code[1]));

        assertEquals(institution.toLowerCase(java.util.Locale.ROOT) + "breezeestate", saved.accountNo(),
                "the pair composed, whitespace out and lowercased, is what an advice can be matched on");
        assertEquals("Biller", saved.accountsLabel(), "and the screen calls it what the bank calls it");
    }

    @Test
    @DisplayName("one organisation may hold the same code on two channels, but not twice on one")
    void aCodeIsUniquePerChannel() {
        PaymentType prompt = coop("COOP_STK_PUSH");
        PaymentType ipn = coop("COOP_IPN_ACCOUNT");
        String shared = "SH" + RrnGenerator.generate("A").substring(0, 8);

        service.assign(new SaveAccountRequest(HashIdUtil.encodeId(prompt.getId()), null, null, null,
                null, null, null, null,
                Map.of("consumerKey", "ck", "consumerSecret", "cs", "accountNumber", shared),
                codeFor(), "123456"));

        // The same string on a different channel is a different thing, and the bank resolves both.
        assertDoesNotThrow(() -> service.assign(new SaveAccountRequest(
                HashIdUtil.encodeId(ipn.getId()), null, null, null, null, null, null, null,
                Map.of("accountNumber", shared), codeFor(), "123456")));

        // The same string twice on one channel would make every notification on it unattributable.
        assertThrows(HodiException.class, () -> service.assign(new SaveAccountRequest(
                HashIdUtil.encodeId(prompt.getId()), null, null, null, null, null, null, null,
                Map.of("consumerKey", "ck2", "consumerSecret", "cs2", "accountNumber", shared),
                codeFor(), "123456")));
    }

    /** A fresh challenge token, for a test that assigns more than once. */
    private String codeFor() {
        return code()[0];
    }

    // ── the second pair of eyes ──────────────────────────────────────────────

    /*
     * An account decides where a buyer's deposit physically lands, so proposing one and letting it collect
     * are two acts by two people. The one-time code is not a substitute and is not replaced: it proves the
     * person typing holds their own handset, which is a different question from whether anybody agreed.
     */

    @Test
    @DisplayName("a new account is written but collects nothing until somebody approves it")
    void aNewAccountWaits() {
        String accountNo = "T" + RrnGenerator.generate("A").substring(0, 9);
        AccountResponse saved = service.assign(till(accountNo, code()));

        assertEquals(AppConstant.STATUS_NEW, saved.status(), "written, and deliberately not live");
        assertTrue(accountRepositoryLive(accountNo).isEmpty(),
                "so no inbound notification on it can be matched to anybody yet");

        // Decoded while still signed in as the maker: the hashid salt is per-user, so an id encoded for
        // one person does not decode for another. The queue hands a checker its own handle.
        Long accountId = HashIdUtil.decodeId(saved.id());
        signInAsChecker();
        approvals.decideFor(AppConstant.APPROVAL_ENTITY_PAYMENT_ACCOUNT,
                accountId, AppConstant.APPROVAL_ACTION_CREATE,
                new ApprovalService.DecisionRequest(AppConstant.APPROVAL_APPROVED, "Checked with the bank"));
        em.flush();
        em.clear();

        assertTrue(accountRepositoryLive(accountNo).isPresent(), "approved, it collects");
    }

    @Test
    @DisplayName("editing a live account takes it out of use until the change is approved")
    void anEditGoesBackInTheQueue() {
        String accountNo = "T" + RrnGenerator.generate("A").substring(0, 9);
        AccountResponse saved = service.assign(till(accountNo, code()));
        Long accountId = HashIdUtil.decodeId(saved.id());
        signInAsChecker();
        approvals.decideFor(AppConstant.APPROVAL_ENTITY_PAYMENT_ACCOUNT,
                accountId, AppConstant.APPROVAL_ACTION_CREATE,
                new ApprovalService.DecisionRequest(AppConstant.APPROVAL_APPROVED, "ok"));
        em.flush();
        em.clear();
        signIn();

        String[] again = code();
        String changed = "T" + RrnGenerator.generate("A").substring(0, 9);
        AccountResponse edited = service.update(HashIdUtil.encodeId(accountId), new SaveAccountRequest(
                channel("BUNI_IPN_TILL"), null, null, null, "522522", changed, "Test Seller Ltd", null,
                null, again[0], again[1]));

        assertEquals(AppConstant.STATUS_NEW, edited.status(),
                "an unapproved destination must not go on collecting");
        assertTrue(accountRepositoryLive(changed).isEmpty(), "and nothing matches the new number yet");
    }

    @Test
    @DisplayName("the queue shows what changed, and never shows a secret")
    void theQueueCarriesTheDifferenceButNotTheSecret() {
        // The biller, because that is where a secret now lives: the prompt's only field is its
        // operator code, and the OAuth credentials are the platform's.
        PaymentType biller = coop("COOP_BILLER");
        AccountResponse saved = service.assign(new SaveAccountRequest(
                HashIdUtil.encodeId(biller.getId()), null, null, null, null, null, null, null,
                Map.of("institutionCode", "21" + RrnGenerator.generate("A").substring(0, 7),
                       "serviceName", "Queue Estate", "connectionID", "ck-1",
                       "connectionPassword", "the-real-secret"),
                codeFor(), "123456"));

        var pending = approvals.pendingFor(AppConstant.APPROVAL_ENTITY_PAYMENT_ACCOUNT,
                HashIdUtil.decodeId(saved.id()), AppConstant.APPROVAL_ACTION_CREATE).orElseThrow();

        String rendered = String.valueOf(pending.getAfterPayload());
        assertTrue(rendered.contains("ck-1"), "a checker sees the values they are approving");
        assertFalse(rendered.contains("the-real-secret"),
                "but a queue that printed a consumer secret would leak more than the control is worth");
        assertTrue(rendered.contains(ChannelConfig.MASK), "the mask says a credential is set, which is enough");
    }

    private java.util.Optional<PaymentAccount> accountRepositoryLive(String accountNo) {
        return accounts.findLiveByAccountNo(accountNo);
    }

    // ── our own addresses are ours to state ──────────────────────────────────

    @Test
    @DisplayName("the notification URL is shown, not asked for, and cannot be overwritten by a form")
    void theNotificationUrlIsOurs() {
        PaymentType ipn = coop("COOP_IPN_ACCOUNT");
        String account = "AC" + RrnGenerator.generate("A").substring(0, 8);

        AccountResponse saved = service.assign(new SaveAccountRequest(
                HashIdUtil.encodeId(ipn.getId()), null, null, null, null, null, null, null,
                // A client posting a value for it — by accident or otherwise — must not be able to change
                // the address Co-op is told to call.
                Map.of("accountNumber", account, "notificationUrl", "https://attacker.invalid/hook"),
                codeFor(), "123456"));

        ChannelConfig.Field shown = saved.config().stream()
                .filter(f -> "notificationUrl".equals(f.key())).findFirst().orElseThrow();
        assertEquals(ChannelConfig.DISPLAY, shown.type(), "declared as ours to state");
        assertTrue(shown.value() != null && shown.value().endsWith("/api/v1/public/coop/notifications"),
                "and built from the platform's own public URL: " + shown.value());

        String stored = jdbc.queryForObject(
                "select config ->> 'notificationUrl' from payment_accounts where account_no = ?",
                String.class, account.toLowerCase(java.util.Locale.ROOT));
        assertNull(stored, "nothing is stored for it, so nothing posted back could have replaced it");
    }

    @Test
    @DisplayName("the biller is told both of its addresses, and they match the routes we serve")
    void theBillerIsToldWhereToPost() {
        PaymentType biller = coop("COOP_BILLER");

        var fields = service.assignable(null, null).stream()
                .filter(c -> c.name().equals(biller.getName()))
                .flatMap(c -> c.accountFields().stream())
                .filter(f -> ChannelConfig.DISPLAY.equals(f.type()))
                .toList();

        assertEquals(2, fields.size(), "validation and advice, both ours to state");
        assertTrue(fields.stream().allMatch(f -> f.value() != null && !f.value().isBlank()),
                "shown rather than hedged: the bank asks for these in writing during onboarding");
        assertTrue(fields.stream().anyMatch(f -> f.value().endsWith(CoopRoutes.BILLER_VALIDATION)));
        assertTrue(fields.stream().anyMatch(f -> f.value().endsWith(CoopRoutes.BILLER_ADVICE)));
    }

    @Test
    @DisplayName("no account asks for the addresses we are notified from — that is one setting for the bank")
    void addressesAreNotAnAccountField() {
        for (String providerType : List.of("COOP_STK_PUSH", "COOP_IPN_ACCOUNT", "COOP_BILLER")) {
            PaymentType type = types.findByProviderType(providerType).orElseThrow();
            assertTrue(service.assignable(null, null).stream()
                            .filter(c -> c.name().equals(type.getName()))
                            .flatMap(c -> c.accountFields().stream())
                            .noneMatch(f -> "whitelistedIps".equals(f.key())),
                    providerType + " still asks for an address list");
        }
    }
}
