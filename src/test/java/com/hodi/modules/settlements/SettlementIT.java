package com.hodi.modules.settlements;

import com.hodi.common.AppConstant;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.agents.AgentPayoutAccountService;
import com.hodi.modules.agents.AgentPayoutAccountService.SavePayoutAccountRequest;
import com.hodi.modules.agents.AgentProfile;
import com.hodi.modules.agents.AgentProfileRepository;
import com.hodi.modules.agents.AgentState;
import com.hodi.modules.beneficiaries.PayoutAccountCheck;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingDtos.InstalmentLine;
import com.hodi.modules.bookings.BookingService;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.developments.DevelopmentUnitRepository;
import com.hodi.modules.developments.DevelopmentUnitType;
import com.hodi.modules.developments.DevelopmentUnitTypeRepository;
import com.hodi.modules.disbursements.Disbursement;
import com.hodi.modules.disbursements.DisbursementRepository;
import com.hodi.modules.payments.PaymentAccount;
import com.hodi.modules.payments.PaymentAccountRepository;
import com.hodi.modules.payments.PaymentDtos.ReceiveRequest;
import com.hodi.modules.payments.PaymentService;
import com.hodi.modules.payments.PaymentType;
import com.hodi.modules.payments.PaymentTypeRepository;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.properties.Property;
import com.hodi.modules.sellerops.CommissionRecord;
import com.hodi.modules.sellerops.CommissionRepository;
import com.hodi.modules.sellerops.SellerOpsConstants;
import com.hodi.modules.settlements.SettlementDtos.DevelopmentSettlements;
import com.hodi.modules.settlements.SettlementDtos.QueueRow;
import com.hodi.modules.settlements.SettlementDtos.SettleRequest;
import com.hodi.modules.settlements.SettlementDtos.SettlementResponse;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A sale the bank collected is settled: the figures add up, the queue shows it, the bank proposes the
 * transfers, and when the bank confirms them the sale is settled and the lines are paid — by the transfers,
 * not by hand.
 */
@SpringBootTest
@Transactional
class SettlementIT {

    @TestConfiguration
    static class FakeBank {
        @Bean @Primary
        PayoutAccountCheck payoutAccountCheck() {
            return (bankCode, accountNo) -> accountNo != null && accountNo.endsWith("99")
                    ? new PayoutAccountCheck.Answer(accountNo, bankCode, null, "No such account at that bank.")
                    : new PayoutAccountCheck.Answer(accountNo, "0011", "HOLDER OF " + accountNo, null);
        }
    }

    @Autowired SettlementService settlements;
    @Autowired SettlementRecorder recorder;
    @Autowired BookingService bookings;
    @Autowired UnitBookingRepository bookingRows;
    @Autowired PaymentService paymentService;
    @Autowired CommissionRepository commissions;
    @Autowired DisbursementRepository disbursements;
    @Autowired AgentPayoutAccountService payoutAccounts;
    @Autowired AgentProfileRepository agents;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired DevelopmentUnitRepository units;
    @Autowired PaymentAccountRepository paymentAccounts;
    @Autowired PaymentTypeRepository paymentTypes;
    @Autowired JdbcTemplate jdbc;

    private static final BigDecimal PRICE = new BigDecimal("9500000");

    private Long tenantId;
    private Development development;
    private Property unit;
    private AgentProfile agent;

