package com.hodi.modules.sellerops;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.agents.AgentProfile;
import com.hodi.modules.agents.AgentProfileRepository;
import com.hodi.modules.agents.AgentState;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingDtos.InstalmentLine;
import com.hodi.modules.bookings.BookingDtos.IntroducerRequest;
import com.hodi.modules.bookings.BookingService;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentMoneySettingsService;
import com.hodi.modules.developments.DevelopmentMoneySettingsService.MoneySettings;
import com.hodi.modules.developments.DevelopmentMoneySettingsService.SaveMoneySettingsRequest;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.developments.DevelopmentUnitRepository;
import com.hodi.modules.developments.DevelopmentUnitType;
import com.hodi.modules.developments.DevelopmentUnitTypeRepository;
import com.hodi.modules.payments.PaymentDtos.ReceiveRequest;
import com.hodi.modules.payments.PaymentService;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.properties.Property;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A completed sale raises a line for the bank and, when the booking names who brought the buyer, one for
 * the agent — at the development's rates, copied onto each line.
 */
@SpringBootTest
@Transactional
class CommissionsIT {

    @Autowired BookingService bookings;
    @Autowired PaymentService paymentService;
    @Autowired CommissionService service;
    @Autowired CommissionRepository commissions;
    @Autowired DevelopmentMoneySettingsService settings;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired DevelopmentUnitRepository units;
    @Autowired UnitBookingRepository bookingRows;
    @Autowired AgentProfileRepository agents;
    @Autowired ConfigurationService configs;
    @Autowired JdbcTemplate jdbc;

    private static final BigDecimal PRICE = new BigDecimal("9500000");

    private Long tenantId;
    private Development development;
    private Property unit;
    private AgentProfile agent;

