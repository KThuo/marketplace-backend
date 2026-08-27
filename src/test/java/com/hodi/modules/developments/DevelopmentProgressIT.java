package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.developments.DevelopmentProgressService.SaveDevelopmentUpdateRequest;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.properties.ProgressUpdateRepository;
import com.hodi.modules.properties.ProgressUpdateService;
import com.hodi.modules.properties.ProgressUpdateService.SaveUpdateRequest;
import com.hodi.modules.properties.ProgressUpdateService.UpdateResponse;
import com.hodi.modules.users.User;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Progress on a development, and the one rule in it that matters most.
 *
 * <p>A bank financing a developer's rental block tracks the build for its own reasons and has no business
 * publishing it. Getting that wrong discloses a client's project, so the refusal is tested from both sides:
 * that a private project cannot publish, and that it can still withdraw something published before it went
 * private — a rule blocking both directions would trap it.
 *
 * <p>Also here: the NullPointerException that {@code property_id} becoming nullable would otherwise have put on
 * every development-scoped update, and the feed's exclusion of projects nobody is selling.
 */
@SpringBootTest
@Transactional
class DevelopmentProgressIT {

    @Autowired DevelopmentProgressService progress;
    @Autowired ProgressUpdateService listingProgress;
    @Autowired PublicDevelopmentService publicService;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentPhaseRepository phases;
    @Autowired ProgressUpdateRepository updates;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private Development live;
    private Development tracked;

