package com.hodi.modules.agents;

import com.hodi.common.AppConstant;
import com.hodi.modules.bookings.BookingTermsService;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.agents.AgentEarningsService.AgentTotals;
import com.hodi.modules.agents.AgentEarningsService.IntroductionResponse;
import com.hodi.modules.agents.AgentPayoutAccountService.PayoutAccountResponse;
import com.hodi.modules.agents.AgentPayoutAccountService.SavePayoutAccountRequest;
import com.hodi.modules.beneficiaries.PayoutAccountCheck;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingDtos.InstalmentLine;
import com.hodi.modules.bookings.BookingDtos.IntroducerRequest;
import com.hodi.modules.bookings.BookingService;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.developments.DevelopmentUnitRepository;
import com.hodi.modules.developments.DevelopmentUnitType;
import com.hodi.modules.developments.DevelopmentUnitTypeRepository;
import com.hodi.modules.leads.EnquiryService;
import com.hodi.modules.leads.EnquiryTicket;
import com.hodi.modules.leads.EnquiryTicketRepository;
import com.hodi.modules.leads.LeadDtos.DecideOfferRequest;
import com.hodi.modules.leads.LeadDtos.EnquiryResponse;
import com.hodi.modules.leads.LeadDtos.OfferResponse;
import com.hodi.modules.leads.LeadDtos.SubmitOfferRequest;
import com.hodi.modules.leads.PurchaseRequestService;
import com.hodi.modules.payments.PaymentDtos.ReceiveRequest;
import com.hodi.modules.payments.PaymentService;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.properties.Property;
import com.hodi.modules.sellerops.SellerOpsConstants;
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
 * Who brought the buyer travels from the enquiry to the offer to the booking, as early as the bank allows;
 * an agent says where they are paid; and an agent sees what they brought in through their own login.
 */
@SpringBootTest
@Transactional
class AgentAttributionIT {

    @TestConfiguration
    static class FakeBank {
        @Bean @Primary
        PayoutAccountCheck payoutAccountCheck() {
            return (bankCode, accountNo) -> accountNo != null && accountNo.endsWith("99")
                    ? new PayoutAccountCheck.Answer(accountNo, bankCode, null, "No such account at that bank.")
                    : new PayoutAccountCheck.Answer(accountNo, "0011", "CONFIRMED HOLDER", null);
        }
    }

    @Autowired IntroducerService introducers;
    @Autowired AgentPayoutAccountService payoutAccounts;
    @Autowired AgentEarningsService earnings;
    @Autowired EnquiryService enquiries;
    @Autowired EnquiryTicketRepository tickets;
    @Autowired PurchaseRequestService offers;
    @Autowired BookingService bookings;
    @Autowired UnitBookingRepository bookingRows;
    @Autowired PaymentService paymentService;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired DevelopmentUnitRepository units;
    @Autowired AgentProfileRepository agents;
    @Autowired ConfigurationService configs;
    @Autowired JdbcTemplate jdbc;

    private static final BigDecimal PRICE = new BigDecimal("9500000");

    private Long tenantId;
    private Long buyerUserId;
    private Development development;
    private Property unit;
    private AgentProfile agent;

