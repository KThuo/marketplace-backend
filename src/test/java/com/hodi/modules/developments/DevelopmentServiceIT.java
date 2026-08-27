package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
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
    @Autowired DevelopmentPhaseRepository phases;
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
                AppConstant.DEV_PURPOSE_FOR_SALE, "Acacia Builders Ltd", null,
                "Nairobi", town, "Kilimani", "Off Argwings Kodhek", null, null,
                200, null, null, null, null, null, null);
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

    @Test
    @DisplayName("the platform cannot own a development, because somebody has to be building it")
    void platformCannotCreate() {
        User user = User.builder().id(9L).username("admin-test").password("x")
                .email("a@example.invalid").firstName("Ada").lastName("Admin")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(9L).userId(9L)
                .profileType(AppConstant.ACTOR_PLATFORM).userTypeCode("SUPER_ADMIN")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile, Set.of(), List.of(), true, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        HodiException thrown = assertThrows(HodiException.class,
                () -> service.create(request("Platform project", "Nairobi")));
        assertTrue(thrown.getMessage().contains("building or financing"));
    }

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
        service.submit(created.id(), null);

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
        service.submit(created.id(), null);

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
        service.submit(created.id(), null);
        service.applyPublication(id);

        assertThrows(HodiException.class, () -> service.archive(created.id()));
    }
}
