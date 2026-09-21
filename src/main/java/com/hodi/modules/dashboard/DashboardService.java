package com.hodi.modules.dashboard;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.analytics.AnalyticsQueries;
import com.hodi.modules.analytics.AnalyticsService;
import com.hodi.modules.analytics.AnalyticsViews.Attention;
import com.hodi.modules.analytics.AnalyticsViews.CalendarView;
import com.hodi.modules.analytics.AnalyticsViews.Figure;
import com.hodi.modules.analytics.AnalyticsViews.Funnel;
import com.hodi.modules.analytics.AnalyticsViews.MoneyTotals;
import com.hodi.modules.analytics.AnalyticsViews.MonthFigures;
import com.hodi.modules.analytics.AnalyticsViews.MonthlyView;
import com.hodi.modules.analytics.AnalyticsViews.PipelineStats;
import com.hodi.modules.analytics.AnalyticsViews.TodayView;
import com.hodi.modules.analytics.AnalyticsViews.OverallView;
import com.hodi.modules.analytics.AnalyticsViews.Positions;
import com.hodi.modules.analytics.AnalyticsWindow;
import com.hodi.modules.auth.RefreshTokenRepository;
import org.springframework.http.HttpStatus;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import com.hodi.modules.banks.BankRepository;
import com.hodi.modules.tenants.TenantRepository;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.DecimalFormat;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One dashboard endpoint, tailored by who is asking.
 *
 * <p><strong>Assembled server-side, never filtered client-side.</strong> The response contains only the cards
 * the caller is entitled to, so a card added later cannot leak to an actor who should not see it, and a
 * hidden card is not a figure sitting in the JSON waiting to be read out of dev tools.
 *
 * <h2>Two kinds of thing on it</h2>
 *
 * <p>The cards are the facts that need saying in words — a stranded bank, an unconfirmed buyer, projects
 * running late — and they are assembled per audience here. The figures — overall, the month, the calendar —
 * are each their own endpoint, because they filter independently: Overall by year or not at all, the summary
 * by month, the calendar by year. One combined answer would mean stepping the calendar re-read the month.
 * Only the development is shared, and it genuinely narrows all three.
 *
 * <p>Nothing is stored or cached. Every figure is a sum over bookings, payments, the cost ledger and the units
 * when the page asks, through the same {@code AnalyticsQueries} the analytics page reads.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TenantRepository tenants;
    private final BankRepository institutions;
    private final UserProfileRepository profiles;
    private final RefreshTokenRepository refreshTokens;
    private final AnalyticsQueries figures;
    private final AnalyticsService analytics;
    private final DashboardAttentionQueries attention;

    /** The collections table's page. Ten, like every list in the platform. */
    private static final int COLLECTIONS = 10;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    /**
     * One figure on the dashboard.
     *
     * @param tone a hint for the UI — {@code neutral}, {@code positive}, {@code warning} — so the colour of a
     *             card is decided once, here, rather than by each client re-deriving it from the number
     * @param action a route the card links to, or null. The card that says "3 waiting for approval" is only
     *               useful if it goes somewhere.
     */
    public record Card(String key, String label, String value, String hint, String tone, String action) {}

    /**
     * @param audience which dashboard this is, so the client picks a layout without re-deriving it from the
     *                 user type
     */
    public record DashboardResponse(String audience, String greeting, List<Card> cards) {}

    @Transactional(readOnly = true)
    public DashboardResponse build() {
        UserPrincipal caller = AuthContext.require();
        String greeting = "Welcome back, " + firstNameOf(caller);

        if (caller.isPlatformStaff()) {
            return new DashboardResponse("PLATFORM", greeting, withProjectCards(platformCards()));
        }
        if (caller.isSellerStaff()) {
            return new DashboardResponse("SELLER", greeting, withProjectCards(sellerCards(caller)));
        }
        /*
         * No branch for the bank. Its staff are platform actors now, so they take the PLATFORM arm above — which
         * is the right dashboard for somebody who runs the place rather than one who was let into it.
         */
        return new DashboardResponse("BUYER", greeting, buyerCards(caller));
    }

    // ── today ─────────────────────────────────────────────────────────────────

    private static final DecimalFormat KES = new DecimalFormat("#,##0");
    /** How far ahead a hold is "about to lapse": the working week somebody has to chase the buyer in. */
    private static final int LAPSING_DAYS = 7;

    /**
     * The dashboard as one read.
     *
     * <p>The month against the month before, where things stand today, what is waiting for this person, the
     * year's shape, the latest money in, the stock, and the month's funnel. One call, because a landing page
     * that fires eight is a landing page that reflows eight times; each part is small.
     */
    @Transactional(readOnly = true)
    public TodayView today(String developmentHash) {
        UserPrincipal caller = AuthContext.require();
        Long developmentId = analytics.development(developmentHash);
        YearMonth thisMonth = YearMonth.now();
        AnalyticsWindow month = new AnalyticsWindow(thisMonth.getYear(), thisMonth.getMonthValue(),
                thisMonth.getYear(), thisMonth.getMonthValue());
        AnalyticsWindow year = AnalyticsWindow.of(null, null, null, null);

        MoneyTotals now = figures.totals(month, developmentId);
        MoneyTotals then = figures.totals(month.previous(), developmentId);
        MonthFigures figuresOfTheMonth = new MonthFigures(
                MONTH.format(thisMonth), MONTH.format(thisMonth.minusMonths(1)),
                Figure.of(now.collected(), then.collected()), Figure.of(now.contracted(), then.contracted()),
                Figure.of(now.spent(), then.spent()), Figure.of(now.drawn(), then.drawn()),
                now.payments(), now.bookings(), now.unitsSold());

        String audience = caller.isPlatformStaff() ? "PLATFORM" : caller.isSellerStaff() ? "SELLER" : "BUYER";
        PipelineStats funnel = figures.pipeline(month);
        return new TodayView(audience, greetingFor(caller), figuresOfTheMonth, figures.positions(developmentId),
                attentionFor(caller, developmentId), figures.trend(year, developmentId),
                attention.recentReceipts(6, developmentId), figures.unitsByState(developmentId),
                new Funnel(funnel.enquiries(), funnel.visits(), funnel.offers(), now.bookings()));
    }

    /** "Good morning" by the server's clock: the person and the server are in the same country. */
    private static String greetingFor(UserPrincipal caller) {
        int hour = OffsetDateTime.now(java.time.ZoneId.of("Africa/Nairobi")).getHour();
        String part = hour < 12 ? "Good morning" : hour < 17 ? "Good afternoon" : "Good evening";
        return part + ", " + firstNameOf(caller) + ".";
    }

    /**
     * What is waiting for this person, in the order it costs them: money first, then time, then work.
     *
     * <p>Every line is gated on the permission to act, and only lines with something in them are sent — a
     * dashboard that says "0 offers waiting" every morning teaches people to stop reading it.
     */
    private List<Attention> attentionFor(UserPrincipal caller, Long developmentId) {
        List<Attention> out = new ArrayList<>();
        if (AuthContext.hasAuthority("STATEMENTS_VIEW")) {
            DashboardAttentionQueries.Tally t = attention.unplacedCredits();
            if (t.any()) out.add(new Attention("unplacedCredits", plural(t.count(), "bank credit") + " unplaced",
                    "KES " + KES.format(t.amount()) + " arrived and has not been applied to a booking.",
                    t.count(), t.amount(), t.oldest(), "warning", "/app/statements?state=UNMAPPED"));
        }
        if (AuthContext.hasAuthority("BOOKINGS_VIEW")) {
            DashboardAttentionQueries.Tally t = attention.buyersBehind(developmentId);
            if (t.any()) out.add(new Attention("buyersBehind", plural(t.count(), "buyer") + " behind",
                    "KES " + KES.format(t.amount()) + " due and unpaid across their bookings.",
                    t.count(), t.amount(), null, "warning", "/app/bookings"));
        }
        // The bank's own money: the permission is platform-only, and the check says so as well, because a
        // permission a seller can never hold is one somebody will one day grant by mistake.
        if (caller.isPlatformStaff() && AuthContext.hasAuthority("DISBURSEMENTS_VIEW")) {
            DashboardAttentionQueries.Tally waiting = attention.disbursementsAwaitingRelease();
            if (waiting.any()) out.add(new Attention("disbursementsAwaiting",
                    plural(waiting.count(), "transfer") + " awaiting release",
                    "KES " + KES.format(waiting.amount()) + " proposed and not yet approved by a second person.",
                    waiting.count(), waiting.amount(), waiting.oldest(), "warning", "/app/disbursements?state=AWAITING_APPROVAL"));
            DashboardAttentionQueries.Tally silent = attention.disbursementsUnanswered();
            if (silent.any()) out.add(new Attention("disbursementsUnanswered",
                    plural(silent.count(), "transfer") + " unanswered by the bank",
                    "Sent, and past the time Co-op promised an answer by. Ask Co-op now from the transfer.",
                    silent.count(), silent.amount(), silent.oldest(), "warning", "/app/disbursements?state=SENT"));
        }
        if (AuthContext.hasAuthority("APPROVALS_VIEW")) {
            int n = attention.approvalsAwaiting(caller.getUserId());
            if (n > 0) out.add(new Attention("approvals", plural(n, "approval") + " await your decision",
                    "Somebody else proposed them; they wait for a second pair of eyes.", n, null, null, "neutral", "/app/approvals"));
        }
        if (AuthContext.hasAuthority("PAYMENTS_VIEW")) {
            DashboardAttentionQueries.Tally t = attention.promptsUnanswered();
            if (t.any()) out.add(new Attention("promptsUnanswered", plural(t.count(), "phone prompt") + " unanswered",
                    "Sent to a handset and past the deadline with no answer from the bank. Each booking's Requests tab can ask again.",
                    t.count(), t.amount(), t.oldest(), "neutral", "/app/bookings"));
        }
        if (AuthContext.hasAuthority("BOOKINGS_VIEW")) {
            DashboardAttentionQueries.Tally t = attention.holdsLapsing(LAPSING_DAYS, developmentId);
            if (t.any()) out.add(new Attention("holdsLapsing", plural(t.count(), "hold") + " lapse this week",
                    "Reserved homes whose window runs out within " + LAPSING_DAYS + " days. Agree them or let them go.",
                    t.count(), null, t.oldest(), "neutral", "/app/bookings"));
        }
        if (AuthContext.hasAuthority("PURCHASE_REQUESTS_DECIDE")) {
            DashboardAttentionQueries.Tally t = attention.offersAwaiting();
            if (t.any()) out.add(new Attention("offers", plural(t.count(), "offer") + " awaiting a decision",
                    "KES " + KES.format(t.amount()) + " offered on your listings and not yet answered.",
                    t.count(), t.amount(), t.oldest(), "neutral", "/app/offers"));
        }
        if (AuthContext.hasAuthority("SITE_VISITS_DECIDE")) {
            DashboardAttentionQueries.Tally t = attention.viewingsToConfirm();
            if (t.any()) out.add(new Attention("viewings", plural(t.count(), "viewing") + " to confirm",
                    "Buyers have asked for a time and wait to hear back.", t.count(), null, t.oldest(), "neutral", "/app/viewings"));
        }
        if (AuthContext.hasAuthority("ENQUIRIES_VIEW")) {
            DashboardAttentionQueries.Tally t = attention.enquiriesAwaiting();
            if (t.any()) out.add(new Attention("enquiries", plural(t.count(), "enquiry", "enquiries") + " awaiting a reply",
                    "The last word was the buyer's.", t.count(), null, t.oldest(), "neutral", "/app/enquiries"));
        }
        if (caller.isPlatformStaff()) {
            if (AuthContext.hasAuthority("PROPERTIES_APPROVE")) {
                int n = attention.listingsPending();
                if (n > 0) out.add(new Attention("listingsPending", plural(n, "listing") + " awaiting publication",
                        "Sent by sellers and not yet approved.", n, null, null, "neutral", "/app/listings"));
            }
            if (AuthContext.hasAuthority("SELLERS_VIEW")) {
                int n = attention.sellerApplicationsPending();
                if (n > 0) out.add(new Attention("sellerApplications", plural(n, "seller application") + " pending",
                        "Organisations asking to sell on the platform.", n, null, null, "neutral", "/app/seller-applications"));
            }
            if (AuthContext.hasAuthority("KYC_VIEW")) {
                int n = attention.kycPending();
                if (n > 0) out.add(new Attention("kycPending", plural(n, "KYC pack") + " to review",
                        "Submitted and waiting for Compliance.", n, null, null, "neutral", "/app/compliance"));
            }
        }
        if (AuthContext.hasAuthority("DEVELOPMENTS_FINANCE_VIEW")) {
            Positions now = figures.positions(developmentId);
            if (now.developmentsLate() > 0) out.add(new Attention("projectsLate",
                    plural(now.developmentsLate(), "project") + " running late", "With a phase past its planned date.",
                    now.developmentsLate(), null, null, "warning", "/app/developments"));
            if (now.developmentsOverBudget() > 0) out.add(new Attention("projectsOverBudget",
                    plural(now.developmentsOverBudget(), "project") + " over budget", "Spent more than was allowed.",
                    now.developmentsOverBudget(), null, null, "warning", "/app/developments"));
        }
        return out;
    }

    private static String plural(int n, String noun) {
        return plural(n, noun, noun + "s");
    }

    private static String plural(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    // ── the figures ───────────────────────────────────────────────────────────

    /** <b>Overall.</b> Everything, or one year of it. All time by default: the year is a filter, not the frame. */
    @Transactional(readOnly = true)
    public OverallView overall(Integer year, String developmentHash) {
        if (year != null && (year < 2000 || year > 2100)) {
            throw new HodiException("That is not a year this system has data for.", HttpStatus.BAD_REQUEST);
        }
        Long developmentId = analytics.development(developmentHash);
        return new OverallView(year == null ? "All time" : String.valueOf(year),
                figures.totals(null, year, developmentId), figures.positions(developmentId));
    }

    /** <b>The month.</b> Its money, the sales rate today, and its receipts a page at a time. */
    @Transactional(readOnly = true)
    public MonthlyView monthly(Integer year, Integer month, String developmentHash, int page, int size) {
        LocalDate today = LocalDate.now();
        int y = year == null ? today.getYear() : year;
        int m = month == null ? today.getMonthValue() : month;
        AnalyticsWindow window = new AnalyticsWindow(y, m, y, m);
        Long developmentId = analytics.development(developmentHash);
        return new MonthlyView(y, m, MONTH.format(YearMonth.of(y, m)),
                figures.totals(window, developmentId), figures.positions(developmentId),
                figures.collections(y, m, developmentId, Math.max(0, page),
                        Math.min(Math.max(1, size), 100)));
    }

    /** <b>The calendar.</b> Twelve months of receipts and spend, including the empty ones. */
    @Transactional(readOnly = true)
    public CalendarView calendar(Integer year, String developmentHash) {
        int y = year == null ? LocalDate.now().getYear() : year;
        if (y < 2000 || y > 2100) {
            throw new HodiException("That is not a year this system has data for.", HttpStatus.BAD_REQUEST);
        }
        Long developmentId = analytics.development(developmentHash);
        return new CalendarView(y, figures.collectionsByMonth(y, developmentId));
    }

    /**
     * The project cards, for anyone who may see a development's money.
     *
     * <p>Only the ones that ask for something: a permanent "0 late" card is noise, and the same card showing
     * 2 is something somebody should open today. Gated by the finance permission rather than the audience,
     * because a collaborator with progress rights on a bank's project holds neither figure.
     */
    private List<Card> withProjectCards(List<Card> cards) {
        if (!AuthContext.hasAuthority("DEVELOPMENTS_FINANCE_VIEW")) return cards;
        Positions now = figures.positions(null);
        if (now.developments() == 0) return cards;
        if (now.developmentsLate() > 0) {
            cards.add(new Card("projectsLate", "Projects running late", String.valueOf(now.developmentsLate()),
                    "with a phase past its date", "warning", "/app/developments"));
        }
        if (now.developmentsOverBudget() > 0) {
            cards.add(new Card("projectsOverBudget", "Over budget", String.valueOf(now.developmentsOverBudget()),
                    "spent more than was allowed", "warning", "/app/developments"));
        }
        if (now.overdueBookings() > 0) {
            cards.add(new Card("overdue", "Buyers behind", String.valueOf(now.overdueBookings()),
                    "bookings with an instalment overdue", "warning", "/app/analytics"));
        }
        return cards;
    }

    // ── platform ──────────────────────────────────────────────────────────────

    private List<Card> platformCards() {
        List<Card> cards = new ArrayList<>();
        long active = tenants.countByOnboardingStatus(AppConstant.ONBOARDING_ACTIVE);
        long suspended = tenants.countByOnboardingStatus(AppConstant.ONBOARDING_SUSPENDED);

        cards.add(new Card("sellers", "Seller organisations", String.valueOf(active),
                "active", "neutral", "/platform/tenants"));
        if (suspended > 0) {
            // Only rendered when it is non-zero: a permanent "0 suspended" card is noise, and the same card
            // showing 2 is something somebody should act on today.
            cards.add(new Card("suspended", "Suspended", String.valueOf(suspended),
                    "need attention", "warning", "/platform/tenants?status=suspended"));
        }
        // No institutions or partnerships card. Both counted a marketplace of banks and the negotiations
        // between them and sellers; there is one bank, and it is the one reading this dashboard.
        cards.add(new Card("staff", "Platform staff",
                String.valueOf(profiles.countLiveByUserTypeCode("SUPER_ADMIN")
                        + profiles.countLiveByUserTypeCode("SUPPORT_ADMIN")
                        + profiles.countLiveByUserTypeCode("PLATFORM_AUDITOR")),
                "with access", "neutral", "/platform/users"));
        cards.add(new Card("sessions", "Live sessions",
                String.valueOf(refreshTokens.countLiveSessions(OffsetDateTime.now())),
                "signed in now", "neutral", null));
        return cards;
    }

    // ── seller ────────────────────────────────────────────────────────────────

    private List<Card> sellerCards(UserPrincipal caller) {
        List<Card> cards = new ArrayList<>();
        cards.add(new Card("staff", "Your team",
                String.valueOf(profiles.countByTenant(
                        caller.getTenantId(), AppConstant.STATUS_DELETED)),
                "people with access", "neutral", "/app/users"));

        // The "Finance partners" card is gone: a seller no longer chooses the bank, because there is one
        // and it runs the platform. Nothing replaces it — a card saying "your finance partner is the bank"
        // would be a constant.
        return cards;
    }

    // ── buyer ─────────────────────────────────────────────────────────────────

    private List<Card> buyerCards(UserPrincipal caller) {
        List<Card> cards = new ArrayList<>();
        /*
         * "Ready to use", not "Confirmed" — they are different facts and the card was asserting the wrong
         * one. isVerified() answers "is this profile being held back", which for somebody who also holds a
         * staff profile is no even though their address has never been confirmed. A card reading "Confirmed"
         * beside a details row reading "Not yet" is the platform contradicting itself.
         */
        cards.add(new Card("verification", "Your account",
                caller.isVerified() ? "Ready to use" : "Not confirmed",
                caller.isVerified() ? "nothing outstanding" : "confirm your email to continue",
                caller.isVerified() ? "positive" : "warning",
                "/account/profile"));
        // Saved properties, enquiries and applications land here as those slices ship. Deliberately not
        // stubbed with zeroes: a card reading "0 enquiries" implies the feature exists and found nothing.
        return cards;
    }

    private static String firstNameOf(UserPrincipal caller) {
        String full = caller.getFullName();
        if (full == null || full.isBlank()) return caller.getUsername();
        int space = full.indexOf(' ');
        return space > 0 ? full.substring(0, space) : full;
    }
}
