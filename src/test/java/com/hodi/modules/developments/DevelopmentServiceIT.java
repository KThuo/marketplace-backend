package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.developments.DevelopmentPhaseDtos.SavePhaseRequest;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.developments.DevelopmentUnitTypeDtos.SaveUnitTypeRequest;
import com.hodi.modules.developments.DevelopmentDtos.SaveDevelopmentRequest;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.users.User;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The development lifecycle, through the service rather than the repository.
 *
 * <p>What this covers that the other two tests cannot: the publish gate, which is a list of refusals; the state
 * machine, which is where a wrong transition becomes a page nobody meant to publish; and the archive cascade,
 * which exists only because the database has no cascade of its own — the one piece of this module where
 * forgetting a line leaves the marketplace serving a dead project.
 *
 * <p>A principal is put in the security context by hand, because every write here reads {@code AuthContext} for
 * the owning organisation and the audit trail. That is the same derivation the login path performs.
 */
@SpringBootTest
@Transactional
class DevelopmentServiceIT {

    @Autowired DevelopmentService service;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired com.hodi.modules.properties.PropertyRepository properties;
    @Autowired DevelopmentPhaseRepository phases;
    @Autowired DevelopmentPhaseService phaseService;
    @Autowired DevelopmentUnitTypeService typeService;
    @Autowired com.hodi.modules.approvals.ApprovalService approvals;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Long tenantId() {
        return jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
    }

    private String tenantName(Long id) {
        return jdbc.queryForObject("select name from tenants where id = ?", String.class, id);
    }

    private void signInAsSeller(Long tenantId) {
        User user = User.builder().id(1L).username("seller-test").password("x")
                .email("s@example.invalid").firstName("Sam").lastName("Seller")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenantId).tenantName(tenantName(tenantId))
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("DEVELOPMENTS_CREATE", "DEVELOPMENTS_UPDATE", "DEVELOPMENTS_SUBMIT"),
                List.of(tenantId), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private SaveDevelopmentRequest request(String name, String town) {
        return new SaveDevelopmentRequest(name, "Two hundred units over four blocks.", "APARTMENT",
                AppConstant.DEV_PURPOSE_FOR_SALE, "Acacia Builders Ltd", null, null, null,
                "Nairobi", town, "Kilimani", "Off Argwings Kodhek", null, null,
                200, null, null, null, null, null,
                // amenityCodes, greenCertified, greenCertification, energyRating, percentComplete
                null, null, null, null, null);
    }

    private void addTypology(Long developmentId) {
        unitTypes.save(DevelopmentUnitType.builder()
                .developmentId(developmentId)
                .reference(RrnGenerator.generate("UT"))
                .code("2BED").name("Two bedroom").propertyType("APARTMENT")
                .bedrooms((short) 2).listPrice(new java.math.BigDecimal("9500000"))
                .build());
    }

    @Test
    @DisplayName("a seller drafting a development is its owner and its marketer, without being asked")
    void createDefaultsTheSellingOrganisation() {
        Long tenantId = tenantId();
        signInAsSeller(tenantId);

        var created = service.create(request("Highrise Apartments", "Nairobi"));

        assertEquals(AppConstant.LISTING_DRAFT, created.listingState());
        assertEquals("SELLER", created.ownerKind());
        assertNotNull(created.reference());
        assertEquals(tenantName(tenantId), created.sellingTenantName(),
                "asking a seller to name themselves is a question with one answer");
    }

    /*
     * "the platform cannot own a development, because somebody has to be building it" used to be pinned
     * here. Both halves of that stopped being true — Co-op runs this platform and also builds, and its
     * staff carry no organisation — so the rule it guarded is gone. What replaced it is guarded below:
     * platform staff must say whose project it is, and may say the bank.
     */

    @Test
    @DisplayName("the publish gate refuses in turn: no town, then no unit type")
    void publishGate() {
        Long tenantId = tenantId();
        signInAsSeller(tenantId);

        // No town.
        var noTown = service.create(request("Nameless Heights", null));
        HodiException townFirst = assertThrows(HodiException.class,
                () -> service.submit(noTown.id(), null));
        assertTrue(townFirst.getMessage().contains("town"), townFirst.getMessage());

        // Town, but nothing to buy.
        var withTown = service.create(request("Riverside Court", "Nairobi"));
        HodiException needsTypology = assertThrows(HodiException.class,
                () -> service.submit(withTown.id(), null));
        assertTrue(needsTypology.getMessage().contains("unit type"), needsTypology.getMessage());

        // With a typology it goes for approval.
        addTypology(HashIdUtil.decodeId(withTown.id()));
        var submitted = service.submit(withTown.id(), null);
        assertEquals(AppConstant.LISTING_PENDING, submitted.listingState());
    }

