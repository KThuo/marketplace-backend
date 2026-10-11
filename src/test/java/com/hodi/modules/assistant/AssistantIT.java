package com.hodi.modules.assistant;

import com.hodi.common.AppConstant;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.assistant.AssistantKnowledgeService.KnowledgeRequest;
import com.hodi.modules.assistant.AssistantProvider.Action;
import com.hodi.modules.assistant.AssistantService.*;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.CreateBookingRequest;
import com.hodi.modules.bookings.BookingPolicyService;
import com.hodi.modules.bookings.BookingService;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.developments.DevelopmentUnitRepository;
import com.hodi.modules.developments.DevelopmentUnitType;
import com.hodi.modules.developments.DevelopmentUnitTypeRepository;
import com.hodi.modules.leads.EnquiryTicket;
import com.hodi.modules.leads.EnquiryTicketRepository;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.properties.Property;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.UserPrincipal;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.hodi.modules.assistant.AssistantConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The assistant, phase 1: memory, routing, visitors, ratings, knowledge, hand-off and insights.
 *
 * <p>Runs against the shared dev database inside a rolled-back transaction, so every assertion is about rows
 * this test made, never about totals.
 */
@SpringBootTest
@Transactional
class AssistantIT {

    @Autowired AssistantService assistant;
    @Autowired AssistantKnowledgeService knowledge;
    @Autowired AssistantConversationRepository conversations;
    @Autowired EnquiryTicketRepository enquiries;
    @Autowired BookingService bookings;
    @Autowired UnitBookingRepository bookingRows;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitRepository units;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired UserRepository users;
    @Autowired ConfigurationService configs;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    private User buyer;

    @BeforeEach
    void build() {
        buyer = users.save(User.builder().username("as-buyer-" + System.nanoTime()).password("x")
                .email("as" + System.nanoTime() + "@example.invalid").phone("+2547" + (System.nanoTime() % 100000000L))
                .firstName("Asha").lastName("Mwangi").status(AppConstant.STATUS_ACTIVE).enabled(true).build());
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        configs.evictAll();
    }

    // ── memory and routing ────────────────────────────────────────────────────

    @Test
    @DisplayName("a follow-up keeps the last search's filters, and \"new search\" drops them")
    void remembersTheSearch() throws Exception {
        signInAsBuyer(buyer);
        ConversationDetail first = assistant.ask(new AskRequest(null, "three bedrooms under 20m"));
        String ref = first.conversation().reference();
        assertEquals(INTENT_SEARCH, last(first).intent());
        JsonNode filters = filters(ref);
        assertEquals(3, filters.get("bedrooms").asInt());
        assertEquals(0, new BigDecimal("20000000").compareTo(filters.get("maxPrice").decimalValue()));

        ConversationDetail second = assistant.ask(new AskRequest(ref, "what about under 15m"));
        assertEquals(INTENT_SEARCH, last(second).intent());
        filters = filters(ref);
        assertEquals(3, filters.get("bedrooms").asInt(), "the bedrooms were kept");
        assertEquals(0, new BigDecimal("15000000").compareTo(filters.get("maxPrice").decimalValue()), "the budget moved");

        assistant.ask(new AskRequest(ref, "new search, apartments to rent"));
        filters = filters(ref);
        assertTrue(filters.get("bedrooms").isNull(), "a new search forgets the bedrooms");
        assertEquals("RENT", filters.get("listingType").asString());
        assertEquals("APARTMENT", filters.get("propertyType").asString());
    }

    @Test
    @DisplayName("whole words: \"agent\" alone is not a hand-off, \"current\" is not \"rent\", and greetings are answered")
    void routesByWholeWords() {
        signInAsBuyer(buyer);
        String ref = assistant.ask(new AskRequest(null, "hello")).conversation().reference();
        assertEquals(INTENT_GREETING, last(assistant.find(ref)).intent());

        assertNotEquals(INTENT_HANDOFF, last(assistant.ask(new AskRequest(ref, "what does the agent's commission cost me"))).intent());

        MessageResponse stamp = last(assistant.ask(new AskRequest(ref, "what is the current stamp duty")));
        assertEquals(INTENT_EXPLAIN, stamp.intent());
        assertTrue(stamp.body().contains("Stamp duty"));

        MessageResponse person = last(assistant.ask(new AskRequest(ref, "can I talk to a person")));
        assertEquals(INTENT_HANDOFF, person.intent());
        assertEquals(ACTION_HANDOFF, person.actions().get(0).type());

        assertEquals(INTENT_UNKNOWN, last(assistant.ask(new AskRequest(ref, "do you sell cars"))).intent());
    }