    @BeforeEach
    void signInAndBuild() {
        tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        signIn(tenantId);

        live = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Open Gardens").developmentType("APARTMENT")
                // ck_dev_published: LIVE means it went out at a moment, and the moment is not optional.
                .listingState(AppConstant.LISTING_LIVE).publishedAt(java.time.OffsetDateTime.now()).build());
        tracked = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId)
                .name("Borrower's Rental Block").developmentType("APARTMENT")
                .listingState(AppConstant.DEV_STATE_PRIVATE).build());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void signIn(Long tenant) {
        User user = User.builder().id(1L).username("progress-test").password("x")
                .email("p@example.invalid").firstName("Pat").lastName("Progress")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenant).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("DEVELOPMENTS_PROGRESS", "DEVELOPMENTS_VIEW"), List.of(tenant), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private String id(Development d) {
        return HashIdUtil.encodeId(d.getId());
    }

    private SaveDevelopmentUpdateRequest post(String title, String milestoneCode) {
        return new SaveDevelopmentUpdateRequest(
                new SaveUpdateRequest(title, "The slab went down on Tuesday.", (short) 25, null,
                        LocalDate.now()),
                milestoneCode, null, null);
    }

    // ── the write path ────────────────────────────────────────────────────────

    @Test
    @DisplayName("an update names its development, caches its owner, and starts as a draft")
    void createStartsAsDraft() {
        UpdateResponse saved = progress.create(id(live), post("Slab cast", "SLAB"));

        assertNotNull(saved.id());
        assertFalse(saved.published(), "a draft, because three updates written on a Friday are not all ready");
        assertNull(saved.publishedAt());

        var row = updates.findById(HashIdUtil.decodeId(saved.id())).orElseThrow();
        assertEquals(live.getId(), row.getDevelopmentId());
        assertNull(row.getPropertyId(), "one subject, and this one is not a listing");
        assertEquals(tenantId, row.getTenantId(), "the owner is cached from the development");
        assertEquals("SLAB", row.getMilestoneCode());
    }

    @Test
    @DisplayName("picking a stage fills the wording, so a timeline reads as words rather than codes")
    void milestoneCodeSuppliesItsName() {
        UpdateResponse saved = progress.create(id(live), post("Stage reached", "ROOFING"));
        assertEquals("Roofing", saved.milestone());
    }

    @Test
    @DisplayName("wording the reporter typed is never overwritten by the stage they picked")
    void typedWordingWins() {
        UpdateResponse saved = progress.create(id(live), new SaveDevelopmentUpdateRequest(
                new SaveUpdateRequest("Stage reached", null, (short) 65, "Roof sheets on, ridge to follow",
                        LocalDate.now()),
                "ROOFING", null, null));
        assertEquals("Roof sheets on, ridge to follow", saved.milestone(),
                "the site's own words survive the picker");
    }

    @Test
    @DisplayName("a stage nobody has heard of is refused here, not by a foreign key")
    void unknownMilestoneRefused() {
        HodiException e = assertThrows(HodiException.class,
                () -> progress.create(id(live), post("Mystery stage", "TOPPING_OUT")));
        assertTrue(e.getMessage().contains("build stages"), e.getMessage());
    }

    @Test
    @DisplayName("a phase belonging to another development cannot be named in the request")
    void phaseFromAnotherDevelopmentRefused() {
        DevelopmentPhase elsewhere = phases.save(DevelopmentPhase.builder()
                .reference(RrnGenerator.generate("PH"))
                .developmentId(tracked.getId()).name("Phase 1").sequenceNo((short) 1).build());

        assertThrows(ResourceNotFoundException.class, () -> progress.create(id(live),
                new SaveDevelopmentUpdateRequest(
                        new SaveUpdateRequest("Block B roofed", null, (short) 65, null, LocalDate.now()),
                        null, HashIdUtil.encodeId(elsewhere.getId()), null)));
    }

    // ── the publish gate ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a project nobody is selling cannot publish its progress")
    void trackedProjectCannotPublish() {
        UpdateResponse draft = progress.create(id(tracked), post("Foundation poured", "FOUNDATION"));

        HodiException e = assertThrows(HodiException.class,
                () -> progress.setPublished(id(tracked), draft.id(), true));
        assertTrue(e.getMessage().contains("private"), e.getMessage());
        assertFalse(updates.findById(HashIdUtil.decodeId(draft.id())).orElseThrow().isPublished());
    }

    @Test
    @DisplayName("withdrawing is never refused, so a project that turned private is not trapped")
    void withdrawalIsAlwaysAllowed() {
        UpdateResponse published = progress.create(id(live), post("Slab cast", "SLAB"));
        progress.setPublished(id(live), published.id(), true);

        live.setListingState(AppConstant.DEV_STATE_PRIVATE);
        developments.save(live);

        UpdateResponse pulled = progress.setPublished(id(live), published.id(), false);
        assertFalse(pulled.published(), "it can still come back down");
    }

    @Test
    @DisplayName("publishing stamps the moment and keeps it when the post comes back down")
    void publishedAtSurvivesWithdrawal() {
        UpdateResponse saved = progress.create(id(live), post("Slab cast", "SLAB"));
        UpdateResponse up = progress.setPublished(id(live), saved.id(), true);
        assertNotNull(up.publishedAt());

        UpdateResponse down = progress.setPublished(id(live), saved.id(), false);
        assertEquals(up.publishedAt(), down.publishedAt(), "when it went out is still worth knowing");
    }

    @Test
    @DisplayName("an archived post stops serving from the public timeline")
    void archivedPostLeavesThePublicTimeline() {
        UpdateResponse saved = progress.create(id(live), post("Slab cast", "SLAB"));
        progress.setPublished(id(live), saved.id(), true);
        assertEquals(1, progress.published(live.getReference()).size());

        progress.archive(id(live), saved.id());
        assertEquals(0, progress.published(live.getReference()).size(),
                "archived and unpublished together, or a removed post keeps being served");
    }

    // ── the public reads ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a private project's timeline is not found at all, rather than found and empty")
    void privateProjectHasNoPublicTimeline() {
        assertThrows(ResourceNotFoundException.class, () -> progress.published(tracked.getReference()));
    }

    @Test
    @DisplayName("drafts stay off the public timeline")
    void draftsAreNotPublic() {
        progress.create(id(live), post("Not ready yet", "SLAB"));
        assertEquals(0, progress.published(live.getReference()).size());
    }

    @Test
    @DisplayName("the cross-project feed carries the project's identity and excludes tracked projects")
    void feedNamesItsProjectAndSkipsPrivateOnes() {
        UpdateResponse shown = progress.create(id(live), post("Slab cast", "SLAB"));
        progress.setPublished(id(live), shown.id(), true);

        // Published on the private project by going round the gate, which is what a stale row would look like
        // if a project were published, posted to, and then made private.
        UpdateResponse hidden = progress.create(id(tracked), post("Foundation poured", "FOUNDATION"));
        jdbc.update("update listing_progress_updates set published = true, published_at = now() where id = ?",
                HashIdUtil.decodeId(hidden.id()));

        var feed = publicService.progressFeed(new PagedDataRequest());
        var mine = feed.getContent().stream()
                .filter(i -> live.getReference().equals(i.developmentReference())).toList();

        assertEquals(1, mine.size(), "the live project's update is in the feed");
        assertEquals("Open Gardens", mine.getFirst().developmentName(),
                "with the project's name, so the card can link somewhere");
        assertTrue(feed.getContent().stream()
                        .noneMatch(i -> tracked.getReference().equals(i.developmentReference())),
                "and a project nobody is selling is not in it, even with a published row");
    }

    // ── the two services do not answer for each other ────────────────────────

    @Test
    @DisplayName("a development's update handed to the listing service is not found, not a crash")
    void listingServiceDoesNotThrowOnADevelopmentUpdate() {
        UpdateResponse saved = progress.create(id(live), post("Slab cast", "SLAB"));
        Long propertyId = jdbc.queryForObject(
                "select id from properties where tenant_id = ? and status <> 5 order by id limit 1",
                Long.class, tenantId);

        /*
         * The regression this exists for: `update.getPropertyId().equals(...)` threw a NullPointerException
         * once property_id became nullable — on a path reached by an id a caller supplies. A 500 for a
         * malformed request, on every development update, for as long as nobody tried it.
         */
        assertThrows(ResourceNotFoundException.class, () -> listingProgress.update(
                HashIdUtil.encodeId(propertyId), saved.id(),
                new SaveUpdateRequest("Hijacked", null, (short) 10, null, LocalDate.now())));
    }
}
