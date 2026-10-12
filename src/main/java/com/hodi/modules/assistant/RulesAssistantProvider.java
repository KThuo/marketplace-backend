package com.hodi.modules.assistant;

import com.hodi.common.AppConstant;
import com.hodi.modules.assistant.AssistantContext.Filters;
import com.hodi.modules.assistant.AssistantContext.Shown;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingPolicyService;
import com.hodi.modules.bookings.BookingService;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.developments.DevelopmentDtos.PublicDevelopmentResponse;
import com.hodi.modules.developments.DevelopmentDtos.PublicUnitTypeResponse;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.developments.PublicDevelopmentService;
import com.hodi.modules.finance.AffordabilityService;
import com.hodi.modules.finance.FinanceDtos;
import com.hodi.modules.leads.EnquiryTicketRepository;
import com.hodi.modules.leads.SiteVisitRepository;
import com.hodi.modules.properties.PropertyDtos;
import com.hodi.modules.properties.PublicPropertyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.hodi.modules.assistant.AssistantConstants.*;

/**
 * The assistant, as rules over the platform's own capabilities (M11).
 *
 * <h2>Why rules and not a model</h2>
 *
 * <p>The platform decided against a language model for now. So this answers from real data, says plainly what
 * it is, and hands over to a person the moment it is out of its depth. Everything it does is something the
 * platform already does: search is {@code PublicPropertyService}, affordability is the public calculator's
 * arithmetic, a booking is read through {@code BookingService} exactly as the buyer's own page reads it.
 *
 * <h2>Reading a question</h2>
 *
 * <p>Whole words and phrases, checked in a deliberate order, most specific first: "tell me when a three-bed
 * comes up" is an alert, not a search; "what can I afford on 200k" is borrowing, not a search for something
 * costing 200,000; "where is my viewing" is the buyer's own activity, not a request for a new one.
 *
 * <h2>Remembering</h2>
 *
 * <p>Each answer returns what the conversation should remember: the search in force and what was shown. A
 * search that names only a budget keeps the town and bedrooms from before, and says so, unless the person
 * asks to start again.
 *
 * <h2>Visitors</h2>
 *
 * <p>A visitor gets everything the public marketplace would show them. A question about their own things gets a
 * sign-in card that brings them back to this conversation, not a refusal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RulesAssistantProvider implements AssistantProvider {

    /** "20m", "20 million", "1.5m", "850k", "20,000,000". */
    private static final Pattern MONEY = Pattern.compile(
            "(\\d[\\d,.]*)\\s*(m\\b|million|k\\b|thousand)?", Pattern.CASE_INSENSITIVE);

    /** "3 bed", "3-bedroom", "three bedroom". Written numbers too, because that is how people type it. */
    private static final Pattern BEDROOMS = Pattern.compile(
            "(\\d|one|two|three|four|five|six|seven)\\s*(?:-|\\s)?\\s*(?:bed|beds|bedroom|bedrooms|br)\\b",
            Pattern.CASE_INSENSITIVE);

    /** "the second one", "number 2", "#2", "2nd". */
    private static final Pattern ORDINAL = Pattern.compile(
            "\\b(first|second|third|fourth|1st|2nd|3rd|4th)\\b|(?:number|no\\.?|#)\\s*([1-4])\\b",
            Pattern.CASE_INSENSITIVE);

    /** A listing's or development's reference typed into the question. */
    private static final Pattern REFERENCE = Pattern.compile("\\b([A-Z]{2}\\d{6}[A-Z0-9]{4})\\b");

    private static final Map<String, Integer> WRITTEN = Map.of(
            "one", 1, "two", 2, "three", 3, "four", 4, "five", 5, "six", 6, "seven", 7);
    private static final Map<String, Integer> ORDINALS = Map.of(
            "first", 1, "1st", 1, "second", 2, "2nd", 2, "third", 3, "3rd", 3, "fourth", 4, "4th", 4);

    /** A phrase, never a lone word: "agent" alone is usually about an agent's commission, not a request for one. */
    private static final List<String> HANDOFF = List.of(
            "talk to a person", "talk to someone", "talk to somebody", "talk to a human", "talk to a real person",
            "speak to a person", "speak to someone", "speak to somebody", "speak to a human", "real person",
            "human", "customer care", "customer service", "call me", "talk to an agent", "speak to an agent",
            "pass me on", "hand me over");

    private static final List<String> ALERT = List.of(
            "tell me when", "let me know when", "notify me", "alert me", "set an alert", "set up an alert",
            "create an alert", "email me when", "text me when", "keep me posted");

    private static final List<String> AFFORD = List.of(
            "afford", "borrow", "mortgage on", "how much can i", "salary", "income", "take home", "take-home");

    private static final List<String> MY_BOOKINGS = List.of(
            "my booking", "my bookings", "my unit", "my reservation", "what do i owe", "how much do i owe",
            "i still owe", "my balance", "my instalment", "my instalments", "next instalment", "next payment",
            "when do i pay", "when is my payment", "payment due", "how much have i paid");

    private static final List<String> TERMS = List.of(
            "refund", "refunds", "refunded", "cancel my booking", "cancellation", "lapse", "lapsed", "lapses",
            "revive", "booking terms", "terms of my booking", "my terms", "penalty", "get my money back");

    private static final List<String> MY_ACTIVITY = List.of(
            "my viewing", "my viewings", "my enquiry", "my enquiries", "my offer", "my offers",
            "my appointment", "my visit", "my visits", "have i", "did i");

    private static final List<String> VIEWING = List.of(
            "viewing", "view it", "view the", "visit", "site visit", "see it", "see the", "see that",
            "go and see", "look at it in person", "come and see");

    private static final List<String> UNITS = List.of(
            "units left", "homes left", "how many left", "how many are left", "still available", "availability",
            "sold out", "how many units");

    private static final List<String> RESTART = List.of(
            "new search", "start again", "start over", "anything", "forget that", "clear that", "something else");

    private static final List<String> GREETING = List.of(
            "hi", "hello", "hey", "habari", "jambo", "good morning", "good afternoon", "good evening");
    private static final List<String> THANKS = List.of("thanks", "thank you", "asante", "cheers");

    private static final List<String> SEARCH = List.of(
            "house", "houses", "home", "homes", "apartment", "apartments", "flat", "flats", "plot", "plots", "land",
            "townhouse", "maisonette", "bungalow", "villa", "property", "properties", "listing", "listings",
            "for sale", "to let", "rent", "rental", "buy", "looking for", "show me", "anything in", "commercial",
            "office", "godown", "shop", "cheaper", "bigger", "under", "below", "what about");

    /** The words people use for the property-type catalogue's codes. Most specific first. */
    private static final LinkedHashMap<String, String> TYPES = new LinkedHashMap<>();
    static {
        TYPES.put("townhouse", "TOWNHOUSE");
        TYPES.put("townhouses", "TOWNHOUSE");
        TYPES.put("maisonette", "MAISONETTE");
        TYPES.put("maisonettes", "MAISONETTE");
        TYPES.put("apartment", "APARTMENT");
        TYPES.put("apartments", "APARTMENT");
        TYPES.put("flat", "APARTMENT");
        TYPES.put("flats", "APARTMENT");
        TYPES.put("bungalow", "HOUSE");
        TYPES.put("villa", "HOUSE");
        TYPES.put("house", "HOUSE");
        TYPES.put("houses", "HOUSE");
        TYPES.put("plot", "LAND");
        TYPES.put("plots", "LAND");
        TYPES.put("land", "LAND");
        TYPES.put("office", "COMMERCIAL");
        TYPES.put("offices", "COMMERCIAL");
        TYPES.put("office space", "COMMERCIAL");
        TYPES.put("hall", "COMMERCIAL");
        TYPES.put("halls", "COMMERCIAL");
        TYPES.put("warehouse", "COMMERCIAL");
        TYPES.put("showroom", "COMMERCIAL");
        TYPES.put("retail", "COMMERCIAL");
        TYPES.put("shops", "COMMERCIAL");
        TYPES.put("shop", "COMMERCIAL");
        TYPES.put("godown", "COMMERCIAL");
        TYPES.put("commercial", "COMMERCIAL");
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final PublicPropertyService marketplace;
    private final PublicDevelopmentService developmentsPublic;
    private final DevelopmentRepository developments;
    private final AffordabilityService affordability;
    private final EnquiryTicketRepository enquiries;
    private final SiteVisitRepository visits;
    private final BookingService bookings;
    private final UnitBookingRepository bookingRows;
    private final BookingPolicyService policies;
    private final AssistantKnowledgeService knowledge;

    @Override
    public Answer answer(Question question) {
        String q = question.text() == null ? "" : question.text().trim().toLowerCase(Locale.ROOT);
        AssistantContext context = question.context() == null ? AssistantContext.empty() : question.context();
        if (q.isEmpty()) {
            return reply(INTENT_UNKNOWN, "Ask me about a property, what you could borrow, or anything you have "
                    + "already started here.", context);
        }

        // Order matters: the most specific reading first.
        if (Words.any(q, HANDOFF)) return handoff(question, context);
        if (Words.any(q, ALERT)) return alert(q, question, context);
        if (Words.any(q, AFFORD)) return afford(q, context);
        if (Words.any(q, MY_BOOKINGS)) return myBookings(question, context);
        if (Words.any(q, TERMS)) return terms(question, context);
        if (Words.any(q, MY_ACTIVITY)) return myActivity(question, context);
        if (Words.any(q, VIEWING)) return viewing(q, question, context);

        PublicDevelopmentResponse named = developmentNamedIn(q);
        if (named != null || (Words.any(q, UNITS) && lastDevelopment(context) != null)) {
            return unitsLeft(named, context);
        }

        var explained = knowledge.match(q);
        if (explained.isPresent()) return explain(explained.get(), context);

        if (looksLikeSearch(q)) return search(q, context);
        if (Words.any(q, THANKS)) {
            return reply(INTENT_GREETING, "You are welcome. Ask me anything else about a home, or what you could "
                    + "borrow.", context);
        }
        if (Words.any(q, GREETING)) {
            return reply(INTENT_GREETING, "Hello. Tell me what you are looking for, such as \"three bedrooms in "
                    + "Kilimani under 20m\", or ask what you could borrow on your monthly pay.", context);
        }
        return notUnderstood(question, context);
    }

    // ── searching ─────────────────────────────────────────────────────────────

    private Answer search(String q, AssistantContext context) {
        Filters asked = filtersIn(q);
        boolean restart = Words.any(q, RESTART);
        Filters filters = restart ? asked.after(null) : asked.after(context.filtersOrNone());
        // Said back in words, so a filter kept from before is never a silent surprise.
        String kept = restart ? "" : describeKept(asked.carriedInto(filters));
        String keptLine = kept.isEmpty() ? ""
                : "\n\nFrom before, I kept: " + kept + ". Say \"new search\" to drop it.";

        var request = new PropertyDtos.PublicSearchRequest();
        request.setSize(4);
        if (filters.bedrooms() != null) request.setMinBedrooms(filters.bedrooms().shortValue());
        if (filters.maxPrice() != null) request.setMaxPrice(filters.maxPrice());
        if (filters.town() != null) request.setSearch(filters.town());
        if (filters.listingType() != null) request.setListingType(filters.listingType());
        if (filters.propertyType() != null) request.setPropertyType(filters.propertyType());
        var results = marketplace.search(request);

        AssistantContext next = context.withFilters(filters).withIntent(INTENT_SEARCH);
        String described = describe(filters);
        if (results.getContent().isEmpty()) {
            return new Answer(INTENT_SEARCH,
                    "Nothing on the marketplace matches " + (described.isEmpty() ? "that" : described)
                            + " at the moment. Widening the area or the budget usually helps, or I can tell you "
                            + "when something comes up." + keptLine,
                    Map.of("matched", 0, "filters", filters),
                    List.of(new Link("Browse everything", "/")),
                    List.of(alertAction(filters)),
                    next.withShown(List.of()));
        }

        StringBuilder reply = new StringBuilder("Here is what matches");
        if (!described.isEmpty()) reply.append(' ').append(described);
        reply.append(": ").append(results.getTotalElements())
                .append(results.getTotalElements() == 1 ? " listing" : " listings").append(" in all.");
        List<Link> links = new ArrayList<>();
        List<Shown> shown = new ArrayList<>();
        int n = 0;
        for (var p : results.getContent()) {
            n++;
            reply.append("\n").append(n).append(". ").append(p.title()).append(", ").append(money(p.price()))
                    .append(", ").append(p.town() == null ? p.county() : p.town());
            links.add(new Link(p.title(), "/property/" + p.reference()));
            shown.add(new Shown("PROPERTY", p.reference(), p.title()));
        }
        reply.append(keptLine);
        reply.append("\n\nSay \"see the first one\" to arrange a viewing, or \"tell me when\" for new matches.");

        return new Answer(INTENT_SEARCH, reply.toString(),
                Map.of("matched", results.getTotalElements(), "filters", filters),
                links, List.of(), next.withShown(shown));
    }

    private Answer alert(String q, Question question, AssistantContext context) {
        Filters filters = filtersIn(q).after(Words.any(q, RESTART) ? null : context.filtersOrNone());
        if (filters.isEmpty()) {
            return reply(INTENT_ALERT, "Tell me what to watch for, such as \"tell me when a three-bed comes up in "
                    + "Karen under 25m\", and I will set it up.", context);
        }
        String described = describe(filters);
        List<Action> actions = new ArrayList<>();
        actions.add(alertAction(filters));
        String reply = "I can tell you when a listing " + described + " comes on the marketplace. "
                + (question.visitor()
                        ? "Alerts belong to an account, so sign in and then press Create alert."
                        : "Press Create alert and new matches will reach you by the channels you chose.");
        return new Answer(INTENT_ALERT, reply, Map.of("filters", filters), List.of(), actions,
                context.withFilters(filters).withIntent(INTENT_ALERT));
    }

    private static Action alertAction(Filters f) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", alertName(f));
        if (f.bedrooms() != null) data.put("minBedrooms", f.bedrooms());
        if (f.maxPrice() != null) data.put("maxPrice", f.maxPrice());
        if (f.town() != null) data.put("town", capitalise(f.town()));
        if (f.listingType() != null) data.put("listingType", f.listingType());
        if (f.propertyType() != null) data.put("propertyType", f.propertyType());
        return new Action(ACTION_ALERT, "Create alert", data);
    }

    // ── borrowing ─────────────────────────────────────────────────────────────

    /** The figure asked for is take-home, like everywhere else, and the answer says which figure it used. */
    private Answer afford(String q, AssistantContext context) {
        BigDecimal income = firstMoney(q);
        if (income == null) {
            return new Answer(INTENT_AFFORD,
                    "Tell me what you take home each month, after tax, and I will work out roughly what you could "
                            + "borrow. For example \"what can I afford on 180,000 a month\". The calculator asks a "
                            + "few more questions and gives a firmer figure.",
                    null, List.of(new Link("Open the calculator", "/affordability")), List.of(),
                    context.withIntent(INTENT_AFFORD));
        }
        var estimate = affordability.estimate(new FinanceDtos.AffordabilityRequest(
                income, null, null, null, null, null, null, null, null));
        boolean noDeposit = estimate.maxPropertyPrice() != null
                && estimate.maxPropertyPrice().compareTo(estimate.maxLoanAmount()) == 0;
        String line = "On " + money(income) + " a month, the bank would typically look at around "
                + money(estimate.maxLoanAmount()) + " of borrowing"
                + (noDeposit
                        ? ", so about that much property before any deposit of your own. Every shilling you put "
                          + "down buys more."
                        : ", roughly " + money(estimate.maxPropertyPrice()) + " of property once your deposit is added.")
                + " That is arithmetic, not an offer: only the bank can tell you what they will actually lend.";
        // What it buys becomes the budget in force, so "show me what that buys" is the next search.
        Filters budget = new Filters(null, estimate.maxPropertyPrice(), null, null, null).after(context.filtersOrNone());
        return new Answer(INTENT_AFFORD, line, Map.of("income", income),
                List.of(new Link("Work it out properly", "/affordability"),
                        new Link("What that buys", "/?maxPrice=" + estimate.maxPropertyPrice())),
                List.of(), context.withFilters(budget).withIntent(INTENT_AFFORD));
    }

    // ── their own things ──────────────────────────────────────────────────────

    private Answer myBookings(Question question, AssistantContext context) {
        if (question.visitor()) {
            return signIn(INTENT_MY_BOOKINGS, "Sign in and I can tell you where each of your bookings stands, what "
                    + "you have paid and when the next instalment is due.", context);
        }
        List<BookingResponse> mine = bookings.mine();
        if (mine.isEmpty()) {
            return new Answer(INTENT_MY_BOOKINGS, "You have no bookings yet. When you reserve a home, I can tell you "
                    + "what is owed and when.", null, List.of(new Link("Browse listings", "/")), List.of(),
                    context.withIntent(INTENT_MY_BOOKINGS));
        }
        StringBuilder reply = new StringBuilder("You have ")
                .append(mine.size()).append(mine.size() == 1 ? " booking." : " bookings.");
        List<Link> links = new ArrayList<>();
        for (BookingResponse b : mine.stream().limit(5).toList()) {
            reply.append("\n· ").append(b.developmentName() == null ? b.propertyTitle() : b.developmentName())
                    .append(b.unitLabel() == null ? "" : ", " + b.unitLabel())
                    .append(": ").append(stateSaid(b.state()));
            if (isLive(b.state())) {
                reply.append(". Paid ").append(money(b.paid(), b.currency()))
                        .append(" of ").append(money(b.scheduled(), b.currency()));
                if (b.overdue() != null && b.overdue().signum() > 0) {
                    reply.append(", ").append(money(b.overdue(), b.currency())).append(" overdue");
                }
                if (b.nextDueOn() != null) reply.append(". Next due ").append(DAY.format(b.nextDueOn()));
                if ("PRESENTED".equals(b.termsState())) reply.append(". Its terms are waiting for you to accept them");
            }
            reply.append('.');
            links.add(new Link(b.reference(), "/account/bookings/" + b.id()));
        }
        return new Answer(INTENT_MY_BOOKINGS, reply.toString(), Map.of("bookings", mine.size()), links, List.of(),
                context.withIntent(INTENT_MY_BOOKINGS));
    }

    /**
     * The refund and revival terms.
     *
     * <p>A signed-in buyer with bookings gets each booking's own development policy, which is what their terms
     * were filled from. Everybody else gets the platform's default, and is told a development may differ.
     */
    private Answer terms(Question question, AssistantContext context) {
        if (!question.visitor()) {
            List<UnitBooking> mine = bookingRows.findForBuyer(question.userId()).stream()
                    .filter(b -> isLive(b.getState()) || AppConstant.BOOKING_LAPSED.equals(b.getState()))
                    .limit(3).toList();
            if (!mine.isEmpty()) {
                StringBuilder reply = new StringBuilder();
                List<Link> links = new ArrayList<>();
                for (UnitBooking b : mine) {
                    var development = b.getDevelopmentId() == null ? null
                            : developments.findById(b.getDevelopmentId()).orElse(null);
                    var policy = policies.policyFor(development);
                    if (!reply.isEmpty()) reply.append("\n\n");
                    reply.append(development == null ? "Booking " + b.getReference() : development.getName())
                            .append(" (").append(stateSaid(b.getState()).toLowerCase(Locale.ROOT)).append("). ")
                            .append(policy.penaltySaid(b.getCurrency() == null ? "KES" : b.getCurrency()))
                            .append(' ').append(windows(policy));
                    links.add(new Link("Terms of " + b.getReference(),
                            "/account/bookings/" + com.hodi.security.hashid.HashIdUtil.encodeId(b.getId())));
                }
                reply.append("\n\nThese are the terms each booking was made under. The full wording is on the "
                        + "booking.");
                return new Answer(INTENT_TERMS, reply.toString(), null, links, List.of(), context.withIntent(INTENT_TERMS));
            }
        }
        var policy = policies.policyFor(null);
        String reply = "Unless a development sets its own, this is the rule: " + policy.penaltySaid("KES") + " "
                + windows(policy) + " Whatever applies to a home is shown to you before you pay, and you accept "
                + "it or decline it first.";
        if (question.visitor()) {
            return new Answer(INTENT_TERMS, reply + " Sign in and I can tell you the terms of your own booking.",
                    null, List.of(), List.of(signInAction()), context.withIntent(INTENT_TERMS));
        }
        return reply(INTENT_TERMS, reply, context);
    }

    private static String windows(BookingPolicyService.BookingPolicy p) {
        String refund = p.refundWithinDays() > 0 ? "within " + p.refundWithinDays() + " days" : "at any time";
        String revive = p.reviveWithinDays() > 0 ? "within " + p.reviveWithinDays() + " days" : "at any time";
        return "A refund may be asked for " + refund + ", and a lapsed booking may be revived " + revive + ".";
    }

    private Answer myActivity(Question question, AssistantContext context) {
        if (question.visitor()) {
            return signIn(INTENT_MY_ACTIVITY, "Sign in and I can tell you where your enquiries and viewings stand.",
                    context);
        }
        long openEnquiries = enquiries.countOpenForUser(question.userId());
        long upcomingVisits = visits.countUpcomingForUser(question.userId(), OffsetDateTime.now());
        String reply = openEnquiries == 0 && upcomingVisits == 0
                ? "You have nothing open at the moment: no unanswered questions and no viewings booked."
                : "You have " + phrase(openEnquiries, "open enquiry", "open enquiries")
                        + " and " + phrase(upcomingVisits, "viewing booked", "viewings booked") + ".";
        return new Answer(INTENT_MY_ACTIVITY, reply, null,
                List.of(new Link("Your conversations", "/account/conversations")), List.of(),
                context.withIntent(INTENT_MY_ACTIVITY));
    }

    // ── a viewing ─────────────────────────────────────────────────────────────

    /**
     * Which home, then a card to ask for it.
     *
     * <p>The card carries the listings to choose from and opens the same request the listing page sends. A
     * visitor gets the same card, which asks them to sign in when they press it.
     */
    private Answer viewing(String q, Question question, AssistantContext context) {
        List<Shown> candidates = context.shownOrNone().stream().filter(s -> "PROPERTY".equals(s.kind())).toList();
        Shown chosen = pick(q, question.text(), candidates);
        List<Shown> offered = chosen != null ? List.of(chosen) : candidates;
        if (offered.isEmpty()) {
            return reply(INTENT_VIEWING, "Which home would you like to see? Search first, such as \"two bedrooms in "
                    + "Westlands\", and then say \"see the first one\". Every listing page also has Arrange a viewing.",
                    context);
        }
        String reply = chosen != null
                ? "Pick a time to see " + chosen.title() + ". The seller confirms it or offers another."
                : "Which one would you like to see? Pick it below with a time, and the seller confirms or offers another.";
        if (question.visitor()) reply += " You will be asked to sign in when you send it.";
        return new Answer(INTENT_VIEWING, reply, null, List.of(),
                List.of(new Action(ACTION_VIEWING, "Arrange a viewing", Map.of("listings", offered))),
                context.withIntent(INTENT_VIEWING));
    }

    /** "the second one", "number 2", a reference typed in, a title named, or the only one shown. */
    private static Shown pick(String q, String original, List<Shown> candidates) {
        Matcher ref = REFERENCE.matcher(original == null ? "" : original.toUpperCase(Locale.ROOT));
        if (ref.find()) {
            String r = ref.group(1);
            Optional<Shown> hit = candidates.stream().filter(s -> s.reference().equals(r)).findFirst();
            return hit.orElse(new Shown("PROPERTY", r, r));
        }
        if (candidates.isEmpty()) return null;
        Matcher m = ORDINAL.matcher(q);
        if (m.find()) {
            int n = m.group(1) != null ? ORDINALS.get(m.group(1).toLowerCase(Locale.ROOT)) : Integer.parseInt(m.group(2));
            return n <= candidates.size() ? candidates.get(n - 1) : null;
        }
        for (Shown s : candidates) {
            if (s.title() != null && q.contains(s.title().toLowerCase(Locale.ROOT))) return s;
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    // ── a development ─────────────────────────────────────────────────────────

    private Answer unitsLeft(PublicDevelopmentResponse named, AssistantContext context) {
        String reference = named != null ? named.reference() : lastDevelopment(context).reference();
        PublicDevelopmentResponse d = named != null ? named : developmentsPublic.find(reference);
        List<PublicUnitTypeResponse> types = developmentsPublic.availability(reference);
        StringBuilder reply = new StringBuilder(d.name());
        if (d.unitsTotal() == 0) {
            reply.append(" has no homes released yet.");
        } else if (d.unitsAvailable() == 0) {
            reply.append(" is sold out: all ").append(d.unitsTotal()).append(" homes are taken.");
        } else {
            reply.append(" has ").append(d.unitsAvailable()).append(" of ").append(d.unitsTotal())
                    .append(" homes available");
            reply.append(types.isEmpty() ? "." : ":");
            for (PublicUnitTypeResponse t : types) {
                reply.append("\n· ").append(t.name());
                String name = t.name() == null ? "" : t.name().toLowerCase(Locale.ROOT);
                // "Two bedroom, 2 bed" says it twice; add the count only when the name does not.
                if (t.bedrooms() != null && !name.contains("bed") && !name.contains("studio")) {
                    reply.append(t.bedrooms() == 0 ? ", studio" : ", " + t.bedrooms() + " bed");
                }
                reply.append(": ").append(t.unitsAvailable() == 0 ? "none left" : t.unitsAvailable() + " left");
                if (t.unitsAvailable() > 0 && t.fromPrice() != null) {
                    reply.append(", from ").append(money(t.fromPrice(), t.currency()));
                }
            }
        }
        List<Shown> shown = List.of(new Shown("DEVELOPMENT", d.reference(), d.name()));
        return new Answer(INTENT_UNITS_LEFT, reply.toString(), Map.of("development", d.reference()),
                List.of(new Link("Open " + d.name(), "/development/" + d.reference())), List.of(),
                context.withShown(shown).withIntent(INTENT_UNITS_LEFT));
    }

    /** A live development whose name is in the question, longest name first. */
    private PublicDevelopmentResponse developmentNamedIn(String q) {
        try {
            var request = new PublicDevelopmentService.PublicDevelopmentSearchRequest();
            request.setSize(100);
            return developmentsPublic.search(request).getContent().stream()
                    .filter(d -> d.name() != null && d.name().length() >= 4)
                    .filter(d -> Words.has(q, d.name().toLowerCase(Locale.ROOT)))
                    .max(Comparator.comparingInt(d -> d.name().length()))
                    .orElse(null);
        } catch (RuntimeException e) {
            log.debug("Could not read the developments: {}", e.getMessage());
            return null;
        }
    }

    private static Shown lastDevelopment(AssistantContext context) {
        return context.shownOrNone().stream().filter(s -> "DEVELOPMENT".equals(s.kind())).findFirst().orElse(null);
    }

    // ── explaining, handing off, not knowing ──────────────────────────────────

    private Answer explain(AssistantKnowledge k, AssistantContext context) {
        List<Link> links = k.getLinkTo() == null ? List.of() : List.of(new Link(k.getLinkLabel(), k.getLinkTo()));
        return new Answer(INTENT_EXPLAIN, k.getAnswer(), Map.of("knowledge", k.getReference()), links, List.of(),
                context.withIntent(INTENT_EXPLAIN));
    }

    /**
     * Offers the hand-off as a card: one button per listing in view, and one for "something else".
     *
     * <p>Whether "something else" is offered, and who it reaches, is the platform's setting; the service
     * enforces it again when the card is pressed. A visitor is asked to sign in first, because the person
     * replying needs somebody to reply to.
     */
    private Answer handoff(Question question, AssistantContext context) {
        List<Shown> listings = context.shownOrNone().stream().filter(s -> "PROPERTY".equals(s.kind())).toList();
        String reply = listings.isEmpty()
                ? "Of course. I will pass this whole conversation on, so you do not have to say it twice. If it is "
                  + "about a particular home, open it and ask from there, or tell me which and I will send it to its seller."
                : "Of course. Is it about one of the homes I showed you, or something else? I will pass this whole "
                  + "conversation on, so you do not have to say it twice.";
        // The card says a visitor signs in first; saying it here as well said it twice.
        return new Answer(INTENT_HANDOFF, reply, null, List.of(),
                List.of(new Action(ACTION_HANDOFF, "Pass this to a person", Map.of("listings", listings))),
                context.withIntent(INTENT_HANDOFF));
    }

    private Answer notUnderstood(Question question, AssistantContext context) {
        return new Answer(INTENT_UNKNOWN,
                "I did not follow that, and I would rather say so than guess. I can search the listings (\"three "
                        + "bedrooms in Kilimani under 20m\"), say how many homes are left in a development, work out "
                        + "roughly what you could borrow, explain the words that come up when you are buying, and "
                        + (question.visitor() ? "once you sign in, tell you about your own bookings and viewings."
                                : "tell you about your own bookings, enquiries and viewings.")
                        + "\n\nOr say \"talk to a person\" and I will pass this on.",
                null, List.of(new Link("Browse listings", "/"), new Link("What can I afford?", "/affordability")),
                List.of(), context.withIntent(INTENT_UNKNOWN));
    }

    private Answer signIn(String intent, String reply, AssistantContext context) {
        return new Answer(intent, reply, null, List.of(), List.of(signInAction()), context.withIntent(intent));
    }

    private static Action signInAction() {
        return new Action(ACTION_SIGN_IN, "Sign in", Map.of());
    }

    private static Answer reply(String intent, String text, AssistantContext context) {
        return new Answer(intent, text, null, List.of(), List.of(), context.withIntent(intent));
    }

    // ── reading the question ──────────────────────────────────────────────────

    private boolean looksLikeSearch(String q) {
        return !filtersIn(q).isEmpty() || Words.any(q, SEARCH);
    }

    Filters filtersIn(String q) {
        String listingType = Words.any(q, List.of("rent", "rental", "to let", "for rent", "lease a"))
                ? "RENT" : Words.any(q, List.of("buy", "for sale", "to buy", "purchase")) ? "SALE" : null;
        String type = null;
        for (var e : TYPES.entrySet()) {
            if (Words.has(q, e.getKey())) { type = e.getValue(); break; }
        }
        return new Filters(firstBedrooms(q), firstMoney(q), firstTown(q), listingType, type);
    }

    /** The first figure, with k/m suffixes understood. Under a thousand is a bedroom count or a typo. */
    static BigDecimal firstMoney(String q) {
        Matcher m = MONEY.matcher(q);
        while (m.find()) {
            String digits = m.group(1).replace(",", "");
            if (digits.isEmpty() || digits.equals(".")) continue;
            BigDecimal value;
            try {
                value = new BigDecimal(digits);
            } catch (NumberFormatException e) {
                continue;
            }
            String suffix = m.group(2) == null ? "" : m.group(2).toLowerCase(Locale.ROOT);
            if (suffix.startsWith("m")) value = value.multiply(BigDecimal.valueOf(1_000_000));
            else if (suffix.startsWith("k") || suffix.startsWith("thousand")) value = value.multiply(BigDecimal.valueOf(1_000));
            if (value.compareTo(BigDecimal.valueOf(1_000)) < 0) continue;
            return value;
        }
        return null;
    }

    static Integer firstBedrooms(String q) {
        Matcher m = BEDROOMS.matcher(q);
        if (!m.find()) return null;
        String token = m.group(1).toLowerCase(Locale.ROOT);
        return WRITTEN.containsKey(token) ? WRITTEN.get(token) : Integer.parseInt(token);
    }

    /** A town the marketplace has listings in, read from the live facets rather than a list in code. */
    private String firstTown(String q) {
        try {
            return marketplace.facets().towns().stream()
                    .filter(t -> t != null && !t.isBlank())
                    .filter(t -> Words.has(q, t.toLowerCase(Locale.ROOT)))
                    .max(Comparator.comparingInt(String::length))
                    // As the marketplace spells it: "Upper Hill", not "Upper hill".
                    .map(String::trim)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.debug("Could not read the town facets: {}", e.getMessage());
            return null;
        }
    }

    // ── saying it ─────────────────────────────────────────────────────────────

    /** "3 bedrooms or more, apartments to rent, up to KES 20,000,000, around Kilimani". */
    static String describe(Filters f) {
        List<String> parts = new ArrayList<>();
        if (f.bedrooms() != null) parts.add(f.bedrooms() + (f.bedrooms() == 1 ? " bedroom" : " bedrooms") + " or more");
        if (f.propertyType() != null || f.listingType() != null) {
            String kind = f.propertyType() == null ? "homes" : plural(f.propertyType());
            if ("RENT".equals(f.listingType())) kind += " to rent";
            else if ("SALE".equals(f.listingType())) kind += " for sale";
            parts.add(kind);
        }
        if (f.maxPrice() != null) parts.add("up to " + money(f.maxPrice()));
        if (f.town() != null) parts.add("around " + capitalise(f.town()));
        return String.join(", ", parts);
    }

    /** Each kept filter on its own: "to rent, around Upper Hill", never a sentence implying what was not kept. */
    static String describeKept(Filters f) {
        List<String> parts = new ArrayList<>();
        if (f.bedrooms() != null) parts.add(f.bedrooms() + (f.bedrooms() == 1 ? " bedroom" : " bedrooms") + " or more");
        if (f.propertyType() != null) parts.add(plural(f.propertyType()));
        if ("RENT".equals(f.listingType())) parts.add("to rent");
        else if ("SALE".equals(f.listingType())) parts.add("to buy");
        if (f.maxPrice() != null) parts.add("up to " + money(f.maxPrice()));
        if (f.town() != null) parts.add("around " + capitalise(f.town()));
        return String.join(", ", parts);
    }

    private static String alertName(Filters f) {
        String d = describe(f);
        String name = d.isEmpty() ? "New listings" : capitalise(d);
        return name.length() <= 120 ? name : name.substring(0, 117) + "…";
    }

    private static String plural(String typeCode) {
        return switch (typeCode) {
            case "APARTMENT" -> "apartments";
            case "TOWNHOUSE" -> "townhouses";
            case "MAISONETTE" -> "maisonettes";
            case "LAND" -> "plots";
            case "COMMERCIAL" -> "commercial property";
            default -> "houses";
        };
    }

    private static boolean isLive(String state) {
        return AppConstant.BOOKING_RESERVED.equals(state) || AppConstant.BOOKING_AGREED.equals(state);
    }

    private static String stateSaid(String state) {
        if (state == null) return "Unknown";
        return switch (state) {
            case AppConstant.BOOKING_RESERVED -> "Reserved";
            case AppConstant.BOOKING_AGREED -> "Agreed";
            case AppConstant.BOOKING_COMPLETED -> "Completed";
            case AppConstant.BOOKING_CANCELLED -> "Cancelled";
            case AppConstant.BOOKING_LAPSED -> "Lapsed";
            default -> capitalise(state.toLowerCase(Locale.ROOT));
        };
    }

    private static String phrase(long count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }

    private static String money(BigDecimal value) {
        return money(value, "KES");
    }

    /** "KES 1,250,000": the code the rows hold and the client renders, never a symbol. */
    private static String money(BigDecimal value, String currency) {
        if (value == null) return "nothing";
        return (currency == null ? "KES" : currency) + " "
                + java.text.NumberFormat.getIntegerInstance(Locale.UK).format(value);
    }

    private static String capitalise(String value) {
        return value == null || value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