    @Test
    @DisplayName("a viewing names the listing asked for, and asks which when nothing has been shown")
    void viewing() {
        signInAsBuyer(buyer);
        MessageResponse which = last(assistant.ask(new AskRequest(null, "can I see the second one")));
        assertEquals(INTENT_VIEWING, which.intent());
        assertTrue(which.actions().isEmpty(), "nothing shown yet, so it asks which");

        MessageResponse named = last(assistant.ask(new AskRequest(null, "I would like a viewing of PR260101ABCD")));
        assertEquals(INTENT_VIEWING, named.intent());
        Action card = named.actions().get(0);
        assertEquals(ACTION_VIEWING, card.type());
        assertTrue(json.writeValueAsString(card.data()).contains("PR260101ABCD"));
    }

    @Test
    @DisplayName("\"tell me when\" offers an alert built from the search in force")
    void alert() {
        signInAsBuyer(buyer);
        String ref = assistant.ask(new AskRequest(null, "two bedrooms under 12m")).conversation().reference();
        MessageResponse alert = last(assistant.ask(new AskRequest(ref, "tell me when something comes up")));
        assertEquals(INTENT_ALERT, alert.intent());
        Action card = alert.actions().get(0);
        assertEquals(ACTION_ALERT, card.type());
        assertEquals(2, ((Number) card.data().get("minBedrooms")).intValue());
        assertNotNull(card.data().get("name"));
    }

    // ── their own things ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a buyer's bookings and their terms come from the booking and the development's own policy")
    void bookingsAndTerms() {
        Development d = development("Assistant Terms Court", false);
        Property unit = unit(d, "AT-1-01");
        signInAsPlatform("BOOKINGS_VIEW", "BOOKINGS_MANAGE", "UNITS_SELL", "UNITS_MANAGE");
        BookingResponse booking = bookings.create(HashIdUtil.encodeId(d.getId()), new CreateBookingRequest(
                HashIdUtil.encodeId(unit.getId()), "Asha Mwangi", "+254712000111", "asha@example.invalid", null,
                new BigDecimal("9500000"), new BigDecimal("950000"), AppConstant.PLAN_INSTALMENTS, 14, null, List.of()));
        UnitBooking row = bookingRows.findById(HashIdUtil.decodeId(booking.id())).orElseThrow();
        row.setBuyerUserId(buyer.getId());
        bookingRows.saveAndFlush(row);

        signInAsBuyer(buyer);
        MessageResponse owed = last(assistant.ask(new AskRequest(null, "what do I owe")));
        assertEquals(INTENT_MY_BOOKINGS, owed.intent());
        assertTrue(owed.body().contains("Assistant Terms Court"), owed.body());
        assertTrue(owed.body().contains("Reserved"), owed.body());
        assertTrue(owed.body().contains("waiting for you to accept"), "the terms are still to accept");

        MessageResponse terms = last(assistant.ask(new AskRequest(null, "can I get a refund")));
        assertEquals(INTENT_TERMS, terms.intent());
        assertTrue(terms.body().contains("5% of what you paid"), terms.body());
        assertTrue(terms.body().contains("revived within 45 days"), terms.body());
    }

    @Test
    @DisplayName("how many are left in a development, by name, from the public availability")
    void unitsLeft() {
        Development d = development("Assistant Gardens Kilele", true);
        unit(d, "AG-1-01");
        signInAsBuyer(buyer);
        MessageResponse left = last(assistant.ask(new AskRequest(null, "how many units are left at Assistant Gardens Kilele?")));
        assertEquals(INTENT_UNITS_LEFT, left.intent());
        assertTrue(left.body().startsWith("Assistant Gardens Kilele"), left.body());
        assertEquals("/development/" + d.getReference(), left.links().get(0).to());
    }

