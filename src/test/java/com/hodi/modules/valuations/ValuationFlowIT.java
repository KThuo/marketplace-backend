package com.hodi.modules.valuations;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.modules.users.User;
import com.hodi.modules.valuations.ValuationService.*;
import com.hodi.modules.valuations.ValuerService.UpdateValuerRequest;
import com.hodi.modules.valuations.ValuerService.ValuerListRequest;
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
 * A valuation from request to a reviewed figure: raised by a seller, assigned by the platform under the
 * indemnity rule, accepted, handed back and remembered, reported, checked by a second person, and cancelled
 * — with each party seeing only what is theirs.
 */
@SpringBootTest
@Transactional
class ValuationFlowIT {

    @Autowired ValuationService valuations;
    @Autowired ValuerService valuerService;
    @Autowired ValuationRequestRepository requests;
    @Autowired ValuationReportRepository reports;
    @Autowired ValuationEventRepository events;
    @Autowired ValuerProfileRepository valuers;
    @Autowired PropertyRepository properties;
    @Autowired ApprovalService approvals;
    @Autowired JdbcTemplate jdbc;

    private static final BigDecimal PRICE = new BigDecimal("12000000");

    private Long tenantId;
    private Long otherTenantId;
    private Property home;
    private ValuerProfile wanjiru;   // covered to 20m, Nairobi
    private ValuerProfile otieno;    // covered to 5m, anywhere

    @BeforeEach
    void build() {
        List<Long> tenants = jdbc.queryForList("select id from tenants where status <> 5 order by id limit 2", Long.class);
        tenantId = tenants.get(0);
        otherTenantId = tenants.size() > 1 ? tenants.get(1) : tenantId + 1;
        asSeller(tenantId);
        home = properties.save(Property.builder()
                .listingKind("HOUSE").propertyType("HOUSE").title("Valued Villa")
                .reference(RrnGenerator.generate("PR")).tenantId(tenantId).tenantName("Test Seller")
                .price(PRICE).currency("KES").county("Nairobi")
                .saleState(AppConstant.UNIT_AVAILABLE).listingState(AppConstant.LISTING_LIVE)
                .publishedAt(OffsetDateTime.now())
                .constructionStatus(AppConstant.BUILD_PLANNED).build());
        wanjiru = valuer("Wanjiru Valuer", new BigDecimal("20000000"), "NAIROBI,KIAMBU");
        otieno = valuer("Otieno Valuer", new BigDecimal("5000000"), null);
    }

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    // ── the whole road ────────────────────────────────────────────────────────