    @Test
    @DisplayName("approval makes it live and stamps the date the database insists on")
    void publication() {
        Long tenantId = tenantId();
        signInAsSeller(tenantId);
        var created = service.create(request("Garden City Phase 2", "Nairobi"));
        Long id = HashIdUtil.decodeId(created.id());
        addTypology(id);
        service.submit(created.id(),
                new com.hodi.modules.developments.DevelopmentDtos.SubmitRequest(null));

        service.applyPublication(id);

        Development after = developments.findById(id).orElseThrow();
        assertEquals(AppConstant.LISTING_LIVE, after.getListingState());
        assertNotNull(after.getPublishedAt(),
                "the CHECK on the table refuses LIVE without it, so this is not merely tidy");
    }

    @Test
    @DisplayName("a refusal sends it back to draft rather than leaving it pending forever")
    void refusal() {
        Long tenantId = tenantId();
        signInAsSeller(tenantId);
        var created = service.create(request("Sent Back Villas", "Nairobi"));
        Long id = HashIdUtil.decodeId(created.id());
        addTypology(id);
        service.submit(created.id(),
                new com.hodi.modules.developments.DevelopmentDtos.SubmitRequest(null));

        service.applyRefusal(id, "The block plan does not match the unit schedule.");

        assertEquals(AppConstant.LISTING_DRAFT,
                developments.findById(id).orElseThrow().getListingState());
    }

    @Test
    @DisplayName("making a project private clears its marketer, and will not do it under a live page")
    void markPrivate() {
        Long tenantId = tenantId();
        signInAsSeller(tenantId);
        var created = service.create(request("Quiet Holdings", "Nairobi"));
        Long id = HashIdUtil.decodeId(created.id());

        var priv = service.markPrivate(created.id());
        assertEquals(AppConstant.DEV_STATE_PRIVATE, priv.listingState());
        assertNull(priv.sellingTenantName(), "a project nobody markets has nobody marketing it");

        // And a live one is refused rather than pulled out from under a reader.
        var live = service.create(request("Live Court", "Nairobi"));
        Long liveId = HashIdUtil.decodeId(live.id());
        addTypology(liveId);
        service.submit(live.id(), null);
        service.applyPublication(liveId);
        assertThrows(HodiException.class, () -> service.markPrivate(live.id()));
    }

    @Test
    @DisplayName("archiving cascades to phases and typologies, because the database has no cascade")
    void archiveCascades() {
        Long tenantId = tenantId();
        signInAsSeller(tenantId);
        var created = service.create(request("Doomed Gardens", "Nairobi"));
        Long id = HashIdUtil.decodeId(created.id());
        addTypology(id);
        phases.save(DevelopmentPhase.builder().developmentId(id)
                .reference(RrnGenerator.generate("PH")).name("Foundation").sequenceNo((short) 1).build());

        assertEquals(1, unitTypes.findForDevelopment(id).size());
        assertEquals(1, phases.findForDevelopment(id).size());

        service.archive(created.id());

        assertEquals(AppConstant.STATUS_DELETED, developments.findById(id).orElseThrow().getStatus());
        assertTrue(unitTypes.findForDevelopment(id).isEmpty(),
                "a typology of a dead project must not still be listed");
        assertTrue(phases.findForDevelopment(id).isEmpty());
    }

    @Test
    @DisplayName("a live development cannot be archived; withdraw it first")
    void archiveRefusedWhileLive() {
        Long tenantId = tenantId();
        signInAsSeller(tenantId);
        var created = service.create(request("Stubborn Heights", "Nairobi"));
        Long id = HashIdUtil.decodeId(created.id());
        addTypology(id);
        service.submit(created.id(),
                new com.hodi.modules.developments.DevelopmentDtos.SubmitRequest(null));
        service.applyPublication(id);

        assertThrows(HodiException.class, () -> service.archive(created.id()));
    }