    // ── visitors ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a visitor asks without an account, is asked to sign in for their own things, and keeps the conversation on signing in")
    void visitor() {
        String token = token();
        SecurityContextHolder.clearContext();
        ConversationDetail asked = assistant.askAsVisitor(token, "10.0.0.7", new AskRequest(null, "what do I owe"));
        String ref = asked.conversation().reference();
        MessageResponse answer = last(asked);
        assertEquals(INTENT_MY_BOOKINGS, answer.intent());
        assertEquals(ACTION_SIGN_IN, answer.actions().get(0).type());

        assertThrows(ResourceNotFoundException.class, () -> assistant.findAsVisitor(token(), ref),
                "another browser cannot read it");
        ConversationDetail rated = assistant.rateAsVisitor(token, ref, answer.id(), new RateRequest(-1));
        assertEquals((short) -1, last(rated).rating());

        signInAsBuyer(buyer);
        assertThrows(ResourceNotFoundException.class, () -> assistant.find(ref), "not theirs until claimed");
        assertEquals(1, assistant.claim(new ClaimRequest(token)).claimed());
        assertEquals(ref, assistant.find(ref).conversation().reference(), "now it is");
        assertEquals(0, assistant.claim(new ClaimRequest(token)).claimed(), "harmless to repeat");
        assertThrows(ResourceNotFoundException.class, () -> assistant.findAsVisitor(token, ref),
                "the visitor token no longer reads it");

        HodiException bad = assertThrows(HodiException.class,
                () -> assistant.askAsVisitor("short", null, new AskRequest(null, "hi")));
        assertEquals(HttpStatus.BAD_REQUEST, bad.getStatus());
    }

    @Test
    @DisplayName("a visitor is limited per hour, by token and by address")
    void visitorLimit() {
        setting("assistant.visitor.hourly.limit", "2");
        try {
            String token = token();
            String ref = assistant.askAsVisitor(token, "10.0.0.9", new AskRequest(null, "hello")).conversation().reference();
            assistant.askAsVisitor(token, "10.0.0.9", new AskRequest(ref, "thanks"));
            HodiException limited = assertThrows(HodiException.class,
                    () -> assistant.askAsVisitor(token, "10.0.0.9", new AskRequest(ref, "one more")));
            assertEquals(HttpStatus.TOO_MANY_REQUESTS, limited.getStatus());
            assertThrows(HodiException.class,
                    () -> assistant.askAsVisitor(token(), "10.0.0.9", new AskRequest(null, "a new browser, same address")),
                    "a fresh token from the same address is still counted");
        } finally {
            setting("assistant.visitor.hourly.limit", "40");
        }
    }

    // ── knowledge, hand-off, insights ─────────────────────────────────────────

    @Test
    @DisplayName("the platform adds what the assistant explains, and switching it off stops it being said")
    void knowledge() {
        signInAsPlatform("ASSISTANT_VIEW", "ASSISTANT_MANAGE");
        var entry = knowledge.create(new KnowledgeRequest("title deed", "title deeds\nlogbook for land",
                "A title deed is the document that proves who owns the land.", "Browse land", "/?propertyType=LAND", true));
        assertThrows(HodiException.class, () -> knowledge.create(new KnowledgeRequest("Title Deed", null, "again", null, null, true)),
                "one entry per term");
        assertThrows(HodiException.class, () -> knowledge.create(new KnowledgeRequest("half link", null, "x", "Label", null, true)),
                "a link needs both halves");

        signInAsBuyer(buyer);
        MessageResponse said = last(assistant.ask(new AskRequest(null, "what is a logbook for land")));
        assertEquals(INTENT_EXPLAIN, said.intent());
        assertTrue(said.body().startsWith("A title deed"));
        assertEquals("/?propertyType=LAND", said.links().get(0).to());

        signInAsPlatform("ASSISTANT_VIEW", "ASSISTANT_MANAGE");
        knowledge.update(entry.reference(), new KnowledgeRequest("title deed", "title deeds\nlogbook for land",
                "A title deed is the document that proves who owns the land.", null, null, false));
        signInAsBuyer(buyer);
        assertNotEquals(INTENT_EXPLAIN, last(assistant.ask(new AskRequest(null, "what is a title deed"))).intent());
    }

