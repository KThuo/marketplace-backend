package com.hodi.modules.sellerops;

import com.hodi.common.AppConstant;
import com.hodi.modules.bookings.BookingTermsService;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.agents.AgentProfile;
import com.hodi.modules.agents.AgentProfileRepository;
import com.hodi.modules.agents.AgentState;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingDtos.InstalmentLine;
import com.hodi.modules.bookings.BookingService;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.developments.DevelopmentUnitRepository;
import com.hodi.modules.developments.DevelopmentUnitType;
import com.hodi.modules.developments.DevelopmentUnitTypeRepository;
import com.hodi.modules.payments.PaymentDtos.ReceiveRequest;
import com.hodi.modules.payments.PaymentService;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.properties.Property;
import com.hodi.modules.reports.ReportService;
import com.hodi.modules.reports.ReportService.ReportQuery;
import com.hodi.modules.reports.ReportService.ReportResult;
import com.hodi.modules.sellerops.CommissionService.CommissionListRequest;
import com.hodi.modules.sellerops.CommissionService.CommissionResponse;
import com.hodi.modules.sellerops.CommissionService.CommissionTotals;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The three statements read what the screens wrote: every commission line, every sale the bank collected
 * and how its settlement stands, and every buyer an agent brought. And the commission page's totals say
 * whose the money is.
 */
@SpringBootTest
@Transactional
class CommissionReportsIT {

    @Autowired ReportService reports;
    @Autowired UnitBookingRepository bookingRows;
    @Autowired CommissionService commissions;
    @Autowired BookingService bookings;
    @Autowired PaymentService paymentService;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired DevelopmentUnitRepository units;
    @Autowired AgentProfileRepository agents;
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
                .tenantName("Test Seller").name("Statement Terraces " + RrnGenerator.generate("X")).developmentType("APARTMENT")
                .collectionMode(Development.COLLECTED_BY_BANK)
                .bankCommissionPercent(new BigDecimal("2.000"))
                .agentCommissionPercent(new BigDecimal("1.000"))
                .build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(PRICE).build());
        unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("T-1-01")
                .saleState(AppConstant.UNIT_AVAILABLE)
                .constructionStatus(AppConstant.BUILD_PLANNED).build());
        agent = approvedAgent("Report Agent");
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    @DisplayName("the commission statement lists both lines of a sale, says whose each is, and the page's totals agree")
    void commissionStatementAndTotals() {
        BookingResponse sale = sell(agent.getReference());
        asBank();

        ReportResult out = reports.run("COMMISSION", forThisDevelopment());
        assertEquals(2, out.rows().size(), "the bank's line and the agent's");
        Map<String, Object> agentRow = out.rows().stream().filter(r -> "Agent".equals(r.get("earned_by"))).findFirst().orElseThrow();
        assertEquals("Report Agent", agentRow.get("agent_name"));
        assertEquals(sale.reference(), agentRow.get("booking_ref"));
        assertEquals("The seller", agentRow.get("borne_by"));
        assertEquals("DUE", agentRow.get("state"));
        assertEquals(0, new BigDecimal("285000.00").compareTo(out.totals().get("amount")), "190,000 + 95,000");
        assertTrue(out.columns().stream().anyMatch(c -> "paid_by_transfer".equals(c.key())));

        CommissionTotals totals = commissions.totals();
        assertTrue(totals.bankOutstanding().compareTo(new BigDecimal("190000")) >= 0);
        assertTrue(totals.agentOutstanding().compareTo(new BigDecimal("95000")) >= 0);
        assertEquals(0, totals.outstanding().compareTo(totals.bankOutstanding().add(totals.agentOutstanding())),
                "the whole is the two parts");

        CommissionListRequest byPeriod = new CommissionListRequest();
        byPeriod.setFrom(LocalDate.now().plusDays(1));
        byPeriod.setTo(LocalDate.now().plusDays(2));
        assertTrue(commissions.list(byPeriod).getContent().stream().noneMatch(c -> sale.reference().equals(c.bookingRef())),
                "a period the sale is not in leaves it out");
        CommissionListRequest today = new CommissionListRequest();
        today.setFrom(LocalDate.now());
        today.setTo(LocalDate.now());
        List<CommissionResponse> found = commissions.list(today).getContent().stream()
                .filter(c -> sale.reference().equals(c.bookingRef())).toList();
        assertEquals(2, found.size());
    }

    @Test
    @DisplayName("the sale settlements statement shows a bank-collected sale awaiting settlement, with the owner's net")
    void saleSettlementsStatement() {
        BookingResponse sale = sell(agent.getReference());
        asBank();
        ReportResult out = reports.run("SALE_SETTLEMENTS", forThisDevelopment());
        assertEquals(1, out.rows().size());
        Map<String, Object> row = out.rows().get(0);
        assertEquals(sale.reference(), row.get("booking_ref"));
        assertEquals("T-1-01", row.get("home"));
        assertEquals("Awaiting settlement", row.get("state"));
        assertEquals(0, PRICE.compareTo(new BigDecimal(String.valueOf(row.get("gross")))));
        assertEquals(0, new BigDecimal("9215000").compareTo(new BigDecimal(String.valueOf(row.get("net_to_owner")))));
        assertEquals("The seller", row.get("agent_fee_borne_by"));

        asSeller();
        ReportResult owners = reports.run("SALE_SETTLEMENTS", forThisDevelopment());
        assertEquals(1, owners.rows().size(), "the owner reads their own");
    }

    @Test
    @DisplayName("the agents' sales statement shows every buyer an agent brought, from the day of booking")
    void agentSalesStatement() {
        BookingResponse booked = bookings.create(HashIdUtil.encodeId(development.getId()), request(agent.getReference()));
        asBank();
        ReportResult out = reports.run("AGENT_SALES", forThisDevelopment());
        assertEquals(1, out.rows().size());
        Map<String, Object> row = out.rows().get(0);
        assertEquals("Report Agent", row.get("agent_name"));
        assertEquals(booked.reference(), row.get("booking_ref"));
        assertEquals("Reserved", row.get("state"));
        assertNull(row.get("commission"), "nothing earned until the sale completes");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ReportQuery forThisDevelopment() {
        return new ReportQuery(LocalDate.now().minusDays(1), LocalDate.now(), null,
                Map.of("development_name", development.getName()), List.of(), 0, 50);
    }

    private BookingResponse sell(String agentRef) {
        asSeller();
        BookingResponse booked = bookings.create(HashIdUtil.encodeId(development.getId()), request(agentRef));
        agree(booked);
        paymentService.receive(new ReceiveRequest(booked.id(), PRICE, null, AppConstant.PAY_CHEQUE, null,
                "A7K2", null, null, null, null));
        return bookings.complete(HashIdUtil.encodeId(development.getId()), booked.id());
    }

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

    private void asSeller() { signIn(AppConstant.ACTOR_SELLER, "SELLER_OWNER", tenantId, false); }
    private void asBank() { signIn(AppConstant.ACTOR_PLATFORM, "SUPER_ADMIN", null, true); }

    private void signIn(String actor, String userType, Long tenant, boolean platform) {
        User user = User.builder().id(1L).username("report-test").password("x")
                .email("r@example.invalid").firstName("Re").lastName("Port")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(actor).userTypeCode(userType)
                .tenantId(tenant).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("UNITS_MANAGE", "UNITS_SELL", "BOOKINGS_MANAGE", "PAYMENTS_RECEIVE", "REPORTS_VIEW",
                        "COMMISSIONS_VIEW", "SETTLEMENTS_VIEW", "DEVELOPMENTS_FINANCE_VIEW"),
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