    @Test
    @DisplayName("raised, assigned by the panel, accepted, reported, reviewed — and the count goes up")
    void theWholeRoad() {
        ValuationResponse raised = valuations.raise(new RaiseRequest(home.getReference(), "MORTGAGE",
                LocalDate.now().plusDays(14), new BigDecimal("45000"), "Second floor is let"));
        assertEquals(AppConstant.VALUATION_REQUESTED, raised.state());
        assertEquals("Test Seller", raised.requestedBy());
        assertFalse(raised.mayAssign(), "a seller does not assign");

        asBank();
        assertTrue(valuations.find(raised.reference()).mayAssign());
        ValuationResponse assigned = valuations.assign(raised.reference(), null);
        assertEquals(AppConstant.VALUATION_ASSIGNED, assigned.state());
        assertEquals("Wanjiru Valuer", assigned.valuerName(), "the only one whose cover meets 12m in Nairobi");
        assertEquals(AppConstant.ASSIGN_ROUND_ROBIN, assigned.assignmentMethod());
        assertEquals(1, valuers.findById(wanjiru.getId()).orElseThrow().getOpenAssignments());

        HodiException twice = assertThrows(HodiException.class, () -> valuations.assign(raised.reference(), null));
        assertTrue(twice.getMessage().contains("already has a valuer"));

        asValuer(wanjiru);
        ValuationResponse mine = valuations.find(raised.reference());
        assertTrue(mine.mayAccept() && mine.mayDecline() && mine.mayReport());
        assertFalse(mine.mayCancel(), "a valuer does not cancel a job");
        ValuationResponse accepted = valuations.accept(raised.reference());
        assertEquals(AppConstant.VALUATION_IN_PROGRESS, accepted.state());

        ValuationResponse submitted = valuations.submitReport(raised.reference(), new SubmitReportRequest(
                new BigDecimal("11500000"), new BigDecimal("9800000"), new BigDecimal("12500000"),
                "COMPARABLE", LocalDate.now(), "Good order", "Vacant possession assumed", "Three sales on the same road", null));
        assertEquals(AppConstant.VALUATION_SUBMITTED, submitted.state(), "in, but not counted yet");
        assertNotNull(submitted.submittedAt());
        assertNull(submitted.completedAt());
        assertTrue(approvals.pendingFor(AppConstant.APPROVAL_ENTITY_VALUATION,
                requests.findByReference(raised.reference()).orElseThrow().getId(), AppConstant.APPROVAL_ACTION_REPORT).isPresent(),
                "the reviewer's queue has it");
        assertFalse(submitted.mayApprove(), "the valuer does not review their own");

        asBank();
        ValuationResponse forReview = valuations.find(raised.reference());
        assertTrue(forReview.mayApprove());
        assertFalse(forReview.mayCancel(), "decide on the report rather than cancel the job");
        ValuationResponse done = valuations.review(raised.reference(), new ReviewRequest("APPROVED", "Figures reasonable"));
        assertEquals(AppConstant.VALUATION_COMPLETED, done.state());
        assertNotNull(done.completedAt());
        assertEquals("Figures reasonable", done.reviewNote());
        assertEquals(1, valuers.findById(wanjiru.getId()).orElseThrow().getCompletedCount(), "the count goes up");
        assertEquals(0, valuers.findById(wanjiru.getId()).orElseThrow().getOpenAssignments());

        List<String> road = done.events().stream().map(EventResponse::action).toList();
        assertEquals(List.of("RAISED", "ASSIGNED", "ACCEPTED", "REPORTED", "APPROVED"), road);
    }

    @Test
    @DisplayName("sent back, the report is gone and the valuer submits again; the reviewer's reason is kept")
    void sentBackAndResubmitted() {
        ValuationResponse raised = valuations.raise(new RaiseRequest(home.getReference(), "SALE", null, null, null));
        asBank();
        valuations.assign(raised.reference(), new AssignRequest(wanjiru.getReference(), null, null));
        asValuer(wanjiru);
        valuations.submitReport(raised.reference(), new SubmitReportRequest(new BigDecimal("30000000"), null, null,
                "COMPARABLE", LocalDate.now(), null, null, null, null));

        asBank();
        ValuationResponse back = valuations.review(raised.reference(), new ReviewRequest("SENT_BACK", "Two and a half times the asking price — show the comparables"));
        assertEquals(AppConstant.VALUATION_IN_PROGRESS, back.state());
        assertNull(back.report(), "the report is gone with the send-back");
        assertTrue(back.reviewNote().contains("comparables"));

        asValuer(wanjiru);
        ValuationResponse again = valuations.submitReport(raised.reference(), new SubmitReportRequest(new BigDecimal("12200000"), null, null,
                "COMPARABLE", LocalDate.now(), null, null, "Nos 4, 9 and 12", null));
        assertEquals(AppConstant.VALUATION_SUBMITTED, again.state());
        asBank();
        assertEquals(AppConstant.VALUATION_COMPLETED, valuations.review(raised.reference(), new ReviewRequest("APPROVED", null)).state());
        assertEquals(2, events.countByRequestIdAndAction(requests.findByReference(raised.reference()).orElseThrow().getId(), ValuationEvent.REPORTED));
    }

    // ── the indemnity rule and the hand-back ──────────────────────────────────

