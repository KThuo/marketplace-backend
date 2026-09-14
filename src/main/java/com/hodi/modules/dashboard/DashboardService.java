package com.hodi.modules.dashboard;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.analytics.AnalyticsQueries;
import com.hodi.modules.analytics.AnalyticsService;
import com.hodi.modules.analytics.AnalyticsViews.CalendarView;
import com.hodi.modules.analytics.AnalyticsViews.MonthlyView;
import com.hodi.modules.analytics.AnalyticsViews.OverallView;
import com.hodi.modules.analytics.AnalyticsViews.Positions;
import com.hodi.modules.analytics.AnalyticsWindow;
import com.hodi.modules.auth.RefreshTokenRepository;
import org.springframework.http.HttpStatus;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import com.hodi.modules.institutions.LendingInstitutionRepository;
import com.hodi.modules.tenants.TenantRepository;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>The cards are the facts that need saying in words — a stranded lender, an unconfirmed buyer, projects
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
    private final LendingInstitutionRepository institutions;
    private final UserProfileRepository profiles;
    private final RefreshTokenRepository refreshTokens;
    private final AnalyticsQueries figures;
    private final AnalyticsService analytics;

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
         * No LENDER branch. Its staff are platform actors now, so they take the PLATFORM arm above — which
         * is the right dashboard for somebody who runs the place rather than one who was let into it.
         */
        return new DashboardResponse("BUYER", greeting, buyerCards(caller));
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

        // The "Finance partners" card is gone: a seller no longer chooses a lender, because there is one
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
