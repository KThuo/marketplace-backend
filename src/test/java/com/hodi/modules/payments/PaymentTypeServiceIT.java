package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
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
                "522522", accountNo, "Test Seller Ltd", null, code[0], code[1]);
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
                channel("BUNI_IPN_TILL"), null, null, null, null, null, null, null, code[0], code[1])));
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
                cash, null, null, null, null, "12345", "Somebody", null, code[0], code[1])));
        assertTrue(e.getMessage().contains("recorded by hand"), e.getMessage());

        AccountResponse saved = service.assign(new SaveAccountRequest(
                cash, null, null, null, null, null, null, null, code[0], code[1]));
        assertNull(saved.accountNo());

        // And once per owner: cash twice would be two ways to record the same thing.
        String[] again = code();
        HodiException dup = assertThrows(HodiException.class, () -> service.assign(new SaveAccountRequest(
                cash, null, null, null, null, null, null, null, again[0], again[1])));
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
                null, "T" + RrnGenerator.generate("A").substring(0, 9), "Name", null, code[0], code[1])));
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
        var offered = service.methodsOnOffer().stream()
                .map(com.hodi.modules.payments.PaymentDtos.MethodOption::value).toList();

        org.junit.jupiter.api.Assertions.assertTrue(offered.contains(AppConstant.PAY_CASH));
        org.junit.jupiter.api.Assertions.assertTrue(offered.contains(AppConstant.PAY_CHEQUE));
        org.junit.jupiter.api.Assertions.assertFalse(offered.contains(AppConstant.PAY_CARD),
                "a form offering a card channel nobody configured is a payment recorded against nothing");
        org.junit.jupiter.api.Assertions.assertFalse(offered.contains(AppConstant.PAY_MOBILE_MONEY),
                "mobile money needs a till or a paybill behind it, and this organisation has none");
    }

    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("a configured channel adds its method, and only its method")
    void aConfiguredChannelIsOffered() {
        PaymentType channel = types.findAllLive().stream()
                .filter(t -> t.channelCategory().isReceivable())
                .filter(t -> AppConstant.PAY_MOBILE_MONEY.equals(t.getMethod()))
                .findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(channel != null,
                "no mobile-money channel in the catalogue to configure");

        accounts.save(PaymentAccount.builder()
                .paymentTypeId(channel.getId()).tenantId(tenantId)
                // The check constraint insists a non-counter account names itself.
                .category(channel.getCategory())
                .accountNo("TEST-OFFERED-1").accountName("Test offered account")
                .status(AppConstant.STATUS_ACTIVE).statusFlag(AppConstant.FLAG_ACTIVE).build());

        var offered = service.methodsOnOffer().stream()
                .map(com.hodi.modules.payments.PaymentDtos.MethodOption::value).toList();

        org.junit.jupiter.api.Assertions.assertTrue(offered.contains(AppConstant.PAY_MOBILE_MONEY),
                "configuring the channel is what puts its method on the form");
        org.junit.jupiter.api.Assertions.assertFalse(offered.contains(AppConstant.PAY_CARD),
                "and it puts nothing else there");
    }
}