    @Test
    @DisplayName("a valuer whose cover is below the price is refused by name and skipped by the panel")
    void theCoverRule() {
        ValuationResponse raised = valuations.raise(new RaiseRequest(home.getReference(), "SALE", null, null, null));
        asBank();
        HodiException e = assertThrows(HodiException.class,
                () -> valuations.assign(raised.reference(), new AssignRequest(otieno.getReference(), null, null)));
        assertTrue(e.getMessage().contains("indemnity cover is below"), e.getMessage());

        // A property nobody's cover meets: the panel says so rather than assigning anybody.
        asSeller(tenantId);
        Property palace = properties.save(Property.builder()
                .listingKind("HOUSE").propertyType("HOUSE").title("Palace")
                .reference(RrnGenerator.generate("PR")).tenantId(tenantId).tenantName("Test Seller")
                .price(new BigDecimal("9000000000")).currency("KES").county("Nairobi")
                .saleState(AppConstant.UNIT_AVAILABLE).listingState(AppConstant.LISTING_LIVE)
                .publishedAt(OffsetDateTime.now()).constructionStatus(AppConstant.BUILD_PLANNED).build());
        ValuationResponse huge = valuations.raise(new RaiseRequest(palace.getReference(), "SALE", null, null, null));
        asBank();
        HodiException none = assertThrows(HodiException.class, () -> valuations.assign(huge.reference(), null));
        assertTrue(none.getMessage().contains("No valuer on the panel"), none.getMessage());

        // Off the panel with a note, the note is kept and she is refused by name.
        valuerService.setOnPanel(wanjiru.getReference(), false, new ValuerService.PanelRequest("On leave"));
        assertEquals("On leave", valuerService.find(wanjiru.getReference()).panelNote());
        HodiException off = assertThrows(HodiException.class,
                () -> valuations.assign(raised.reference(), new AssignRequest(wanjiru.getReference(), null, null)));
        assertTrue(off.getMessage().contains("not on the panel"), off.getMessage());
    }

    @Test
    @DisplayName("handed back, the job returns to the queue, the reason is remembered, and the next valuer starts clean")
    void handedBackAndRemembered() {
        ValuationResponse raised = valuations.raise(new RaiseRequest(home.getReference(), "SALE", null, null, null));
        asBank();
        valuations.assign(raised.reference(), new AssignRequest(wanjiru.getReference(), null, null));
        asValuer(wanjiru);
        ValuationResponse back = valuations.decline(raised.reference(), new DeclineRequest("Conflict of interest: I valued it for the vendor"));
        assertEquals(AppConstant.VALUATION_REQUESTED, back.state());
        assertNull(back.valuerName());
        assertEquals(1, back.handBacks());
        assertTrue(back.declinedReason().contains("Conflict"));
        assertEquals(0, valuers.findById(wanjiru.getId()).orElseThrow().getOpenAssignments());

        asBank();
        // Raise Otieno's cover so he can take it; the update is audited and enforces the cover.
        assertThrows(HodiException.class, () -> valuerService.update(otieno.getReference(),
                new UpdateValuerRequest(null, null, null, null, null, null, null, null, null, null)), "cover is required");
        valuerService.update(otieno.getReference(), new UpdateValuerRequest("Otieno & Co", "V-77", "ISK",
                LocalDate.now().plusYears(1), "Jubilee", "PI-1", new BigDecimal("15000000"), LocalDate.now().plusYears(1), "nairobi, kisumu", null));
        ValuationResponse reassigned = valuations.assign(raised.reference(), new AssignRequest(otieno.getReference(), null, null));
        assertEquals("Otieno Valuer", reassigned.valuerName());
        assertNull(reassigned.declinedReason(), "the last valuer's reason is not the next valuer's");
        assertEquals(1, reassigned.handBacks(), "but the history keeps it");
        assertEquals("NAIROBI,KISUMU", valuerService.find(otieno.getReference()).counties(), "normalised once");
    }