    /** Platform staff: no organisation of their own, which is the whole point of these tests. */
    /** Platform staff who may also decide — the strongest caller there is, and still not exempt. */
    private void signInAsPlatformApprover() {
        User user = User.builder().id(11L).username("superadmin-approver").password("x")
                .email("pa@example.invalid").firstName("Pat").lastName("Platform")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(11L).userId(11L)
                .profileType(AppConstant.ACTOR_PLATFORM).userTypeCode("SUPER_ADMIN")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("DEVELOPMENTS_CREATE", "DEVELOPMENTS_UPDATE", "DEVELOPMENTS_SUBMIT",
                        "DEVELOPMENTS_APPROVE"),
                List.of(), true, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private void signInAsPlatform() {
        User user = User.builder().id(9L).username("superadmin").password("x")
                .email("p@example.invalid").firstName("Pat").lastName("Platform")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(9L).userId(9L)
                .profileType(AppConstant.ACTOR_PLATFORM).userTypeCode("SUPER_ADMIN")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("DEVELOPMENTS_CREATE", "DEVELOPMENTS_UPDATE", "DEVELOPMENTS_SUBMIT"),
                List.of(), true, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /** The same request, with an owner named on it — which only platform staff may do. */
    private SaveDevelopmentRequest ownedBy(String name, String kind, String tenantHash) {
        return new SaveDevelopmentRequest(name, "Two hundred units over four blocks.", "APARTMENT",
                AppConstant.DEV_PURPOSE_FOR_SALE, "Acacia Builders Ltd", null, kind, tenantHash,
                "Nairobi", "Nairobi", "Kilimani", "Off Argwings Kodhek", null, null,
                200, null, null, null, null, null,
                // amenityCodes, greenCertified, greenCertification, energyRating, percentComplete
                null, null, null, null, null);
    }

    // ── who may own one ───────────────────────────────────────────────────────

    @Test
    @DisplayName("platform staff draft on a seller's behalf, and the seller owns it")
    void platformDraftsForASeller() {
        Long tenantId = tenantId();
        signInAsPlatform();

        var created = service.create(ownedBy("Platform Drafted", AppConstant.DEV_OWNER_SELLER, HashIdUtil.encodeId(tenantId)));

        assertEquals("SELLER", created.ownerKind());
        assertEquals(tenantName(tenantId), created.ownerName());
    }

    /**
     * The case the old refusal made impossible.
     *
     * <p>Co-op runs the platform and also builds, and its staff carry no organisation — so "a development
     * belongs to the organisation building or financing it" turned away the only people who could have
     * drafted this.
     */
    @Test
    @DisplayName("the bank can own a development it is building itself")
    void platformDraftsForTheBank() {
        signInAsPlatform();

        var created = service.create(ownedBy("The Bank's Own", AppConstant.DEV_OWNER_BANK, null));

        assertEquals("BANK", created.ownerKind());
        assertNotNull(created.ownerName());
    }

    @Test
    @DisplayName("platform staff naming nobody are told what to choose")
    void platformMustNameAnOwner() {
        signInAsPlatform();

        HodiException thrown = assertThrows(HodiException.class,
                () -> service.create(ownedBy("Ownerless", null, null)));
        assertTrue(thrown.getMessage().contains("whose project"), thrown.getMessage());
    }

    @Test
    @DisplayName("naming a seller without saying which one is refused")
    void sellerOwnerNeedsAnOrganisation() {
        signInAsPlatform();

        HodiException thrown = assertThrows(HodiException.class,
                () -> service.create(ownedBy("Which Seller", AppConstant.DEV_OWNER_SELLER, null)));
        assertTrue(thrown.getMessage().contains("seller organisation"), thrown.getMessage());
    }

    /**
     * The guarantee that matters more than the feature.
     *
     * <p>A seller sending an owner is not refused — the fields are simply never read for a caller who has
     * an organisation of their own. Refusing would work too; not reading is what makes it impossible to
     * forget a check somewhere else.
     */
    @Test
    @DisplayName("a seller naming another owner is ignored, not obeyed")
    void aSellerCannotAssignSomebodyElse() {
        Long tenantId = tenantId();
        signInAsSeller(tenantId);

        var created = service.create(ownedBy("Mine Really", AppConstant.DEV_OWNER_BANK, null));

        assertEquals("SELLER", created.ownerKind());
        assertEquals(tenantName(tenantId), created.ownerName());
    }

    // ── a phase percentage other than 100 ─────────────────────────────────────

    private SavePhaseRequest phase(String name, Short percent, java.time.LocalDate done) {
        return new SavePhaseRequest(name, null, (short) 1, null, null, null, null, done,
                null, null, null, percent, null, null);
    }

    /**
     * The bug: a phase that reached 100% could never be moved back down.
     *
     * <p>ck_phase_complete makes percentage and completion date a biconditional, and the service reconciled
     * the pair in both directions unconditionally — with the date's rule running second, so it always won.
     * Once a phase had a completion date, every later save forced the percentage back to 100, and a phase
     * that slipped could not be recorded as having slipped.
     */
    @Test
    @DisplayName("a completed phase can be corrected back down, and the completion date goes with it")
    void percentageBelowOneHundredIsKept() {
        Long tenantId = tenantId();
        signInAsSeller(tenantId);
        var development = service.create(request("Phased Project", "Nairobi"));

        var finished = phaseService.create(development.id(),
                phase("Foundation", (short) 100, java.time.LocalDate.now()));
        assertEquals(100, finished.percentComplete());

        var corrected = phaseService.update(development.id(), finished.id(),
                phase("Foundation", (short) 60, java.time.LocalDate.now()));

        assertEquals(60, corrected.percentComplete(), "the percentage somebody typed is the one that counts");
        assertNull(corrected.actualCompletionOn(),
                "a phase at 60% has not been completed, and ck_phase_complete would refuse the pair");
    }

    @Test
    @DisplayName("reaching 100% stamps a completion date when none was given")
    void oneHundredStampsTheDate() {
        Long tenantId = tenantId();
        signInAsSeller(tenantId);
        var development = service.create(request("Phased Again", "Nairobi"));

        var created = phaseService.create(development.id(), phase("Roofing", (short) 100, null));

        assertEquals(100, created.percentComplete());
        assertNotNull(created.actualCompletionOn());
    }

    // ── one approval publishes the project, its cards and its units ───────────

    /**
     * What used to take four round trips.
     *
     * <p>A project with two typologies needed one DEVELOPMENT/PUBLISH plus one PROPERTY/PUBLISH per card,
     * and the cards had to be created by hand first — so a project could pass every gate, go live, and show
     * nothing on Browse, because Browse shows the cards. Submitting drafts them; approving publishes them.
     */
    @Test
    @DisplayName("submitting drafts the marketplace cards, and one approval publishes everything")
    void oneApprovalPublishesTheProjectAndItsCards() {
        Long tenantId = tenantId();
        signInAsSeller(tenantId);
        var development = service.create(request("Palm Court", "Nairobi"));
        Long id = HashIdUtil.decodeId(development.id());

        unitTypes.save(DevelopmentUnitType.builder()
                .developmentId(id).reference(RrnGenerator.generate("UT"))
                .code("2BED").name("Two bedroom").propertyType("APARTMENT")
                .listPrice(new java.math.BigDecimal("9500000")).build());
        unitTypes.save(DevelopmentUnitType.builder()
                .developmentId(id).reference(RrnGenerator.generate("UT"))
                .code("3BED").name("Three bedroom").propertyType("APARTMENT")
                .listPrice(new java.math.BigDecimal("14000000")).build());

        // No selling organisation was ever named, and that used to be the refusal.
        service.submit(development.id(), null);

        var cards = properties.findTypologiesForDevelopment(id);
        assertEquals(2, cards.size(), "submitting drafts a card for each priced typology");
        assertTrue(cards.stream().allMatch(c -> AppConstant.LISTING_DRAFT.equals(c.getListingState())));

        service.applyPublication(id);

        assertTrue(properties.findTypologiesForDevelopment(id).stream()
                        .allMatch(c -> AppConstant.LISTING_LIVE.equals(c.getListingState())),
                "one approval, and the cards buyers see are live");
        assertEquals(AppConstant.LISTING_LIVE,
                developments.findById(id).orElseThrow().getListingState());
    }

    @Test
    @DisplayName("a tenant-owned project markets itself unless told otherwise")
    void theSellingOrganisationDefaults() {
        Long tenantId = tenantId();
        signInAsSeller(tenantId);

        var created = service.create(request("Self Marketed", "Nairobi"));

        assertEquals(tenantName(tenantId), created.sellingTenantName());
    }

    // ── the bank sees a price before buyers do ────────────────────────────────

    /**
     * A seller repricing a live project sends it back to the bank.
     *
     * <p>The seller sells through the bank, so a figure the bank has not seen must not be the figure on the
     * marketplace. The live page comes down while it waits, which is the point rather than a side effect:
     * leaving it up with the old price would prevent nothing.
     */
    @Test
    @DisplayName("repricing a typology on a live project sends it back for approval")
    void repricingALiveProjectNeedsTheBankAgain() {
        Long tenantId = tenantId();
        signInAsSeller(tenantId);
        var development = service.create(request("Repriced Heights", "Nairobi"));
        Long id = HashIdUtil.decodeId(development.id());

        var type = typeService.create(development.id(), new SaveUnitTypeRequest(
                "2BED", "Two bedroom", "Ninety-two square metres.", "APARTMENT",
                (short) 2, (short) 2, (short) 1, new java.math.BigDecimal("92"), null,
                new java.math.BigDecimal("9500000"), null, 40, 10, null));

        service.submit(development.id(), null);
        service.applyPublication(id);
        assertEquals(AppConstant.LISTING_LIVE, developments.findById(id).orElseThrow().getListingState());

        typeService.update(development.id(), type.id(), new SaveUnitTypeRequest(
                "2BED", "Two bedroom", "Ninety-two square metres.", "APARTMENT",
                (short) 2, (short) 2, (short) 1, new java.math.BigDecimal("92"), null,
                new java.math.BigDecimal("11000000"), null, 40, 10, null));

        assertEquals(AppConstant.LISTING_PENDING, developments.findById(id).orElseThrow().getListingState(),
                "a price the bank has not seen is not the price on the marketplace");
        assertTrue(properties.findTypologiesForDevelopment(id).stream()
                        .noneMatch(c -> AppConstant.LISTING_LIVE.equals(c.getListingState())),
                "the cards come down with the project, or Browse shows a card for a page that is gone");
    }

    @Test
    @DisplayName("editing something that is not the price leaves a live project alone")
    void aDescriptionEditDoesNotPullItDown() {
        Long tenantId = tenantId();
        signInAsSeller(tenantId);
        var development = service.create(request("Steady Heights", "Nairobi"));
        Long id = HashIdUtil.decodeId(development.id());

        var type = typeService.create(development.id(), new SaveUnitTypeRequest(
                "2BED", "Two bedroom", "First wording.", "APARTMENT",
                (short) 2, (short) 2, (short) 1, new java.math.BigDecimal("92"), null,
                new java.math.BigDecimal("9500000"), null, 40, 10, null));

        service.submit(development.id(), null);
        service.applyPublication(id);

        typeService.update(development.id(), type.id(), new SaveUnitTypeRequest(
                "2BED", "Two bedroom", "Better wording, same price.", "APARTMENT",
                (short) 2, (short) 2, (short) 1, new java.math.BigDecimal("92"), null,
                new java.math.BigDecimal("9500000"), null, 40, 10, null));

        assertEquals(AppConstant.LISTING_LIVE, developments.findById(id).orElseThrow().getListingState(),
                "re-approving over a typo would make the rule the thing people work around");
    }

    private SaveUnitTypeRequest typeSpec(String desc, short beds, String price) {
        return new SaveUnitTypeRequest("2BED", "Two bedroom", desc, "APARTMENT",
                beds, (short) 2, (short) 1, new java.math.BigDecimal("92"), null,
                new java.math.BigDecimal(price), null, 40, 10, null);
    }

    /** Takes a live project and returns its id, with one typology already on it. */
    private Long liveProjectWithAType(String name, java.util.concurrent.atomic.AtomicReference<String> typeId) {
        var development = service.create(request(name, "Nairobi"));
        Long id = HashIdUtil.decodeId(development.id());
        typeId.set(typeService.create(development.id(), typeSpec("Ninety-two square metres.",
                (short) 2, "9500000")).id());
        service.submit(development.id(), null);
        service.applyPublication(id);
        return id;
    }

    /**
     * A two-bed becoming a three-bed is the same kind of change as a price move.
     *
     * <p>The card a buyer decided on now describes a different home, so the bank looks again.
     */
    @Test
    @DisplayName("changing the bedrooms on a live typology sends it back for approval")
    void changingTheBedroomsNeedsTheBankAgain() {
        signInAsSeller(tenantId());
        var typeId = new java.util.concurrent.atomic.AtomicReference<String>();
        Long id = liveProjectWithAType("Bedroom Heights", typeId);

        typeService.update(HashIdUtil.encodeId(id), typeId.get(),
                typeSpec("Ninety-two square metres.", (short) 3, "9500000"));

        assertEquals(AppConstant.LISTING_PENDING, developments.findById(id).orElseThrow().getListingState(),
                "a two-bed that became a three-bed is a different home from the one buyers were shown");
    }

    /**
     * The checker is shown what changed last.
     *
     * <p>A seller editing twice before anybody looks has made one project stale, not two — and the earliest
     * reason is the least current description of what is wrong with it.
     */
    @Test
    @DisplayName("a second edit restates the waiting request rather than failing")
    void theLatestChangeIsTheOneTheCheckerSees() {
        signInAsSeller(tenantId());
        var typeId = new java.util.concurrent.atomic.AtomicReference<String>();
        Long id = liveProjectWithAType("Twice Edited", typeId);

        typeService.update(HashIdUtil.encodeId(id), typeId.get(),
                typeSpec("Ninety-two square metres.", (short) 2, "11000000"));
        // The second edit must not fail with "that is already waiting for a decision".
        typeService.update(HashIdUtil.encodeId(id), typeId.get(),
                typeSpec("Ninety-two square metres.", (short) 3, "11000000"));

        var waiting = approvals.pendingFor(AppConstant.APPROVAL_ENTITY_DEVELOPMENT, id,
                AppConstant.APPROVAL_ACTION_PUBLISH).orElseThrow();
        assertTrue(waiting.getSubmissionNote().contains("bedrooms"),
                "the note should describe the last change, not the first: " + waiting.getSubmissionNote());
    }

    /**
     * The super administrator's exemption is user creation, and user creation only.
     *
     * <p>A {@code SUPER_ADMIN} creating a staff account skips the queue, because on a fresh platform
     * there is nobody else who could ever approve it. That reasoning is about people; it does not extend
     * to a project going on the marketplace, where a second pair of eyes always exists and the whole
     * arrangement is that the bank provides it.
     *
     * <p>This is a regression test rather than a feature test. Nothing in {@code DevelopmentService}
     * knows what kind of user the caller is, and this exists so that stays true — the cheapest way to
     * widen the user-creation exemption by accident is to reach for the same condition here.
     */
    @Test
    @DisplayName("a super administrator still cannot approve the development they submitted")
    void superAdminGetsNoExemptionOnDevelopments() {
        // With DEVELOPMENTS_APPROVE, so what refuses them is the maker/checker rule itself rather than a
        // missing permission — the weaker refusal would pass this test while proving nothing.
        signInAsPlatformApprover();
        Long tenantId = tenantId();

        var created = service.create(ownedBy("Platform Submitted", AppConstant.DEV_OWNER_SELLER,
                HashIdUtil.encodeId(tenantId)));
        Long id = HashIdUtil.decodeId(created.id());
        // A project with nothing to buy cannot be submitted, so give it something first.
        typeService.create(created.id(), typeSpec("Ninety-two square metres.", (short) 2, "9500000"));
        service.submit(created.id(), null);

        var waiting = approvals.pendingFor(AppConstant.APPROVAL_ENTITY_DEVELOPMENT, id,
                AppConstant.APPROVAL_ACTION_PUBLISH).orElseThrow();
        assertEquals(AppConstant.APPROVAL_PENDING, waiting.getState(),
                "submitting must still raise a request, whoever the caller is");

        var refused = assertThrows(HodiException.class, () -> approvals.decide(
                HashIdUtil.encodeId(waiting.getId()),
                new com.hodi.modules.approvals.ApprovalService.DecisionRequest(
                        AppConstant.APPROVAL_APPROVED, null)));
        assertTrue(refused.getMessage().contains("You submitted this"),
                "expected the maker/checker refusal, got: " + refused.getMessage());
        assertFalse(developments.findById(id).orElseThrow().isLive(),
                "the project must not have reached the marketplace");
    }
}