    @BeforeEach
    void build() {
        tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        asSeller();
        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .tenantName("Test Seller").name("Commission Court").developmentType("APARTMENT")
                .bankCommissionPercent(new BigDecimal("2.000"))
                .agentCommissionPercent(new BigDecimal("1.000"))
                .agentCommissionPaidBy(Development.AGENT_PAID_BY_BANK)
                .build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(PRICE).build());
        unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("C-2-04")
                .saleState(AppConstant.UNIT_AVAILABLE)
                .constructionStatus(AppConstant.BUILD_PLANNED).build());
        agent = approvedAgent("Wanjiru Agent");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    // ── raising ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a unit sale raises the bank's line at the development's rate, copied onto it")
    void unitSaleRaisesTheBanksLine() {
        BookingResponse done = sell(null);

        List<CommissionRecord> lines = linesOf(done);
        assertEquals(1, lines.size(), "the bank's line, and no agent's: nobody was named");
        CommissionRecord bank = lines.get(0);
        assertEquals(SellerOpsConstants.PAYEE_PLATFORM, bank.getPayeeKind());
        assertEquals(0, new BigDecimal("2.000").compareTo(bank.getRatePercent()));
        assertEquals(0, new BigDecimal("190000.00").compareTo(bank.getAmount()));
        assertEquals(SellerOpsConstants.COMMISSION_DUE, bank.getState());
        assertEquals(development.getId(), bank.getDevelopmentId());
        assertEquals("Commission Court", bank.getDevelopmentName());
        assertEquals(done.reference(), bank.getBookingRef());
        assertEquals("C-2-04", bank.getPropertyTitle(), "a unit is called by its label");
        assertEquals(Development.AGENT_PAID_BY_SELLER, bank.getPaidBy(), "the bank's fee is always the seller's to bear");
    }

    @Test
    @DisplayName("naming the agent who brought the buyer raises their line too, borne by whom the development says")
    void agentNamedRaisesTheAgentsLine() {
        BookingResponse done = sell(agent.getReference());
        assertEquals(agent.getReference(), done.introducedByAgentRef());
        assertEquals("Wanjiru Agent", done.introducedByAgentName());

        List<CommissionRecord> lines = linesOf(done);
        assertEquals(2, lines.size());
        CommissionRecord agentLine = lines.stream().filter(CommissionRecord::isAgentLine).findFirst().orElseThrow();
        assertEquals(agent.getId(), agentLine.getAgentProfileId());
        assertEquals("Wanjiru Agent", agentLine.getAgentName());
        assertEquals(0, new BigDecimal("1.000").compareTo(agentLine.getRatePercent()));
        assertEquals(0, new BigDecimal("95000.00").compareTo(agentLine.getAmount()));
        assertEquals(Development.AGENT_PAID_BY_BANK, agentLine.getPaidBy(), "this development's bank pays its agents");

        List<CommissionService.CommissionResponse> shown = service.forBooking(HashIdUtil.decodeId(done.id()));
        assertEquals(2, shown.size());
        assertEquals("AGENT", shown.get(0).payeeKind(), "ordered by payee kind, agent first");
        assertEquals("Wanjiru Agent", shown.get(0).agentName());
    }

    @Test
    @DisplayName("a development without rates of its own takes the platform's defaults")
    void withoutRatesThePlatformDefaultApplies() {
        development.setBankCommissionPercent(null);
        development.setAgentCommissionPercent(null);
        development.setAgentCommissionPaidBy(null);
        developments.save(development);
        BigDecimal platformRate = new BigDecimal(configs.getString(ConfigKey.COMMISSION_RATE_PERCENT));
        BigDecimal agentRate = new BigDecimal(configs.getString(ConfigKey.AGENT_COMMISSION_RATE_PERCENT));

        BookingResponse done = sell(agent.getReference());

        List<CommissionRecord> lines = linesOf(done);
        CommissionRecord bank = lines.stream().filter(l -> !l.isAgentLine()).findFirst().orElse(null);
        if (platformRate.compareTo(BigDecimal.ZERO) > 0) {
            assertNotNull(bank, "the platform charges, so its line is raised");
            assertEquals(0, platformRate.compareTo(bank.getRatePercent()));
            assertEquals(0, PRICE.multiply(platformRate).divide(BigDecimal.valueOf(100)).setScale(2)
                    .compareTo(bank.getAmount()));
        } else {
            assertNull(bank);
        }
        boolean agentLine = lines.stream().anyMatch(CommissionRecord::isAgentLine);
        assertEquals(agentRate.compareTo(BigDecimal.ZERO) > 0, agentLine,
                "the agent's default rate decides whether a named agent earns anything");
    }

    @Test
    @DisplayName("a rate of zero raises nothing, and nothing is not a row")
    void zeroRaisesNothing() {
        development.setBankCommissionPercent(BigDecimal.ZERO);
        development.setAgentCommissionPercent(BigDecimal.ZERO);
        developments.save(development);

        BookingResponse done = sell(agent.getReference());
        assertEquals(AppConstant.BOOKING_COMPLETED, done.state(), "the sale is the fact; it completes regardless");
        assertTrue(linesOf(done).isEmpty());
    }

    @Test
    @DisplayName("raising twice for one sale is one sale")
    void raisingTwiceIsOneSale() {
        BookingResponse done = sell(agent.getReference());
        UnitBooking row = bookingRows.findById(HashIdUtil.decodeId(done.id())).orElseThrow();
        Property home = units.findById(unit.getId()).orElseThrow();

        service.raiseFor(row, home, developments.findById(development.getId()).orElseThrow());
        service.raiseFor(row, home, developments.findById(development.getId()).orElseThrow());

        assertEquals(2, linesOf(done).size(), "one for the bank, one for the agent, however often it is asked");
    }

    // ── who brought the buyer ─────────────────────────────────────────────────

    @Test
    @DisplayName("the introducer is named while the booking is live and frozen once it completes")
    void introducerIsFrozenOnceCompleted() {
        BookingResponse booked = bookings.create(devId(), request(null));
        assertNull(booked.introducedByAgentRef());

        BookingResponse named = bookings.setIntroducer(booked.id(), new IntroducerRequest(agent.getReference()));
        assertEquals(agent.getReference(), named.introducedByAgentRef());

        BookingResponse cleared = bookings.setIntroducer(booked.id(), new IntroducerRequest(""));
        assertNull(cleared.introducedByAgentRef(), "a blank reference clears it");

        bookings.setIntroducer(booked.id(), new IntroducerRequest(agent.getReference()));
        pay(booked);
        BookingResponse done = bookings.complete(devId(), booked.id());
        assertEquals(agent.getReference(), done.introducedByAgentRef());

        HodiException e = assertThrows(HodiException.class,
                () -> bookings.setIntroducer(booked.id(), new IntroducerRequest("")));
        assertTrue(e.getMessage().contains("completed"), e.getMessage());
        assertTrue(linesOf(done).stream().anyMatch(CommissionRecord::isAgentLine),
                "the line raised against the named agent stands");
    }

    @Test
    @DisplayName("only an approved agent can be named")
    void onlyAnApprovedAgent() {
        AgentProfile pending = agents.save(AgentProfile.builder()
                .reference(RrnGenerator.generate("AG")).userId(agent.getUserId())
                .profileId(freeProfileId()).fullName("Not Yet Agent").state(AgentState.PENDING).build());
        BookingResponse booked = bookings.create(devId(), request(null));

        HodiException e = assertThrows(HodiException.class,
                () -> bookings.setIntroducer(booked.id(), new IntroducerRequest(pending.getReference())));
        assertTrue(e.getMessage().contains("pending"), e.getMessage());
        assertThrows(RuntimeException.class,
                () -> bookings.setIntroducer(booked.id(), new IntroducerRequest("AGNOBODY")),
                "a reference that names nobody is not found");
    }

    // ── the schedule ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("the bank sets a development's schedule; the owner reads it; a rate is a percentage")
    void theBankSetsTheSchedule() {
        asBank();
        MoneySettings saved = settings.save(devId(), new SaveMoneySettingsRequest("BANK", "OWNER",
                new BigDecimal("2.5"), new BigDecimal("0.75"), "SELLER"));
        assertEquals(0, new BigDecimal("2.5").compareTo(saved.bankCommissionPercent()));
        assertEquals(0, new BigDecimal("0.75").compareTo(saved.agentCommissionPercent()));
        assertEquals("SELLER", saved.agentCommissionPaidBy());
        assertNotNull(saved.defaultBankCommissionPercent(), "what null would mean, for the card to say");

        HodiException e = assertThrows(HodiException.class, () -> settings.save(devId(),
                new SaveMoneySettingsRequest("BANK", "OWNER", new BigDecimal("150"), null, null)));
        assertTrue(e.getMessage().contains("between 0 and 100"), e.getMessage());
        assertThrows(HodiException.class, () -> settings.save(devId(),
                new SaveMoneySettingsRequest("BANK", "OWNER", null, null, "AGENT")), "who pays is SELLER or BANK");

        MoneySettings cleared = settings.save(devId(), new SaveMoneySettingsRequest("BANK", "OWNER"));
        assertNull(cleared.bankCommissionPercent(), "the two-argument form means: the defaults");

        asSeller();
        MoneySettings read = settings.find(devId());
        assertFalse(read.mayChange());
        assertNull(read.bankCommissionPercent());
        assertThrows(HodiException.class, () -> settings.save(devId(), new SaveMoneySettingsRequest("BANK",
                "OWNER", new BigDecimal("1"), null, null)), "an owner never sets a rate");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private BookingResponse sell(String agentRef) {
        BookingResponse booked = bookings.create(devId(), request(agentRef));
        pay(booked);
        return bookings.complete(devId(), booked.id());
    }

    private void pay(BookingResponse booked) {
        paymentService.receive(new ReceiveRequest(booked.id(), PRICE, null, AppConstant.PAY_CHEQUE, null,
                "A7K2", null, null, null, null));
    }

    private CreateBookingRequest request(String agentRef) {
        return new CreateBookingRequest(HashIdUtil.encodeId(unit.getId()), "Asha Mwangi", "+254712000111",
                "asha@example.invalid", "12345678", PRICE, null, AppConstant.PLAN_LUMP_SUM, 14, null,
                List.of(new InstalmentLine("All of it", LocalDate.now(), PRICE)), agentRef);
    }

    private List<CommissionRecord> linesOf(BookingResponse booking) {
        return commissions.findByBookingIdAndStatusNotOrderByPayeeKind(HashIdUtil.decodeId(booking.id()),
                AppConstant.STATUS_DELETED);
    }

    private String devId() { return HashIdUtil.encodeId(development.getId()); }

    private AgentProfile approvedAgent(String name) {
        Long profileId = freeProfileId();
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

    /** A user profile no agent is on yet: the agent table keys on it. */
    private Long freeProfileId() {
        return jdbc.queryForObject("select p.id from user_profiles p where not exists "
                + "(select 1 from agent_profiles a where a.profile_id = p.id) order by p.id limit 1", Long.class);
    }

    private void asSeller() { signIn(AppConstant.ACTOR_SELLER, "SELLER_OWNER", tenantId, false); }
    private void asBank() { signIn(AppConstant.ACTOR_PLATFORM, "SUPER_ADMIN", null, true); }

    private void signIn(String actor, String userType, Long tenant, boolean platform) {
        User user = User.builder().id(1L).username("commission-test").password("x")
                .email("c@example.invalid").firstName("Co").lastName("Mmission")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(actor).userTypeCode(userType)
                .tenantId(tenant).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("UNITS_MANAGE", "UNITS_SELL", "BOOKINGS_MANAGE", "PAYMENTS_RECEIVE",
                        "DEVELOPMENTS_FINANCE_VIEW", "DEVELOPMENT_FINANCE_SETTINGS"),
                tenant == null ? List.of() : List.of(tenant), platform, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
