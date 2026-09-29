package com.hodi.modules.notifications;

import com.hodi.common.AppConstant;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingDtos.InstalmentLine;
import com.hodi.modules.bookings.BookingService;
import com.hodi.modules.bookings.BookingTermsService;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.consent.ConsentService;
import com.hodi.modules.developments.*;
import com.hodi.modules.leads.EnquiryTicket;
import com.hodi.modules.leads.EnquiryTicketRepository;
import com.hodi.modules.notifications.ReminderRuleService.*;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.properties.Property;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
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
 * Reminders are rules: said the right number of days out, once per subject unless the rule repeats, and
 * an organisation may tune or switch off its own.
 */
@SpringBootTest
@Transactional
class ReminderSweepIT {

    @Autowired ReminderSweep sweep;
    @Autowired ReminderRuleService rules;
    @Autowired BookingService bookings;
    @Autowired UnitBookingRepository rows;
    @Autowired NotificationRepository inbox;
    @Autowired ReminderSentRepository sentRows;
    @Autowired ConsentService consent;
    @Autowired UserRepository users;
    @Autowired EnquiryTicketRepository enquiries;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitRepository units;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private User buyer;
    private Development development;
    private Property unit;

    @BeforeEach
    void build() {
        tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        buyer = users.save(User.builder().username("remind-" + System.nanoTime()).password("x")
                .email("remind" + System.nanoTime() + "@example.invalid").phone("+254700" + (System.nanoTime() % 1000000L))
                .firstName("Re").lastName("Mind").status(AppConstant.STATUS_ACTIVE).enabled(true).build());
        signInAsPlatform();
        consent.captureAtRegistration(buyer.getId(), false);
        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).tenantName("Reminder Seller").sellingTenantId(tenantId)
                .name("Reminder Heights").developmentType("APARTMENT").build());
        DevelopmentUnitType typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("1B").name("One bedroom").propertyType("APARTMENT").bedrooms((short) 1)
                .listPrice(new BigDecimal("5000000")).build());
        unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("R-1-01")
                .saleState(AppConstant.UNIT_AVAILABLE).constructionStatus(AppConstant.BUILD_PLANNED).build());
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    @DisplayName("an instalment due in the rule's days is said once; money overdue is said and said again as the rule repeats")
    void instalments() {
        LocalDate today = LocalDate.now();
        Long id = book(List.of(
                new InstalmentLine("Deposit", today.minusDays(5), new BigDecimal("500000")),
                new InstalmentLine("Second", today.plusDays(3), new BigDecimal("1000000")),
                new InstalmentLine("Balance", today.plusDays(90), new BigDecimal("3500000"))));

        // The sweep runs over the whole development database; what is asserted is this buyer's inbox.
        sweep.pass(today);
        List<Notification> mine = inbox.findTop8ByUserIdOrderByCreatedAtDesc(buyer.getId());
        assertEquals(2, mine.size(), mine.toString());
        assertTrue(mine.stream().anyMatch(n -> n.getEventCode().equals("INSTALMENT_DUE") && n.getTitle().contains("KES 1,000,000")),
                "the second instalment, three days out and uncovered");
        assertTrue(mine.stream().anyMatch(n -> n.getEventCode().equals("INSTALMENT_OVERDUE") && n.getTitle().contains("KES 500,000")),
                "the deposit, five days overdue");

        sweep.pass(today);
        assertEquals(2, inbox.findTop8ByUserIdOrderByCreatedAtDesc(buyer.getId()).size(), "said once; not again today");

        sweep.pass(today.plusDays(7));
        List<Notification> later = inbox.findTop8ByUserIdOrderByCreatedAtDesc(buyer.getId());
        assertEquals(3, later.size(), "the overdue one again a week later, while it lasts");
        assertEquals(1, later.stream().filter(n -> n.getEventCode().equals("INSTALMENT_DUE")).count(), "the due one never twice");
        assertNotNull(id);
    }

    @Test
    @DisplayName("an organisation may move the days or switch a rule off for its own bookings")
    void organisationTunes() {
        LocalDate today = LocalDate.now();
        book(List.of(new InstalmentLine("Second", today.plusDays(7), new BigDecimal("1000000"))));

        sweep.pass(today);
        assertEquals(0, dueNotices(), "seven days out; the platform says three");

        signInAsOrganisation(tenantId);
        rules.saveMine("INSTALMENT_DUE", new SaveOverrideRequest(null, 7, null));
        sweep.pass(today);
        assertEquals(1, dueNotices(), "this seller says seven");

        rules.saveMine("INSTALMENT_DUE", new SaveOverrideRequest(false, null, null));
        book(List.of(new InstalmentLine("Other", today.plusDays(3), new BigDecimal("100000"))));
        sweep.pass(today);
        assertEquals(1, dueNotices(), "switched off for this seller: nothing new");

        Effective platformOnly = rules.resolve("INSTALMENT_DUE", null, null);
        assertTrue(platformOnly.enabled() && platformOnly.days() == 3, "the platform's own is untouched");
    }

    @Test
    @DisplayName("an enquiry the seller has not answered is said after the rule's days, keyed on the buyer's last word")
    void enquiries() {
        LocalDate today = LocalDate.now();
        signInAsPlatform();
        EnquiryTicket ticket = enquiries.save(EnquiryTicket.builder()
                .reference(RrnGenerator.generate("EQ")).tenantId(tenantId).tenantName("Reminder Seller")
                .propertyId(unit.getId()).propertyReference(unit.getReference()).propertyTitle("R-1-01")
                .userId(buyer.getId()).buyerName("Re Mind").state(AppConstant.ENQUIRY_OPEN)
                .lastMessageSide(AppConstant.SIDE_BUYER).lastMessageAt(OffsetDateTime.now().minusDays(3)).build());

        String key = String.valueOf(ticket.getLastMessageAt().toEpochSecond());
        sweep.pass(today);
        assertEquals(1, timesSaid("ENQUIRY_UNANSWERED", "ENQUIRY", ticket.getId(), key));
        sweep.pass(today);
        assertEquals(1, timesSaid("ENQUIRY_UNANSWERED", "ENQUIRY", ticket.getId(), key), "said once for that message");
        sweep.pass(today.plusDays(3));
        assertEquals(2, timesSaid("ENQUIRY_UNANSWERED", "ENQUIRY", ticket.getId(), key), "and again as the rule repeats");

        ticket.setLastMessageSide(AppConstant.SIDE_SELLER);
        enquiries.save(ticket);
        sweep.pass(today.plusDays(10));
        assertEquals(2, timesSaid("ENQUIRY_UNANSWERED", "ENQUIRY", ticket.getId(), key), "answered: nothing more to say");
    }

    @Test
    @DisplayName("the layering and the repeat arithmetic, as pure functions")
    void arithmetic() {
        ReminderRule rule = ReminderRule.builder().code("X").description("x").enabled(true).days(3).repeatEveryDays(7).build();
        assertEquals(new Effective("X", true, 3, 7), ReminderRuleService.apply(rule, null));
        assertEquals(new Effective("X", true, 5, 2), ReminderRuleService.apply(rule,
                ReminderRuleOverride.builder().ruleCode("X").tenantId(1L).days(5).repeatEveryDays(2).build()));
        assertFalse(ReminderRuleService.apply(rule, ReminderRuleOverride.builder().ruleCode("X").tenantId(1L).enabled(false).build()).enabled());
        rule.setEnabled(false);
        assertFalse(ReminderRuleService.apply(rule, ReminderRuleOverride.builder().ruleCode("X").tenantId(1L).enabled(true).build()).enabled(),
                "an organisation cannot switch on what the platform switched off");

        Effective once = new Effective("ONCE", true, 1, null);
        OffsetDateTime now = OffsetDateTime.now();
        assertTrue(rules.claim(once, "THING", 1L, "k", now));
        assertFalse(rules.claim(once, "THING", 1L, "k", now.plusDays(30)), "never twice without a repeat");
        assertTrue(rules.claim(once, "THING", 1L, "other", now), "a different key is a different thing");
        Effective weekly = new Effective("WEEKLY", true, 1, 7);
        assertTrue(rules.claim(weekly, "THING", 2L, "", now));
        assertFalse(rules.claim(weekly, "THING", 2L, "", now.plusDays(6)), "not yet");
        assertTrue(rules.claim(weekly, "THING", 2L, "", now.plusDays(7)), "a week on");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private long dueNotices() {
        return inbox.findTop8ByUserIdOrderByCreatedAtDesc(buyer.getId()).stream().filter(n -> n.getEventCode().equals("INSTALMENT_DUE")).count();
    }

    private int timesSaid(String rule, String type, Long id, String key) {
        return sentRows.findByRuleCodeAndSubjectTypeAndSubjectIdAndSubjectKey(rule, type, id, key).map(ReminderSent::getTimes).orElse(0);
    }

    private int nextUnit = 2;

    private Long book(List<InstalmentLine> plan) {
        signInAsPlatform();
        BookingResponse booking = bookings.create(HashIdUtil.encodeId(development.getId()), new CreateBookingRequest(
                HashIdUtil.encodeId(unit.getId()), "Re Mind", buyer.getPhone(), buyer.getEmail(), null,
                new BigDecimal("5000000"), new BigDecimal("500000"), AppConstant.PLAN_INSTALMENTS, 14, null, plan));
        Long id = HashIdUtil.decodeId(booking.id());
        UnitBooking row = rows.findById(id).orElseThrow();
        row.setBuyerUserId(buyer.getId());
        row.setTermsState(BookingTermsService.TERMS_ACCEPTED);
        rows.saveAndFlush(row);
        // The next booking needs a free home: release this one so the fixture can book again.
        Property home = units.findById(unit.getId()).orElseThrow();
        unit = units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(home.getUnitTypeId()).unitLabel("R-1-" + String.format("%02d", nextUnit++))
                .saleState(AppConstant.UNIT_AVAILABLE).constructionStatus(AppConstant.BUILD_PLANNED).build());
        return id;
    }

    private void signInAsPlatform() {
        User user = User.builder().id(7L).username("remind-admin").password("x")
                .email("ra@example.invalid").firstName("Re").lastName("Admin")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(7L).userId(7L)
                .profileType(AppConstant.ACTOR_PLATFORM).userTypeCode("SUPER_ADMIN").status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of("BOOKINGS_MANAGE", "UNITS_SELL", "UNITS_MANAGE", "APP_SETTINGS_UPDATE"), List.of(), true, true));
    }

    private void signInAsOrganisation(Long tenant) {
        User user = User.builder().id(8L).username("remind-owner").password("x")
                .email("ro@example.invalid").firstName("Re").lastName("Owner")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(8L).userId(8L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER").tenantId(tenant).tenantName("Reminder Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of("APP_SETTINGS_OVERRIDE"), List.of(tenant), false, true));
    }

    private static void signIn(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