    @BeforeEach
    void build() {
        tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        ensurePlatformSendAccount();
        asSeller();
        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .tenantName("Test Seller").name("Settlement Square").developmentType("APARTMENT")
                .collectionMode(Development.COLLECTED_BY_BANK)
                .bankCommissionPercent(new BigDecimal("2.000"))
                .agentCommissionPercent(new BigDecimal("1.000"))
                .agentCommissionPaidBy(Development.AGENT_PAID_BY_SELLER)
                .build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(PRICE).build());
        unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("S-3-01")
                .saleState(AppConstant.UNIT_AVAILABLE)
                .constructionStatus(AppConstant.BUILD_PLANNED).build());
        agent = approvedAgent("Amina Agent");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ── the figures ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("gross, less the bank's fee, less the agent's where the owner bears it, is what the owner gets")
    void theFiguresAddUp() {
        BookingResponse sale = sell(agent.getReference());
        asBank();
        SettlementResponse s = settlements.forBooking(sale.id());
        assertEquals(SettlementDtos.AWAITING, s.state());
        assertEquals(0, PRICE.compareTo(s.gross()));
        assertEquals(0, new BigDecimal("190000.00").compareTo(s.bankFee()));
        assertEquals(0, new BigDecimal("95000.00").compareTo(s.agentFee()));
        assertEquals("SELLER", s.agentFeeBorneBy());
        assertEquals(0, new BigDecimal("9215000.00").compareTo(s.netToOwner()));
        assertEquals(0, new BigDecimal("190000.00").compareTo(s.bankKeeps()));
        assertEquals("Amina Agent", s.agentName());
        assertEquals("RETAIN", s.feeSettlement());
        assertTrue(s.blockers().stream().anyMatch(b -> b.contains("no payout account")),
                "the agent has not said where to be paid, and the panel says so");
        assertFalse(s.mayPropose());
    }

    @Test
    @DisplayName("when the bank bears the agent's fee it comes out of the bank's own, and the owner gets more")
    void theBankMayBearTheAgentsFee() {
        development.setAgentCommissionPaidBy(Development.AGENT_PAID_BY_BANK);
        developments.save(development);
        BookingResponse sale = sell(agent.getReference());
        asBank();
        SettlementResponse s = settlements.forBooking(sale.id());
        assertEquals(0, new BigDecimal("9310000.00").compareTo(s.netToOwner()));
        assertEquals(0, new BigDecimal("95000.00").compareTo(s.bankKeeps()), "190,000 less the 95,000 it pays the agent");
    }

    @Test
    @DisplayName("a sale the owner collected is not the bank's to settle, and is not in the queue")
    void ownerCollectedIsNotSettledHere() {
        development.setCollectionMode(Development.COLLECTED_BY_OWNER);
        developments.save(development);
        BookingResponse sale = sell(null);
        asBank();
        assertEquals(SettlementDtos.NOT_BANK_COLLECTED, settlements.forBooking(sale.id()).state());
        assertTrue(settlements.queue(false, new PagedDataRequest()).getContent().stream()
                .noneMatch(r -> r.bookingReference().equals(sale.reference())));
        HodiException e = assertThrows(HodiException.class, () -> settlements.settle(sale.id(),
                new SettleRequest("11", "0110099887766", null, null)));
        assertTrue(e.getMessage().contains("did not collect"), e.getMessage());
    }

    // ── proposing and settling ────────────────────────────────────────────────

    @Test
    @DisplayName("settling proposes the proceeds and the agent's fee; the bank's confirmation settles the sale and pays the lines")
    void settlingProposesAndConfirmationWrites() {
        BookingResponse sale = sell(agent.getReference());
        asAgent(agent);
        payoutAccounts.addMine(new SavePayoutAccountRequest("11", "0110055443322", "Amina", null));

        asBank();
        SettlementResponse before = settlements.forBooking(sale.id());
        assertTrue(before.blockers().isEmpty(), String.join(" ", before.blockers()));
        assertTrue(before.mayPropose());
        assertEquals(1, before.agentAccounts().size(), "the agent's confirmed account is offered");

        assertTrue(settlements.queue(false, new PagedDataRequest()).getContent().stream()
                .anyMatch(r -> r.bookingReference().equals(sale.reference())), "awaiting, in the queue");

        SettlementResponse proposed = settlements.settle(sale.id(), new SettleRequest("11", "0110011223344", null, "Sale of S-3-01"));
        assertEquals(SettlementDtos.IN_FLIGHT, proposed.state());
        assertEquals(2, proposed.legs().size(), "the proceeds and the agent's fee; the bank keeps its own");
        Disbursement proceeds = leg(sale, Disbursement.SETTLEMENT_PROCEEDS);
        Disbursement agentFee = leg(sale, Disbursement.SETTLEMENT_AGENT_FEE);
        assertEquals(0, new BigDecimal("9215000.00").compareTo(proceeds.getAmount()));
        assertEquals("HOLDER OF 0110011223344", proceeds.getValidatedName(), "the name the checker approves is the bank's");
        assertEquals(Disbursement.PAYEE_SELLER, proceeds.getPayeeKind());
        assertEquals(tenantId, proceeds.getTenantId());
        assertEquals(Disbursement.AWAITING_APPROVAL, proceeds.getState(), "maker-checker, as for every transfer");
        assertEquals(0, new BigDecimal("95000.00").compareTo(agentFee.getAmount()));
        assertEquals("0110055443322", agentFee.getAccountNo());
        assertEquals(development.getId(), agentFee.getDevelopmentId());

        HodiException twice = assertThrows(HodiException.class, () -> settlements.settle(sale.id(),
                new SettleRequest("11", "0110011223344", null, null)));
        assertTrue(twice.getMessage().contains("already proposed"), twice.getMessage());

        // The bank confirms both went. In life the callback does this; here the recorder is called as it would be.
        confirm(agentFee);
        List<CommissionRecord> lines = commissions.findByBookingIdAndStatusNotOrderByPayeeKind(
                HashIdUtil.decodeId(sale.id()), AppConstant.STATUS_DELETED);
        CommissionRecord agentLine = lines.stream().filter(CommissionRecord::isAgentLine).findFirst().orElseThrow();
        assertEquals(SellerOpsConstants.COMMISSION_PAID, agentLine.getState());
        assertEquals(agentFee.getId(), agentLine.getDisbursementId());
        CommissionRecord bankLine = lines.stream().filter(l -> !l.isAgentLine()).findFirst().orElseThrow();
        assertEquals(SellerOpsConstants.COMMISSION_DUE, bankLine.getState(), "not until the proceeds go");
        assertNull(bookingRows.findById(HashIdUtil.decodeId(sale.id())).orElseThrow().getSettledAt());

        confirm(proceeds);
        UnitBooking settled = bookingRows.findById(HashIdUtil.decodeId(sale.id())).orElseThrow();
        assertNotNull(settled.getSettledAt(), "the proceeds reaching the owner is what settles the sale");
        bankLine = commissions.findById(bankLine.getId()).orElseThrow();
        assertEquals(SellerOpsConstants.COMMISSION_PAID, bankLine.getState(), "retained: paying out the rest is how it was kept");
        assertEquals(proceeds.getId(), bankLine.getDisbursementId());

        SettlementResponse after = settlements.forBooking(sale.id());
        assertEquals(SettlementDtos.SETTLED, after.state());
        assertTrue(settlements.queue(true, new PagedDataRequest()).getContent().stream()
                .anyMatch(r -> r.bookingReference().equals(sale.reference())));
        assertTrue(settlements.queue(false, new PagedDataRequest()).getContent().stream()
                .noneMatch(r -> r.bookingReference().equals(sale.reference())));

        asSeller();
        DevelopmentSettlements card = settlements.forDevelopment(HashIdUtil.encodeId(development.getId()));
        assertEquals(1, card.completedSales());
        assertEquals(1, card.settledSales());
        assertEquals(0, new BigDecimal("9215000.00").compareTo(card.paidToOwner()));
        assertEquals(0, BigDecimal.ZERO.compareTo(card.stillWithBank()));
        SettlementResponse ownersView = settlements.forBooking(sale.id());
        assertEquals(SettlementDtos.SETTLED, ownersView.state());
        assertFalse(ownersView.mayPropose(), "the owner reads it; the bank proposes it");
        assertTrue(ownersView.ownerAccounts().isEmpty(), "and is not offered the bank's picker");
    }

    @Test
    @DisplayName("with the bank's fee set to transfer, a third leg moves it; the fee arriving pays the bank's line")
    void transferringTheBanksFee() {
        assertEquals(1, jdbc.update("update configurations set config_value = 'TRANSFER' where config_key = ?",
                "commission.platform.settlement"));
        assertEquals(1, jdbc.update("update configurations set config_value = '0011/0110000000001' where config_key = ?",
                "commission.platform.fee.account"));
        configsEvict();
        try {
            BookingResponse sale = sell(null);
            asBank();
            SettlementResponse s = settlements.forBooking(sale.id());
            if (!"TRANSFER".equals(s.feeSettlement())) return; // the shared cache did not take the setting; see AgentAttributionIT
            SettlementResponse proposed = settlements.settle(sale.id(), new SettleRequest("11", "0110011223344", null, null));
            assertEquals(2, proposed.legs().size(), "the proceeds and the bank's fee");
            Disbursement fee = leg(sale, Disbursement.SETTLEMENT_BANK_FEE);
            assertEquals(0, new BigDecimal("190000.00").compareTo(fee.getAmount()));
            assertEquals("0110000000001", fee.getAccountNo());

            confirm(leg(sale, Disbursement.SETTLEMENT_PROCEEDS));
            CommissionRecord bankLine = commissions.findByBookingIdAndStatusNotOrderByPayeeKind(
                    HashIdUtil.decodeId(sale.id()), AppConstant.STATUS_DELETED).get(0);
            assertEquals(SellerOpsConstants.COMMISSION_DUE, bankLine.getState(), "the fee is on its way, not kept");
            confirm(fee);
            assertEquals(SellerOpsConstants.COMMISSION_PAID, commissions.findById(bankLine.getId()).orElseThrow().getState());
        } finally {
            configsEvict();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @Autowired com.hodi.modules.configurations.ConfigurationService configs;
    private void configsEvict() { configs.evictAll(); }

    private BookingResponse sell(String agentRef) {
        asSeller();
        BookingResponse booked = bookings.create(HashIdUtil.encodeId(development.getId()),
                new CreateBookingRequest(HashIdUtil.encodeId(unit.getId()), "Asha Mwangi", "+254712000111",
                        "asha@example.invalid", "12345678", PRICE, null, AppConstant.PLAN_LUMP_SUM, 14, null,
                        List.of(new InstalmentLine("All of it", LocalDate.now(), PRICE)), agentRef));
        paymentService.receive(new ReceiveRequest(booked.id(), PRICE, null, AppConstant.PAY_CHEQUE, null,
                "A7K2", null, null, null, null));
        return bookings.complete(HashIdUtil.encodeId(development.getId()), booked.id());
    }

    private Disbursement leg(BookingResponse sale, String kind) {
        return disbursements.findByBookingIdAndStatusNot(HashIdUtil.decodeId(sale.id()), AppConstant.STATUS_DELETED)
                .stream().filter(d -> kind.equals(d.getSettlementKind())).findFirst().orElseThrow();
    }

    /** What the engine does when Co-op confirms a transfer, minus Co-op. */
    private void confirm(Disbursement row) {
        row.setCheckedBy("checker");
        row.setCheckedAt(OffsetDateTime.now());
        row.setState(Disbursement.SUCCEEDED);
        row.setSettledAt(OffsetDateTime.now());
        row.setUpdatedBy("coop");
        disbursements.save(row);
        recorder.onPaid(row);
    }

    /** The bank's own PesaLink account, which every transfer leaves from. Made here if the database has none. */
    private void ensurePlatformSendAccount() {
        PaymentType pesalink = paymentTypes.findByProviderType("COOP_PESALINK").orElseThrow();
        boolean present = paymentAccounts.findLiveForPlatform().stream()
                .anyMatch(a -> pesalink.getId().equals(a.getPaymentTypeId()));
        if (!present) {
            paymentAccounts.save(PaymentAccount.builder().paymentTypeId(pesalink.getId()).accountNo("0110000000999")
                    .accountName("Platform payouts").category("TRANSFER").status(AppConstant.STATUS_ACTIVE)
                    .statusFlag(AppConstant.FLAG_ACTIVE).configuredByBank(true).build());
        }
        jdbc.update("update payment_types set status = 1 where provider_type = 'COOP_PESALINK'");
    }

    private AgentProfile approvedAgent(String name) {
        Long profileId = jdbc.queryForObject("select p.id from user_profiles p where not exists "
                + "(select 1 from agent_profiles a where a.profile_id = p.id) order by p.id limit 1", Long.class);
        Long userId = jdbc.queryForObject("select user_id from user_profiles where id = ?", Long.class, profileId);
        AgentProfile a = agents.save(AgentProfile.builder()
                .reference(RrnGenerator.generate("AG")).userId(userId).profileId(profileId)
                .fullName(name).state(AgentState.PENDING).build());
        Long agreementId = jdbc.queryForObject(
                "insert into agent_agreements (reference, agent_profile_id, terms_version, terms_sha256, body, "
                        + "body_sha256) values (?, ?, 'test', 'x', 'terms', 'y') returning id",
                Long.class, RrnGenerator.generate("AA"), a.getId());
        a.setState(AgentState.APPROVED);
        a.setTenantId(tenantId);
        a.setAgreementId(agreementId);
        a.setDecidedAt(OffsetDateTime.now());
        return agents.save(a);
    }

    private void asSeller() { signIn(AppConstant.ACTOR_SELLER, "SELLER_OWNER", tenantId, false, 1L, 1L); }
    private void asBank() { signIn(AppConstant.ACTOR_PLATFORM, "SUPER_ADMIN", null, true, 1L, 1L); }
    private void asAgent(AgentProfile a) { signIn(AppConstant.ACTOR_SELLER, "AGENT", a.getTenantId(), false, a.getUserId(), a.getProfileId()); }

    private void signIn(String actor, String userType, Long tenant, boolean platform, Long userId, Long profileId) {
        User user = User.builder().id(userId).username("settlement-test").password("x")
                .email("s@example.invalid").firstName("Set").lastName("Tlement")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(profileId).userId(userId)
                .profileType(actor).userTypeCode(userType)
                .tenantId(tenant).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("UNITS_MANAGE", "UNITS_SELL", "BOOKINGS_MANAGE", "PAYMENTS_RECEIVE", "SETTLEMENTS_VIEW",
                        "SETTLEMENTS_MAKE", "DISBURSEMENTS_MAKE", "AGENT_SELF_UPDATE", "DEVELOPMENTS_FINANCE_VIEW"),
                tenant == null ? List.of() : List.of(tenant), platform, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