    // ── who sees what ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a seller sees their own, another seller sees nothing, a valuer sees only what is theirs")
    void scope() {
        ValuationResponse raised = valuations.raise(new RaiseRequest(home.getReference(), "SALE", null, null, null));
        assertTrue(valuations.list(new ValuationListRequest()).getContent().stream().anyMatch(v -> v.reference().equals(raised.reference())));

        asSeller(otherTenantId);
        assertTrue(valuations.list(new ValuationListRequest()).getContent().stream().noneMatch(v -> v.reference().equals(raised.reference())));
        assertThrows(HodiException.class, () -> valuations.find(raised.reference()));

        asValuer(otieno);
        assertThrows(HodiException.class, () -> valuations.find(raised.reference()), "not assigned to him");
        assertThrows(HodiException.class, () -> valuations.accept(raised.reference()));

        asBank();
        ValuerListRequest available = new ValuerListRequest();
        available.setAvailable(true);
        List<String> names = valuerService.list(available).getContent().stream().map(v -> v.fullName()).toList();
        assertTrue(names.contains("Wanjiru Valuer") && names.contains("Otieno Valuer"));
        ValuerListRequest kiambu = new ValuerListRequest();
        kiambu.setCounty("Kiambu");
        List<String> inKiambu = valuerService.list(kiambu).getContent().stream().map(v -> v.fullName()).toList();
        assertTrue(inKiambu.contains("Wanjiru Valuer"), "named");
        assertTrue(inKiambu.contains("Otieno Valuer"), "anywhere");
    }

    @Test
    @DisplayName("cancelled with a reason, the job is closed and the valuer released")
    void cancelled() {
        ValuationResponse raised = valuations.raise(new RaiseRequest(home.getReference(), "SALE", null, null, null));
        asBank();
        valuations.assign(raised.reference(), new AssignRequest(wanjiru.getReference(), null, null));
        asSeller(tenantId);
        ValuationResponse gone = valuations.cancel(raised.reference(), new DeclineRequest("Sale fell through"));
        assertEquals(AppConstant.VALUATION_CANCELLED, gone.state());
        assertEquals("Sale fell through", gone.cancelledReason());
        assertEquals(0, valuers.findById(wanjiru.getId()).orElseThrow().getOpenAssignments());
        assertEquals("CANCELLED", gone.events().isEmpty() ? valuations.find(raised.reference()).events().get(2).action() : gone.events().get(2).action());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ValuerProfile valuer(String name, BigDecimal cover, String counties) {
        Long profileId = jdbc.queryForObject("select p.id from user_profiles p where not exists "
                + "(select 1 from valuer_profiles v where v.profile_id = p.id) and not exists "
                + "(select 1 from agent_profiles a where a.profile_id = p.id) order by p.id desc limit 1", Long.class);
        Long userId = jdbc.queryForObject("select user_id from user_profiles where id = ?", Long.class, profileId);
        return valuers.save(ValuerProfile.builder()
                .userId(userId).profileId(profileId).reference(RrnGenerator.generate("VP"))
                .fullName(name).firmName(name + " & Co").registrationNumber("V-" + profileId).registrationBody("ISK")
                .registeredUntil(LocalDate.now().plusYears(1))
                .piInsurer("Jubilee").piPolicyNumber("PI-" + profileId).piSumAssured(cover).piExpiresOn(LocalDate.now().plusYears(1))
                .counties(counties).build());
    }

    private void asSeller(Long tenant) { signIn(AppConstant.ACTOR_SELLER, "SELLER_OWNER", tenant, false, 1L, 1L); }
    private void asBank() { signIn(AppConstant.ACTOR_PLATFORM, "SUPER_ADMIN", null, true, 2L, 2L); }
    private void asValuer(ValuerProfile v) { signIn(AppConstant.ACTOR_VALUER, "VALUER", null, false, v.getUserId(), v.getProfileId()); }

    private void signIn(String actor, String userType, Long tenant, boolean platform, Long userId, Long profileId) {
        User user = User.builder().id(userId).username(userType.toLowerCase() + "-" + profileId).password("x")
                .email(userType.toLowerCase() + profileId + "@example.invalid").firstName("Va").lastName("Luation")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(profileId).userId(userId)
                .profileType(actor).userTypeCode(userType)
                .tenantId(tenant).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("VALUATIONS_VIEW", "VALUATIONS_REQUEST", "VALUATIONS_ASSIGN", "VALUATIONS_CANCEL",
                        "VALUATIONS_WORK", "VALUATIONS_APPROVE", "VALUER_PANEL_VIEW", "VALUER_PANEL_MANAGE", "APPROVALS_DECIDE"),
                tenant == null ? List.of() : List.of(tenant), platform, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }
}
