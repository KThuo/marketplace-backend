package com.hodi.modules.dashboard;

import com.hodi.common.AppConstant;
import com.hodi.modules.auth.RefreshTokenRepository;
import com.hodi.modules.institutions.LendingInstitutionRepository;
import com.hodi.modules.partnerships.PartnershipRepository;
import com.hodi.modules.tenants.TenantRepository;
import com.hodi.modules.users.UserRepository;
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
 * <h2>What this honestly is right now</h2>
 *
 * <p>A skeleton over access-management facts, because that is the only data that exists. Every card below
 * counts organisations, staff, partnerships or sessions. The figures that will matter — listings, enquiries,
 * applications, disbursements, conversion — are added as the functional slices land, each one appending to
 * this same endpoint rather than introducing a second dashboard API.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TenantRepository tenants;
    private final LendingInstitutionRepository institutions;
    private final PartnershipRepository partnerships;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;

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
            return new DashboardResponse("PLATFORM", greeting, platformCards());
        }
        if (caller.isSellerStaff()) {
            return new DashboardResponse("SELLER", greeting, sellerCards(caller));
        }
        if (caller.isLenderStaff()) {
            return new DashboardResponse("LENDER", greeting, lenderCards(caller));
        }
        return new DashboardResponse("BUYER", greeting, buyerCards(caller));
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
        cards.add(new Card("institutions", "Lending institutions",
                String.valueOf(institutions.findByStatusNotOrderByNameAsc(
                        AppConstant.STATUS_DELETED).size()),
                "registered", "neutral", "/platform/institutions"));
        cards.add(new Card("partnerships", "Active partnerships",
                String.valueOf(partnerships.countActive()),
                "seller ↔ lender", "positive", "/app/partnerships"));

        long pending = partnerships.countPending();
        if (pending > 0) {
            cards.add(new Card("pendingPartnerships", "Awaiting approval", String.valueOf(pending),
                    "partnership proposals", "warning", "/app/partnerships?state=pending"));
        }
        cards.add(new Card("staff", "Platform staff",
                String.valueOf(users.countLiveByUserTypeCode("SUPER_ADMIN")
                        + users.countLiveByUserTypeCode("SUPPORT_ADMIN")
                        + users.countLiveByUserTypeCode("PLATFORM_AUDITOR")),
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
                String.valueOf(users.countByTenantIdAndStatusNot(
                        caller.getTenantId(), AppConstant.STATUS_DELETED)),
                "people with access", "neutral", "/app/users"));

        int lenders = partnerships.findActiveInstitutionIdsForTenant(caller.getTenantId()).size();
        cards.add(new Card("lenders", "Finance partners", String.valueOf(lenders),
                lenders == 0 ? "none yet — add one to offer finance" : "can see your portfolio",
                lenders == 0 ? "warning" : "positive", "/app/partnerships"));

        long pending = partnerships.countPendingForTenant(caller.getTenantId());
        if (pending > 0) {
            cards.add(new Card("pending", "Awaiting your decision", String.valueOf(pending),
                    "partnership proposals", "warning", "/app/partnerships?state=pending"));
        }
        return cards;
    }

    // ── lender ────────────────────────────────────────────────────────────────

    private List<Card> lenderCards(UserPrincipal caller) {
        List<Card> cards = new ArrayList<>();
        int sellers = caller.getVisibleTenantIds().size();

        /*
         * The card that explains a stranded lender.
         *
         * With no approved partnership, TenantScope returns an empty set and every portfolio list is
         * legitimately empty — which looks exactly like a broken deployment. This says so in words, which is
         * the whole reason TenantScope.isStranded() exists.
         */
        cards.add(new Card("sellers", "Seller portfolios", String.valueOf(sellers),
                sellers == 0
                        ? "no partnerships yet — nothing will be visible until one is approved"
                        : "you can work these",
                sellers == 0 ? "warning" : "positive",
                "/app/partnerships"));

        cards.add(new Card("staff", "Your team",
                String.valueOf(users.countByInstitutionIdAndStatusNot(
                        caller.getInstitutionId(), AppConstant.STATUS_DELETED)),
                "people with access", "neutral", "/app/users"));

        long pending = partnerships.countPendingForInstitution(caller.getInstitutionId());
        if (pending > 0) {
            cards.add(new Card("pending", "Awaiting your decision", String.valueOf(pending),
                    "partnership proposals", "warning", "/app/partnerships?state=pending"));
        }
        return cards;
    }

    // ── buyer ─────────────────────────────────────────────────────────────────

    private List<Card> buyerCards(UserPrincipal caller) {
        List<Card> cards = new ArrayList<>();
        cards.add(new Card("verification", "Your account",
                caller.isVerified() ? "Confirmed" : "Unconfirmed",
                caller.isVerified() ? "ready to use" : "confirm your email to continue",
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
