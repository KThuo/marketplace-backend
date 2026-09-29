package com.hodi.modules.valuations;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.finance.AffordabilityService;
import com.hodi.modules.finance.FinanceDtos.AffordabilityRequest;
import com.hodi.modules.finance.FinanceMatchService;
import com.hodi.modules.leads.PurchaseRequest;
import com.hodi.modules.leads.PurchaseRequestRepository;
import com.hodi.modules.kyc.DocumentService;
import com.hodi.modules.operations.CalendarEntry;
import com.hodi.modules.operations.CalendarEntryRepository;
import com.hodi.modules.operations.OperationsConstants;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
    @Autowired ValuationSweep sweep;
    @Autowired LendingValueService lendingValues;
    @Autowired FinanceMatchService matcher;
    @Autowired AffordabilityService affordability;
    @Autowired PurchaseRequestRepository offers;
    @Autowired CalendarEntryRepository diary;
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

    // ── the inspection and the signed report ──────────────────────────────────

    @Test
    @DisplayName("the inspection goes in the diary and the signed report into the vault, for those the job is visible to")
    void inspectionAndSignedReport() {
        ValuationResponse raised = valuations.raise(new RaiseRequest(home.getReference(), "MORTGAGE", null, null, null));
        asBank();
        valuations.assign(raised.reference(), new AssignRequest(wanjiru.getReference(), null, null));
        Long jobId = requests.findByReference(raised.reference()).orElseThrow().getId();

        asValuer(wanjiru);
        OffsetDateTime onSite = OffsetDateTime.now().plusDays(3).withHour(10).withMinute(0).withSecond(0).withNano(0);
        ValuationResponse booked = valuations.scheduleInspection(raised.reference(), new InspectionRequest(onSite));
        assertEquals(onSite.toInstant(), booked.inspectionAt().toInstant());
        CalendarEntry entry = diary.findBySourceTypeAndSourceId(OperationsConstants.SOURCE_VALUATION, jobId).orElseThrow();
        assertEquals(OperationsConstants.ENTRY_SCHEDULED, entry.getState());
        assertEquals(tenantId, entry.getTenantId(), "in the seller's diary — it is their house");
        assertEquals(wanjiru.getUserId(), entry.getOwnerUserId());

        HodiException past = assertThrows(HodiException.class, () -> valuations.scheduleInspection(raised.reference(),
                new InspectionRequest(OffsetDateTime.now().minusDays(2))));
        assertTrue(past.getMessage().contains("still to come"));

        MockMultipartFile signed = new MockMultipartFile("file", "report.pdf", "application/pdf",
                "%PDF-1.4 signed".getBytes(StandardCharsets.UTF_8));
        HodiException early = assertThrows(HodiException.class, () -> valuations.attachReportDocument(raised.reference(), signed));
        assertTrue(early.getMessage().contains("submitted report"), "figures first, then the file");

        ValuationResponse submitted = valuations.submitReport(raised.reference(), new SubmitReportRequest(
                new BigDecimal("11000000"), null, null, null, null, null, null, null, null));
        assertEquals(onSite.toLocalDate(), submitted.report().inspectedOn(), "the booked inspection is the inspection date");
        assertTrue(submitted.mayAttachReport());
        assertThrows(HodiException.class, () -> valuations.attachReportDocument(raised.reference(),
                new MockMultipartFile("file", "report.docx", "application/octet-stream", new byte[]{1})), "a PDF");
        ValuationResponse attached = valuations.attachReportDocument(raised.reference(), signed);
        assertNotNull(attached.report().documentReference());
        assertEquals("report.pdf", attached.report().documentName());
        assertTrue(attached.events().isEmpty(), "a write returns the job without the timeline");
        assertTrue(valuations.find(raised.reference()).events().stream().map(EventResponse::action).toList()
                .containsAll(List.of("INSPECTION", "DOCUMENTED")));

        asSeller(tenantId);
        DocumentService.Fetched read = valuations.reportDocument(raised.reference());
        assertEquals("report.pdf", read.fileName());
        assertEquals("%PDF-1.4 signed", new String(read.bytes(), StandardCharsets.UTF_8));

        asSeller(otherTenantId);
        assertThrows(Exception.class, () -> valuations.reportDocument(raised.reference()), "not their job");

        asBank();
        valuations.review(raised.reference(), new ReviewRequest("APPROVED", null));
        assertEquals(OperationsConstants.ENTRY_DONE,
                diary.findBySourceTypeAndSourceId(OperationsConstants.SOURCE_VALUATION, jobId).orElseThrow().getState());
    }

    @Test
    @DisplayName("a hand-back takes the appointment out of the diary; a cancellation marks it cancelled")
    void theDiaryFollowsTheJob() {
        ValuationResponse first = valuations.raise(new RaiseRequest(home.getReference(), "SALE", null, null, null));
        ValuationResponse second = valuations.raise(new RaiseRequest(home.getReference(), "SALE", null, null, null));
        asBank();
        valuations.assign(first.reference(), new AssignRequest(wanjiru.getReference(), null, null));
        valuations.assign(second.reference(), new AssignRequest(wanjiru.getReference(), null, null));
        Long firstId = requests.findByReference(first.reference()).orElseThrow().getId();
        Long secondId = requests.findByReference(second.reference()).orElseThrow().getId();

        asValuer(wanjiru);
        valuations.scheduleInspection(first.reference(), new InspectionRequest(OffsetDateTime.now().plusDays(1)));
        valuations.scheduleInspection(second.reference(), new InspectionRequest(OffsetDateTime.now().plusDays(2)));
        ValuationResponse handedBack = valuations.decline(first.reference(), new DeclineRequest("Conflict of interest"));
        assertNull(handedBack.inspectionAt());
        assertTrue(diary.findBySourceTypeAndSourceId(OperationsConstants.SOURCE_VALUATION, firstId).isEmpty(),
                "the next valuer books their own");

        asBank();
        valuations.cancel(second.reference(), new DeclineRequest("Sold privately"));
        assertEquals(OperationsConstants.ENTRY_CANCELLED,
                diary.findBySourceTypeAndSourceId(OperationsConstants.SOURCE_VALUATION, secondId).orElseThrow().getState());
    }

    // ── the sweep ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the sweep says overdue once, and warns of a lapse once per expiry date")
    void theSweepSaysItOnce() {
        ValuationResponse raised = valuations.raise(new RaiseRequest(home.getReference(), "SALE",
                LocalDate.now().minusDays(1), null, null));
        asBank();
        valuations.assign(raised.reference(), new AssignRequest(wanjiru.getReference(), null, null));
        Long jobId = requests.findByReference(raised.reference()).orElseThrow().getId();

        // Cover ending inside the notice; registration well after.
        wanjiru.setPiExpiresOn(LocalDate.now().plusDays(10));
        valuers.save(wanjiru);

        sweep.pass(LocalDate.now());
        assertEquals(LocalDate.now(), requests.findById(jobId).orElseThrow().getOverdueNoticedOn());
        assertEquals(LocalDate.now().plusDays(10), valuers.findById(wanjiru.getId()).orElseThrow().getLapseWarnedFor());
        assertNull(valuers.findById(otieno.getId()).orElseThrow().getLapseWarnedFor(), "a year away is not a warning");

        // Tomorrow: nothing new to say.
        sweep.pass(LocalDate.now().plusDays(1));
        assertEquals(LocalDate.now(), requests.findById(jobId).orElseThrow().getOverdueNoticedOn(), "said once");
        assertEquals(LocalDate.now().plusDays(10), valuers.findById(wanjiru.getId()).orElseThrow().getLapseWarnedFor());

        // Renewed to a later date, and that date comes into the notice: warned again, about the new date.
        wanjiru.setPiExpiresOn(LocalDate.now().plusDays(25));
        valuers.save(wanjiru);
        sweep.pass(LocalDate.now());
        assertEquals(LocalDate.now().plusDays(25), valuers.findById(wanjiru.getId()).orElseThrow().getLapseWarnedFor());
    }

    // ── the figure reaches the bank ───────────────────────────────────────────

    @Test
    @DisplayName("once approved, the forced-sale value is what the bank lends against — and the working says so")
    void theFigureReachesTheBank() {
        assertEquals(LendingValueService.BASIS_PRICE, lendingValues.lendingValueFor(home.getId(), PRICE).basis(),
                "no valuation yet: the price");
        assertTrue(lendingValues.latestFor(home.getId(), PRICE).isEmpty());

        ValuationResponse raised = valuations.raise(new RaiseRequest(home.getReference(), "MORTGAGE", null, null, null));
        asBank();
        valuations.assign(raised.reference(), new AssignRequest(wanjiru.getReference(), null, null));
        asValuer(wanjiru);
        valuations.submitReport(raised.reference(), new SubmitReportRequest(
                new BigDecimal("11500000"), new BigDecimal("9800000"), null, "COMPARABLE", LocalDate.now(), null, null, null, null));
        assertEquals(LendingValueService.BASIS_PRICE, lendingValues.lendingValueFor(home.getId(), PRICE).basis(),
                "submitted is not approved: still the price");

        asBank();
        valuations.review(raised.reference(), new ReviewRequest("APPROVED", null));

        LendingValueService.LendingValue lending = lendingValues.lendingValueFor(home.getId(), PRICE);
        assertEquals(0, new BigDecimal("9800000").compareTo(lending.value()));
        assertEquals(LendingValueService.BASIS_FORCED_SALE, lending.basis());
        assertEquals(raised.reference(), lending.valuationReference());
        assertEquals(LendingValueService.BASIS_PRICE, lendingValues.lendingValueFor(home.getId(), new BigDecimal("9000000")).basis(),
                "a price below the figure is the lesser, and wins");

        var figures = lendingValues.latestFor(home.getId(), PRICE).orElseThrow();
        assertEquals(0, new BigDecimal("11500000").compareTo(figures.marketValue()));
        assertEquals("Wanjiru Valuer", figures.valuerName());

        var panel = matcher.forListing(home.getReference(), null, null);
        assertEquals(0, PRICE.compareTo(panel.price()), "the price is still the price");
        assertEquals(0, new BigDecimal("9800000").compareTo(panel.lendingValue()), "the options are costed on the valuation");
        assertEquals(raised.reference(), panel.valuationReference());

        asSeller(tenantId);
        var estimate = affordability.estimate(new AffordabilityRequest(new BigDecimal("400000"), null, null,
                new BigDecimal("2000000"), (short) 240, null, null, home.getReference(), null));
        assertEquals(0, new BigDecimal("9800000").compareTo(estimate.lendingValue()));
        assertEquals(LendingValueService.BASIS_FORCED_SALE, estimate.lendingBasis());
        assertEquals(0, new BigDecimal("7800000").compareTo(estimate.loanRequired()), "the loan is sized on the lending value");
        assertEquals("Lending value", estimate.steps().get(0).label(), "the working says which figure it used, first");
    }

    @Test
    @DisplayName("a valuation raised against an offer names the sale, and only a party to the sale may raise it")
    void raisedAgainstASale() {
        PurchaseRequest offer = offers.save(PurchaseRequest.builder()
                .reference(RrnGenerator.generate("OF")).tenantId(tenantId).tenantName("Test Seller")
                .propertyId(home.getId()).propertyReference(home.getReference()).propertyTitle(home.getTitle())
                .askingPrice(PRICE).userId(1L).buyerName("A Buyer").offerAmount(new BigDecimal("11000000"))
                .build());

        asSeller(otherTenantId);
        HodiException notTheirs = assertThrows(HodiException.class, () -> valuations.raise(
                new RaiseRequest(null, "MORTGAGE", null, null, null, null, offer.getReference())));
        assertTrue(notTheirs.getMessage().contains("not yours"));

        asSeller(tenantId);
        ValuationResponse raised = valuations.raise(new RaiseRequest(null, "MORTGAGE", null, null, null, null, offer.getReference()));
        assertEquals(home.getReference(), raised.propertyReference(), "the offer names the home");
        assertEquals(offer.getReference(), raised.offerReference());
        assertNull(raised.bookingId());
        assertEquals(offer.getId(), requests.findByReference(raised.reference()).orElseThrow().getOfferId());
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