    @BeforeEach
    void build() {
        tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        buyerUserId = jdbc.queryForObject("select id from users where status <> 5 order by id limit 1", Long.class);
        asSeller();
        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .tenantName("Test Seller").name("Introducer Gardens").developmentType("APARTMENT")
                .bankCommissionPercent(new BigDecimal("2.000"))
                .agentCommissionPercent(new BigDecimal("1.000"))
                .listingState(AppConstant.LISTING_LIVE).publishedAt(OffsetDateTime.now())
                .build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(PRICE).build());
        unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .tenantId(tenantId).tenantName("Test Seller")
                .unitTypeId(typology.getId()).unitLabel("G-1-02").price(PRICE)
                .saleState(AppConstant.UNIT_AVAILABLE).listingState(AppConstant.LISTING_LIVE)
                .publishedAt(OffsetDateTime.now())
                .constructionStatus(AppConstant.BUILD_PLANNED).build());
        agent = approvedAgent("Njeri Agent");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        // The row rolls back with the transaction; the cache does not, so it is emptied here while the
        // transaction is still open — after the rollback the next read finds the default again.
        configs.evictAll();
    }

    // ── attribution travels ───────────────────────────────────────────────────

    @Test
    @DisplayName("named on the enquiry, the agent is on the offer and on the booking it becomes")
    void namedOnTheEnquiryTravelsToTheBooking() {
        EnquiryTicket ticket = tickets.save(EnquiryTicket.builder()
                .reference(RrnGenerator.generate("EN")).tenantId(tenantId).tenantName("Test Seller")
                .propertyId(unit.getId()).propertyReference(unit.getReference()).propertyTitle("G-1-02")
                .userId(buyerUserId).buyerName("Asha Mwangi").subject("Is it still available?").build());
        EnquiryResponse named = enquiries.setIntroducer(ticket.getReference(), new IntroducerRequest(agent.getReference()));
        assertEquals(agent.getReference(), named.introducedByAgentRef());
        assertEquals("Njeri Agent", named.introducedByAgentName());

        asBuyer();
        OfferResponse offer = offers.submit(new SubmitOfferRequest(unit.getReference(), new BigDecimal("9000000"),
                null, null, null, null, "I would like to offer", "+254712000111"));
        assertEquals(agent.getReference(), offer.introducedByAgentRef(), "carried from the buyer's enquiry on this home");

        asSeller();
        offers.decide(offer.reference(), new DecideOfferRequest("ACCEPT", null));
        OfferResponse booked = offers.book(offer.reference(), null);
        UnitBooking booking = bookingRows.findById(HashIdUtil.decodeId(booked.bookingId())).orElseThrow();
        assertEquals(agent.getId(), booking.getIntroducedByAgentId(), "and from the offer onto the booking");

        HodiException e = assertThrows(HodiException.class,
                () -> offers.setIntroducer(offer.reference(), new IntroducerRequest("")));
        assertTrue(e.getMessage().contains("booking"), e.getMessage());
    }

    /*
     * The rule is tested with the setting passed in rather than written to the configuration table: the
     * configuration cache is shared with whatever else is running against this database, and a test that
     * depends on evicting it has been flaky before. What is read from the setting is a one-line lookup.
     */
    @Test
    @DisplayName("how early a name may be given is the bank's setting; the booking is always open")
    void theSettingDecidesHowEarly() {
        assertTrue(Set.of("ENQUIRY", "OFFER", "BOOKING").contains(introducers.attributionFrom()), "always one of the three");
        assertEquals(introducers.attributionFrom(), introducers.options().attributionFrom(), "the picker is told");
        assertFalse(introducers.options().agents().isEmpty(), "and offered the approved agents");

        assertTrue(IntroducerService.allowedAt("ENQUIRY", "ENQUIRY"));
        assertFalse(IntroducerService.allowedAt("ENQUIRY", "OFFER"));
        assertFalse(IntroducerService.allowedAt("OFFER", "BOOKING"));
        assertTrue(IntroducerService.allowedAt("OFFER", "OFFER"));
        assertTrue(IntroducerService.allowedAt("BOOKING", "BOOKING"), "the booking may always say");
        assertTrue(IntroducerService.allowedAt("BOOKING", "ENQUIRY"));

        HodiException e = assertThrows(HodiException.class,
                () -> IntroducerService.assertAllowedAt("ENQUIRY", "BOOKING"));
        assertTrue(e.getMessage().contains("not on an enquiry"), e.getMessage());
        assertTrue(e.getMessage().contains("from the booking on"), e.getMessage());

        BookingResponse booked = bookings.create(HashIdUtil.encodeId(development.getId()), request(agent.getReference()));
        assertEquals(agent.getReference(), booked.introducedByAgentRef());
    }

    // ── where an agent is paid ────────────────────────────────────────────────

    @Test
    @DisplayName("an agent adds accounts; the first is the default; only what the bank confirms is payable")
    void payoutAccounts() {
        asAgent(agent);
        PayoutAccountResponse first = payoutAccounts.addMine(new SavePayoutAccountRequest("11", "0011223344", "Njeri", null));
        assertTrue(first.defaultAccount(), "the first one is the default regardless");
        assertEquals("VERIFIED", first.verification());
        assertEquals("CONFIRMED HOLDER", first.confirmedName());
        assertTrue(first.payable());
        assertEquals("0011", first.bankCode(), "padded to four digits");

        PayoutAccountResponse second = payoutAccounts.addMine(new SavePayoutAccountRequest("11", "0099887799", null, null));
        assertFalse(second.defaultAccount());
        assertEquals("UNVERIFIED", second.verification());
        assertFalse(second.payable(), "the bank did not confirm it, so nothing is paid into it");
        assertNotNull(second.verificationNote());

        assertThrows(HodiException.class,
                () -> payoutAccounts.addMine(new SavePayoutAccountRequest("11", "0011223344", null, null)), "no twice");

        PayoutAccountResponse nowDefault = payoutAccounts.makeMineDefault(second.id());
        assertTrue(nowDefault.defaultAccount());
        assertFalse(payoutAccounts.mine().stream().filter(a -> a.id().equals(first.id())).findFirst().orElseThrow()
                .defaultAccount(), "one default at a time");
        assertEquals(first.accountNo(), payoutAccounts.payableDefault(agent.getId()).orElseThrow().getAccountNo(),
                "a settlement proposes the verified one, whatever is marked default");

        payoutAccounts.removeMine(second.id());
        List<PayoutAccountResponse> left = payoutAccounts.mine();
        assertEquals(1, left.size());
        assertTrue(left.get(0).defaultAccount(), "the next one along becomes the default");

        asBank();
        assertEquals(1, payoutAccounts.forAgent(agent.getReference()).size(), "the bank sees every account");
        AgentProfile other = approvedAgent("Other Agent");
        assertThrows(ResourceNotFoundException.class,
                () -> payoutAccounts.verifyFor(other.getReference(), first.id()), "not through another agent");
    }

    // ── what an agent brought in ──────────────────────────────────────────────

    @Test
    @DisplayName("an agent sees the bookings they brought and what each has earned them")
    void anAgentSeesTheirOwn() {
        BookingResponse booked = bookings.create(HashIdUtil.encodeId(development.getId()), request(agent.getReference()));
        agree(booked);

        asAgent(agent);
        List<IntroductionResponse> before = earnings.myIntroductions();
        IntroductionResponse open = before.stream().filter(i -> i.reference().equals(booked.reference())).findFirst().orElseThrow();
        assertEquals(AppConstant.BOOKING_RESERVED, open.state(), "seen from the day it is made");
        assertNull(open.commissionState(), "nothing earned until the sale completes");
        assertEquals("Introducer Gardens", open.developmentName());
        assertEquals("G-1-02", open.home());

        asSeller();
        paymentService.receive(new ReceiveRequest(booked.id(), PRICE, null, AppConstant.PAY_CHEQUE, null,
                "A7K2", null, null, null, null));
        bookings.complete(HashIdUtil.encodeId(development.getId()), booked.id());

        asAgent(agent);
        IntroductionResponse done = earnings.myIntroductions().stream()
                .filter(i -> i.reference().equals(booked.reference())).findFirst().orElseThrow();
        assertEquals(SellerOpsConstants.COMMISSION_DUE, done.commissionState());
        assertEquals(0, new BigDecimal("95000.00").compareTo(done.commissionAmount()));
        assertEquals(0, PRICE.compareTo(done.paid()));

        AgentTotals totals = earnings.myTotals();
        assertEquals(0, new BigDecimal("95000.00").compareTo(totals.due()));
        assertEquals(1, totals.completed());
        assertTrue(earnings.myCommissions(new com.hodi.common.dto.PagedDataRequest()).getContent().stream()
                .anyMatch(c -> booked.reference().equals(c.bookingRef())), "their own line, not the seller's list");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private CreateBookingRequest request(String agentRef) {
        return new CreateBookingRequest(HashIdUtil.encodeId(unit.getId()), "Asha Mwangi", "+254712000111",
                "asha@example.invalid", "12345678", PRICE, null, AppConstant.PLAN_LUMP_SUM, 14, null,
                List.of(new InstalmentLine("All of it", LocalDate.now(), PRICE)), agentRef);
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

    private void asSeller() { signIn(AppConstant.ACTOR_SELLER, "SELLER_OWNER", tenantId, false, buyerUserId + 1, 1L); }
    private void asBank() { signIn(AppConstant.ACTOR_PLATFORM, "SUPER_ADMIN", null, true, buyerUserId + 1, 1L); }
    private void asBuyer() { signIn(AppConstant.ACTOR_BUYER, "BUYER", null, false, buyerUserId, 2L); }
    private void asAgent(AgentProfile a) { signIn(AppConstant.ACTOR_SELLER, "AGENT", a.getTenantId(), false, a.getUserId(), a.getProfileId()); }

    private void signIn(String actor, String userType, Long tenant, boolean platform, Long userId, Long profileId) {
        User user = User.builder().id(userId).username("attribution-test").password("x")
                .email("a@example.invalid").firstName("At").lastName("Tribution")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(profileId).userId(userId)
                .profileType(actor).userTypeCode(userType)
                .tenantId(tenant).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("UNITS_MANAGE", "UNITS_SELL", "BOOKINGS_MANAGE", "PAYMENTS_RECEIVE", "ENQUIRIES_ASSIGN",
                        "PURCHASE_REQUESTS_DECIDE", "AGENT_SELF_VIEW", "AGENT_SELF_UPDATE", "AGENTS_DECIDE"),
                tenant == null ? List.of() : List.of(tenant), platform, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /** The buyer accepted the terms: every fixture here is about what happens after that. */
    private void agree(BookingResponse b) {
        // Through the entity, not JDBC: the booking is already in the persistence context and would read stale.
        UnitBooking row = bookingRows.findById(HashIdUtil.decodeId(b.id())).orElseThrow();
        row.setTermsState(BookingTermsService.TERMS_ACCEPTED);
        bookingRows.saveAndFlush(row);
    }
}