    @Test
    @DisplayName("a general hand-off goes where the platform says: its own staff, or nowhere when switched off")
    void generalHandOff() {
        signInAsBuyer(buyer);
        String ref = assistant.ask(new AskRequest(null, "do you sell cars")).conversation().reference();
        assertTrue(assistant.find(ref).generalHandOff());

        ConversationDetail passed = assistant.handOff(ref, new HandOffRequest(null, "I have a question about fees."));
        assertEquals(STATE_HANDED_OFF, passed.conversation().state());
        assertTrue(last(passed).body().contains("The Hodi team"), last(passed).body());
        EnquiryTicket ticket = enquiries.findByReference(passed.conversation().enquiryRef()).orElseThrow();
        assertNull(ticket.getPropertyId());
        assertNull(ticket.getTenantId(), "no organisation: the platform's own staff answer it");
        assertThrows(HodiException.class, () -> assistant.ask(new AskRequest(ref, "hello?")), "it is with a person now");

        setting("assistant.handoff.general", "OFF");
        try {
            String another = assistant.ask(new AskRequest(null, "do you sell boats")).conversation().reference();
            assertFalse(assistant.find(another).generalHandOff());
            HodiException off = assertThrows(HodiException.class, () -> assistant.handOff(another, new HandOffRequest(null, null)));
            assertEquals(HttpStatus.CONFLICT, off.getStatus());
        } finally {
            setting("assistant.handoff.general", "PLATFORM");
        }
    }

    @Test
    @DisplayName("insights list what it could not answer, with the question, and what was disliked")
    void insights() {
        signInAsBuyer(buyer);
        // Letters only: digits in the question would be read, correctly, as a budget.
        String question = "can you book me a taxi " + UUID.randomUUID().toString().replaceAll("[^a-f]", "");
        ConversationDetail d = assistant.ask(new AskRequest(null, question));
        assistant.rate(d.conversation().reference(), last(d).id(), new RateRequest(-1));

        signInAsPlatform("ASSISTANT_VIEW");
        PagedDataRequest page = new PagedDataRequest();
        page.setSize(100);
        Insights insights = assistant.insights(1, page);
        assertTrue(insights.unanswered() >= 1);
        assertTrue(insights.disliked() >= 1);
        FailingAnswer ours = insights.failing().getContent().stream()
                .filter(f -> question.equals(f.question())).findFirst().orElseThrow();
        assertEquals(INTENT_UNKNOWN, ours.intent());
        assertEquals((short) -1, ours.rating());
        assertFalse(ours.visitor());
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private static MessageResponse last(ConversationDetail d) {
        return d.messages().get(d.messages().size() - 1);
    }

    private JsonNode filters(String reference) {
        em.flush();
        String context = conversations.findByReference(reference).orElseThrow().getContext();
        return json.readTree(context).get("filters");
    }

    private Development development(String name, boolean live) {
        Long tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        Development d = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).tenantName("Assistant Seller").sellingTenantId(tenantId)
                .name(name).developmentType("APARTMENT")
                .listingState(live ? "LIVE" : "DRAFT").publishedAt(live ? java.time.OffsetDateTime.now() : null)
                .refundPenaltyBasis(BookingPolicyService.BASIS_PERCENT_OF_PAID).refundPenaltyRate(new BigDecimal("5"))
                .reviveWithinDays(45)
                .build());
        unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(d.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(new BigDecimal("9500000")).build());
        return d;
    }

    private Property unit(Development d, String label) {
        DevelopmentUnitType type = unitTypes.findForDevelopment(d.getId()).get(0);
        return units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(d.getId())
                .unitTypeId(type.getId()).unitLabel(label)
                .saleState(AppConstant.UNIT_AVAILABLE).constructionStatus(AppConstant.BUILD_PLANNED).build());
    }

    private void setting(String key, String value) {
        jdbc.update("update configurations set config_value = ? where config_key = ?", value, key);
        em.flush();
        em.clear();
        configs.evictAll();
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "") + "v";
    }

    private static void signInAsBuyer(User user) {
        UserProfile profile = UserProfile.builder().id(user.getId()).userId(user.getId())
                .profileType(AppConstant.ACTOR_BUYER).userTypeCode("BUYER").status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of(), List.of(), false, true));
    }

    private static void signInAsPlatform(String... permissions) {
        User user = User.builder().id(7L).username("assistant-admin").password("x").email("aa@example.invalid")
                .firstName("Assist").lastName("Admin").status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(7L).userId(7L).profileType(AppConstant.ACTOR_PLATFORM)
                .userTypeCode("SUPER_ADMIN").status(AppConstant.STATUS_ACTIVE).build();
        signIn(UserPrincipal.of(user, profile, Set.of(permissions), List.of(), true, true));
    }

    private static void signIn(UserPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
