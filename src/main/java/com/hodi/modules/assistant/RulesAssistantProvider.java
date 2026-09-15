package com.hodi.modules.assistant;

import com.hodi.modules.finance.AffordabilityService;
import com.hodi.modules.leads.EnquiryTicketRepository;
import com.hodi.modules.leads.SiteVisitRepository;
import com.hodi.modules.properties.PropertyDtos;
import com.hodi.modules.properties.PublicPropertyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The assistant, as rules over the platform's own capabilities (M11).
 *
 * <h2>Why rules and not a model</h2>
 *
 * <p>There is no language model wired to this deployment, and a convincing imitation of one would be worse
 * than none: somebody about to spend everything they have on a house should not be guessing whether they are
 * talking to a person, a model, or a lookup table. So this answers from real data, says plainly what it is,
 * and hands over to a person the moment it is out of its depth.
 *
 * <p>Everything it can do is something the platform already does. Search is {@code PublicPropertyService};
 * affordability is the same arithmetic the public calculator runs; "where is my viewing" reads the caller's
 * own rows. Nothing here is a second implementation of anything.
 *
 * <h2>Reading a question</h2>
 *
 * <p>Keywords and two number patterns, in a deliberate order — the most specific reading first, so "what can
 * I afford on 200k" is affordability rather than a search for something costing 200,000. It is not clever,
 * and it does not pretend to be: what it does is say which of five things it thinks was asked, and show its
 * working.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RulesAssistantProvider implements AssistantProvider {

    /** "20m", "20 million", "1.5m", "850k", "20,000,000". */
    private static final Pattern MONEY = Pattern.compile(
            "(\\d[\\d,.]*)\\s*(m\\b|million|k\\b|thousand)?", Pattern.CASE_INSENSITIVE);
    /**
     * "3 bed", "3-bedroom", "three bedroom".
     *
     * <p>Written numbers as well as digits, because that is how people type it — the first version matched
     * only digits and read "three bedroom in Kilimani" as no bedroom filter at all, then cheerfully offered
     * a one-bedroom flat.
     */
    private static final Pattern BEDROOMS = Pattern.compile(
            "(\\d|one|two|three|four|five|six|seven)\\s*(?:-|\\s)?\\s*(?:bed|bedroom|br\\b)",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, Integer> WRITTEN = Map.of(
            "one", 1, "two", 2, "three", 3, "four", 4, "five", 5, "six", 6, "seven", 7);

    private static final List<String> AFFORD_WORDS = List.of(
            "afford", "borrow", "mortgage on", "repayment", "how much can i", "salary", "income");
    private static final List<String> HANDOFF_WORDS = List.of(
            "human", "person", "someone", "somebody", "agent", "call me", "speak to", "talk to");
    /** Enough to say "this is about finding somewhere to live" without pretending to understand it. */
    private static final List<String> PROPERTY_WORDS = List.of(
            "house", "houses", "apartment", "flat", "plot", "land", "townhouse", "maisonette",
            "bungalow", "villa", "property", "listing", "listings", "for sale", "rent", "buy",
            "looking for", "show me", "anything in", "commercial", "office", "godown");

    private static final List<String> MINE_WORDS = List.of(
            "my viewing", "my viewings", "my enquiry", "my enquiries", "my offer", "my offers",
            "my appointment", "have i", "did i");

    /** The glossary. Short answers to the questions a first-time buyer actually asks. */
    private static final Map<String, String> GLOSSARY = Map.ofEntries(
            Map.entry("guide price",
                    "A guide price is an indication of what an auction lot might fetch — not an asking "
                            + "price. Lots often sell for more. Every lot also has a reserve the seller will "
                            + "not go below, and that figure is never published."),
            Map.entry("reserve",
                    "The reserve is the lowest price a seller will accept at auction. It is not published, "
                            + "and it is at or above the guide price."),
            Map.entry("deposit",
                    "Two different things share the word. At auction, a deposit is what you must lodge "
                            + "before you are allowed to bid. On a mortgage, it is the part of the price you "
                            + "pay yourself — the rest is the loan."),
            Map.entry("ltv",
                    "Loan to value: the share of the price the bank will lend. At 80% LTV on a 10 million "
                            + "house, the bank puts up 8 million and you find 2 million."),
            Map.entry("dti",
                    "Debt to income: the share of your monthly income the bank will let go on repayments, "
                            + "usually somewhere between a third and a half."),
            Map.entry("freehold",
                    "Freehold means you own the land outright and for good. Leasehold means you hold it for "
                            + "a fixed term — in Kenya usually 99 years — after which it reverts."),
            Map.entry("leasehold",
                    "Leasehold means you hold the land for a fixed term, in Kenya usually 99 years. What "
                            + "matters most is how many years are left on it."),
            Map.entry("conveyancing",
                    "Conveyancing is the legal work of transferring the title: the searches, the agreement, "
                            + "stamp duty and registration. You will want an advocate — there are some in "
                            + "the services directory."),
            Map.entry("stamp duty",
                    "Stamp duty is the tax on transferring the title, paid by the buyer. Budget for it "
                            + "separately from the deposit — it is not part of the loan."),
            Map.entry("service charge",
                    "A service charge is the monthly amount an apartment or gated development charges for "
                            + "shared costs — security, water, grounds, lifts. It is not part of the "
                            + "mortgage and it does not stop."));

    private final PublicPropertyService marketplace;
    private final AffordabilityService affordability;
    private final EnquiryTicketRepository enquiries;
    private final SiteVisitRepository visits;

    @Override
    public Answer answer(String question, Long userId) {
        String q = question == null ? "" : question.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) {
            return new Answer(AssistantConstants.INTENT_UNKNOWN,
                    "Ask me something about a property, what you can borrow, or anything you have already "
                            + "started here.", null, List.of());
        }

        // Order matters: the most specific reading first. "What can I afford on 200k" is a question about
        // borrowing, not a search for something costing two hundred thousand.
        if (containsAny(q, HANDOFF_WORDS)) return handoffOffer();
        if (containsAny(q, AFFORD_WORDS)) return afford(q);
        if (containsAny(q, MINE_WORDS)) return mine(userId);

        String term = glossaryHit(q);
        if (term != null) {
            return new Answer(AssistantConstants.INTENT_EXPLAIN, GLOSSARY.get(term), null,
                    List.of(new Link("Browse listings", "/")));
        }

        /*
         * Only a search if it is one.
         *
         * The first version treated everything it did not recognise as a search — so "do you sell cars"
         * came back with thirteen houses and no hint that the question had not been understood. A confident
         * wrong answer is worse than an admission, particularly from something a buyer cannot argue with.
         */
        if (looksLikeSearch(q)) return search(q);
        return notUnderstood();
    }

    // ── the five things it can do ─────────────────────────────────────────────

    private Answer search(String q) {
        var request = new PropertyDtos.PublicSearchRequest();
        request.setSize(4);
        Integer bedrooms = firstBedrooms(q);
        if (bedrooms != null) request.setMinBedrooms(bedrooms.shortValue());
        BigDecimal budget = firstMoney(q);
        if (budget != null) request.setMaxPrice(budget);
        String town = firstTown(q);
        if (town != null) request.setSearch(town);

        var results = marketplace.search(request);
        if (results.getContent().isEmpty()) {
            return new Answer(AssistantConstants.INTENT_SEARCH,
                    "Nothing on the marketplace matches that at the moment. Widening the area or the budget "
                            + "usually helps — or I can put you in touch with somebody.",
                    null, List.of(new Link("Browse everything", "/")));
        }

        StringBuilder reply = new StringBuilder("Here is what matches");
        if (bedrooms != null) reply.append(", ").append(bedrooms).append(" bedrooms or more");
        if (budget != null) reply.append(", up to ").append(money(budget));
        if (town != null) reply.append(", around ").append(capitalise(town));
        reply.append(" — ").append(results.getTotalElements())
                .append(results.getTotalElements() == 1 ? " listing" : " listings")
                .append(" in all:");

        List<Link> links = new ArrayList<>();
        for (var p : results.getContent()) {
            reply.append("\n· ").append(p.title()).append(" — ").append(money(p.price()))
                    .append(" in ").append(p.town() == null ? p.county() : p.town());
            links.add(new Link(p.title(), "/property/" + p.reference()));
        }
        return new Answer(AssistantConstants.INTENT_SEARCH, reply.toString(),
                "{\"matched\":" + results.getTotalElements() + "}", links);
    }

    private Answer afford(String q) {
        BigDecimal income = firstMoney(q);
        if (income == null) {
            return new Answer(AssistantConstants.INTENT_AFFORD,
                    "Tell me your monthly income and I will work out roughly what you could borrow — for "
                            + "example \"what can I afford on 180,000 a month\". The calculator asks a few "
                            + "more questions and gives a firmer figure.",
                    null, List.of(new Link("Open the calculator", "/affordability")));
        }
        var estimate = affordability.estimate(new com.hodi.modules.finance.FinanceDtos
                .AffordabilityRequest(income, null, null, null, null, null, null, null));
        /*
         * Said as it is.
         *
         * With no deposit supplied, the most the bank would advance *is* the most property it buys — and
         * the first version still said "once a deposit is added", which was a sentence claiming a figure
         * that had not been given. Anything about somebody's money should be exactly true or not said.
         */
        boolean noDeposit = estimate.maxPropertyPrice() != null
                && estimate.maxPropertyPrice().compareTo(estimate.maxLoanAmount()) == 0;
        String line = "On " + money(income) + " a month, the bank would typically look at around "
                + money(estimate.maxLoanAmount()) + " of borrowing"
                + (noDeposit
                        ? " — so about that much property, before any deposit of your own. Every shilling "
                          + "you put down buys more."
                        : " — roughly " + money(estimate.maxPropertyPrice())
                          + " of property once your deposit is added.")
                + " That is arithmetic, not an offer: only the bank can tell you what they will actually "
                + "lend.";
        return new Answer(AssistantConstants.INTENT_AFFORD, line,
                null,
                List.of(new Link("Work it out properly", "/affordability"),
                        new Link("What that buys", "/?maxPrice=" + estimate.maxPropertyPrice())));
    }

    private Answer mine(Long userId) {
        long openEnquiries = enquiries.countOpenForUser(userId);
        long upcomingVisits = visits.countUpcomingForUser(userId, java.time.OffsetDateTime.now());
        String reply;
        if (openEnquiries == 0 && upcomingVisits == 0) {
            reply = "You have nothing open at the moment — no unanswered questions and no viewings booked.";
        } else {
            reply = "You have " + phrase(openEnquiries, "open enquiry", "open enquiries")
                    + " and " + phrase(upcomingVisits, "viewing booked", "viewings booked") + ".";
        }
        return new Answer(AssistantConstants.INTENT_MY_ACTIVITY, reply, null,
                List.of(new Link("Your conversations", "/account/conversations")));
    }

    /**
     * Whether the question is about finding a property at all.
     *
     * <p>Something extractable — a bedroom count, a budget, a town the marketplace knows — or a word about
     * property. Anything else gets an honest answer instead of a confident one.
     */
    private boolean looksLikeSearch(String q) {
        if (firstBedrooms(q) != null || firstMoney(q) != null || firstTown(q) != null) return true;
        return containsAny(q, PROPERTY_WORDS);
    }

    private Answer notUnderstood() {
        return new Answer(AssistantConstants.INTENT_UNKNOWN,
                "I did not follow that, and I would rather say so than guess. I can search the listings "
                        + "(\"three bedrooms in Kilimani under 20m\"), work out roughly what you could "
                        + "borrow (\"what can I afford on 180,000 a month\"), explain the words that come "
                        + "up when you are buying, or tell you where your own enquiries and viewings stand.\n\n"
                        + "Or say \"talk to a person\" and I will pass this on.",
                null, List.of(new Link("Browse listings", "/"),
                        new Link("What can I afford?", "/affordability")));
    }

    private Answer handoffOffer() {
        return new Answer(AssistantConstants.INTENT_HANDOFF,
                "Of course. Tell me which listing it is about and I will pass this whole conversation to "
                        + "the seller's team, so you do not have to say it twice.",
                null, List.of());
    }

    // ── reading the question ──────────────────────────────────────────────────

    private static boolean containsAny(String haystack, List<String> needles) {
        return needles.stream().anyMatch(haystack::contains);
    }

    private String glossaryHit(String q) {
        return GLOSSARY.keySet().stream().filter(q::contains).findFirst().orElse(null);
    }

    /** The first figure, with k/m suffixes understood. */
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
            else if (suffix.startsWith("k") || suffix.startsWith("thousand")) {
                value = value.multiply(BigDecimal.valueOf(1_000));
            }
            // A bare one-digit number is a bedroom count or a typo, not a price.
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

    /**
     * A place name, if one of the ones the marketplace knows about is in the question.
     *
     * <p>Read from the live facets rather than a hardcoded list, so a town nobody has listed in yet is not
     * a town the assistant claims to know.
     */
    private String firstTown(String q) {
        try {
            return marketplace.facets().towns().stream()
                    .filter(t -> t != null && !t.isBlank())
                    .filter(t -> q.contains(t.toLowerCase(Locale.ROOT)))
                    .findFirst()
                    .orElse(null);
        } catch (RuntimeException e) {
            log.debug("Could not read the town facets: {}", e.getMessage());
            return null;
        }
    }

    private static String phrase(long count, String one, String many) {
        return count + " " + (count == 1 ? one : many);
    }

    private static String money(BigDecimal value) {
        if (value == null) return "—";
        // "KES", not the "Ksh" symbol — the same code the rows hold and the client renders.
        return "KES " + java.text.NumberFormat.getIntegerInstance(Locale.UK).format(value);
    }

    private static String capitalise(String value) {
        return value.isEmpty() ? value
                : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
