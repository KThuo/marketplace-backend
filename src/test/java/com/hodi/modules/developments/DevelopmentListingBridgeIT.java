package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.media.MediaAsset;
import com.hodi.modules.media.MediaAssetRepository;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.modules.properties.PropertyService;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.modules.properties.PublicPropertyService;
import com.hodi.modules.properties.PropertyDtos.PublicSearchRequest;
import com.hodi.modules.profiles.UserProfile;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a development meets the listing table, which is where this feature was most likely to break quietly.
 *
 * <p>Two silent failures, both predicted and both now held by a test.
 *
 * <p>The publish gate counted {@code property_media} and nothing else, so submitting a typology whose
 * photographs belong to the development would be refused with "Add at least one photograph" while the screen
 * showed twelve. A refusal nobody can act on is worse than a crash, because it looks like the author's fault.
 *
 * <p>And the search list: a two-hundred-unit project shows as one development card, so its typologies must not
 * also appear as separate listings — while still being reachable by reference, because that is where a buyer
 * lands when they click one.
 */
@SpringBootTest
@Transactional
class DevelopmentListingBridgeIT {

    @Autowired PropertyService propertyService;
    @Autowired PublicPropertyService publicProperties;
    @Autowired PublicDevelopmentService publicDevelopments;
    @Autowired DevelopmentUnitRepository units;
    @Autowired PropertyRepository properties;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired MediaAssetRepository mediaAssets;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private Development development;
    private DevelopmentUnitType typology;

    @BeforeEach
    void signInAndBuild() {
        tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        User user = User.builder().id(1L).username("bridge-test").password("x")
                .email("b@example.invalid").firstName("Bea").lastName("Bridge")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenantId).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("PROPERTIES_SUBMIT", "PROPERTIES_UPDATE"), List.of(tenantId), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Highrise Apartments").developmentType("APARTMENT").town("Nairobi")
                .listingState(AppConstant.LISTING_LIVE).publishedAt(OffsetDateTime.now()).build());
        typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(new BigDecimal("9500000")).build());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** A listing standing for the typology, in whatever state the test needs. */
    private Property typologyListing(String state) {
        return properties.save(Property.builder()
                .listingKind("TYPOLOGY")
                .reference(RrnGenerator.generate("PR")).tenantId(tenantId)
                .title("Two bedroom at Highrise Apartments")
                .description("Ninety-two square metres over four blocks.")
                .propertyType("APARTMENT").listingType(AppConstant.LISTING_TYPE_SALE)
                .price(new BigDecimal("9500000")).bedrooms((short) 2)
                .county("Nairobi").town("Nairobi")
                .developmentId(development.getId()).unitTypeId(typology.getId())
                .listingState(state)
                .publishedAt(AppConstant.LISTING_LIVE.equals(state) ? OffsetDateTime.now() : null)
                .build());
    }

    private void planOn(String ownerType, Long ownerId) {
        mediaAssets.save(MediaAsset.builder()
                .ownerType(ownerType).ownerId(ownerId).tenantId(tenantId)
                .mediaKind(AppConstant.MEDIA_KIND_FLOOR_PLAN)
                .storageKey("t" + tenantId + "/unit-types/2026/plan.png")
                .primary(false).publicVisible(true).build());
    }

    private void photographOn(String ownerType, Long ownerId) {
        mediaAssets.save(MediaAsset.builder()
                .ownerType(ownerType).ownerId(ownerId).tenantId(tenantId)
                .mediaKind(AppConstant.MEDIA_KIND_PHOTO)
                .storageKey("t" + tenantId + "/developments/2026/x.jpg")
                .primary(true).publicVisible(true).build());
    }

    // ── the publish gate ─────────────────────────────────────────────────────

    @Test
    @DisplayName("a typology inherits the development's photographs at the publish gate")
    void inheritedPhotographsSatisfyTheGate() {
        Property listing = typologyListing(AppConstant.LISTING_DRAFT);
        photographOn(AppConstant.MEDIA_OWNER_DEVELOPMENT, development.getId());

        propertyService.submit(HashIdUtil.encodeId(listing.getId()), null);

        assertEquals(AppConstant.LISTING_PENDING,
                properties.findById(listing.getId()).orElseThrow().getListingState(),
                "the site photography is the typology's photography");
    }

    @Test
    @DisplayName("the typology's own photographs count too, without the project needing any")
    void typologysOwnPhotographsSatisfyTheGate() {
        Property listing = typologyListing(AppConstant.LISTING_DRAFT);
        photographOn(AppConstant.MEDIA_OWNER_UNIT_TYPE, typology.getId());

        propertyService.submit(HashIdUtil.encodeId(listing.getId()), null);
        assertEquals(AppConstant.LISTING_PENDING,
                properties.findById(listing.getId()).orElseThrow().getListingState());
    }

    @Test
    @DisplayName("with no photograph anywhere, the refusal says where to put one")
    void refusalNamesBothPlaces() {
        Property listing = typologyListing(AppConstant.LISTING_DRAFT);

        HodiException e = assertThrows(HodiException.class,
                () -> propertyService.submit(HashIdUtil.encodeId(listing.getId()), null));
        assertTrue(e.getMessage().contains("typology"), e.getMessage());
        assertTrue(e.getMessage().contains("development"),
                "an author looking at twelve pictures needs to be told which thing they belong to");
    }

    // ── the search list ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a typology is one row in search, standing for all its homes")
    void typologyListingIsOneRowInSearch() {
        typology.setUnitsTotal(30);
        typology.setUnitsAvailable(25);
        unitTypes.save(typology);
        Property listing = typologyListing(AppConstant.LISTING_LIVE);
        listing.setDevelopmentName(development.getName());
        listing.setDevelopmentReference(development.getReference());
        listing.setUnitTypeReference(typology.getReference());
        listing.setUnitsAvailable(25);
        listing.setUnitsTotal(30);
        properties.save(listing);

        var card = publicProperties.search(new PublicSearchRequest()).getContent().stream()
                .filter(c -> listing.getReference().equals(c.reference())).findFirst().orElseThrow();

        /*
         * The shape of the row: what it is, which project, what it starts at, how many are left. Thirty
         * identical homes are one result, not thirty — which is the duplication this exists to avoid — and
         * the count is what turns a group into something somebody can act on.
         */
        assertEquals("Highrise Apartments", card.developmentName());
        assertEquals(development.getReference(), card.developmentReference(),
                "the project's name is a link, so the card carries what the link needs");
        assertEquals(typology.getReference(), card.unitTypeReference(),
                "and the drill-down to the individual homes");
        assertEquals(25, card.unitsAvailable());
        assertEquals(30, card.unitsTotal());
    }

    @Test
    @DisplayName("an ordinary listing carries no group fields at all")
    void ordinaryListingHasNoGroupFields() {
        Property ordinary = properties.save(Property.builder()
                .reference(RrnGenerator.generate("PR")).tenantId(tenantId)
                .title("A resale house in Karen").description("Four bedrooms on half an acre.")
                .propertyType("HOUSE").listingType(AppConstant.LISTING_TYPE_SALE)
                .price(new BigDecimal("28000000")).bedrooms((short) 4)
                .county("Nairobi").town("Nairobi")
                .listingState(AppConstant.LISTING_LIVE).publishedAt(OffsetDateTime.now()).build());

        var card = publicProperties.search(new PublicSearchRequest()).getContent().stream()
                .filter(c -> ordinary.getReference().equals(c.reference())).findFirst().orElseThrow();

        /*
         * Null rather than 1. A house showing "1 remaining" reads as one that is nearly gone, and the card
         * uses the absence of these to decide whether it is rendering a group at all.
         */
        assertNull(card.unitsTotal());
        assertNull(card.unitsAvailable());
        assertNull(card.developmentName());
    }

    @Test
    @DisplayName("but it is still reachable by reference, because that is where a buyer lands")
    void typologyListingIsStillReachable() {
        Property listing = typologyListing(AppConstant.LISTING_LIVE);

        var detail = publicProperties.findByReference(listing.getReference());
        assertEquals(listing.getReference(), detail.reference(),
                "hidden from the list is not the same as withdrawn");
    }

    @Test
    @DisplayName("the page shows the project's photographs as well as the typology's own plan")
    void theDetailPageShowsEverythingItInherits() {
        /*
         * The reported symptom: pictures on Browse, none on the page you reach by clicking one.
         *
         * The card's cover and the page's gallery were resolved by two different rules. The cover walked
         * past an empty level to the next; the gallery took the first level that held anything at all — so
         * a typology carrying one floor plan and no photograph ended the search there, and the project's
         * site photography, which the card was happily showing, never reached the page.
         */
        Property listing = typologyListing(AppConstant.LISTING_LIVE);
        planOn(AppConstant.MEDIA_OWNER_UNIT_TYPE, typology.getId());
        photographOn(AppConstant.MEDIA_OWNER_DEVELOPMENT, development.getId());

        var detail = publicProperties.findByReference(listing.getReference());

        assertEquals(1, detail.imageUrls().size(),
                "the project's photography is this home's photography, and the card already said so");
        assertEquals(1, detail.floorPlanUrls().size(), "and the typology's plan is still there");
    }

    @Test
    @DisplayName("an ordinary listing is untouched by all of this")
    void ordinaryListingStillAppears() {
        Property ordinary = properties.save(Property.builder()
                .reference(RrnGenerator.generate("PR")).tenantId(tenantId)
                .title("A resale house in Karen").description("Four bedrooms on half an acre.")
                .propertyType("HOUSE").listingType(AppConstant.LISTING_TYPE_SALE)
                .price(new BigDecimal("28000000")).bedrooms((short) 4)
                .county("Nairobi").town("Nairobi")
                .listingState(AppConstant.LISTING_LIVE).publishedAt(OffsetDateTime.now()).build());

        var page = publicProperties.search(new PublicSearchRequest());
        assertTrue(page.getContent().stream()
                        .anyMatch(c -> ordinary.getReference().equals(c.reference())),
                "every listing that is not part of a development behaves exactly as it did");
    }

    @Test
    @DisplayName("the totals on the filter bar count the rows the page will show")
    void facetsCountWhatTheListShows() {
        long before = publicProperties.facets().liveCount();
        typologyListing(AppConstant.LISTING_LIVE);
        long after = publicProperties.facets().liveCount();

        /*
         * Counted now, because the list shows them now. The bug is a filter promising a count the page cannot
         * produce — it does not matter which way the two agree, only that they do.
         */
        assertEquals(before + 1, after,
                "a typology listing is a row in the results, so it is a row in the count");
    }

    // ── what a buyer may see of the units ────────────────────────────────────

    @Test
    @DisplayName("the public unit list shows what is left and what has gone, and no buyer")
    void publicUnitListShowsBothAndNoBuyer() {
        development.setListingState(AppConstant.LISTING_LIVE);
        development.setPublishedAt(OffsetDateTime.now());
        developments.save(development);

        units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("B-3-01").block("B").floorNo((short) 3)
                .price(new BigDecimal("9500000"))
                .saleState(AppConstant.UNIT_AVAILABLE)
                .constructionStatus(AppConstant.BUILD_PLANNED).build());
        units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("B-3-02").block("B").floorNo((short) 3)
                .saleState(AppConstant.UNIT_SOLD)
                .soldPrice(new BigDecimal("9750000")).soldAt(OffsetDateTime.now())
                .buyerName("Asha Mwangi").buyerPhone("+254712345678")
                .constructionStatus(AppConstant.BUILD_PLANNED).build());
        units.save(Property.builder()
                .listingKind("UNIT").propertyType("APARTMENT").title("Unit")
                .reference(RrnGenerator.generate("UN")).developmentId(development.getId())
                .unitTypeId(typology.getId()).unitLabel("B-3-03").block("B").floorNo((short) 3)
                .saleState(AppConstant.UNIT_RETAINED)
                .constructionStatus(AppConstant.BUILD_PLANNED).build());

        var list = publicDevelopments.unitsFor(development.getReference(), typology.getReference());
        assertEquals(3, list.size(), "sold units are shown — what has gone is half of what this is for");

        var available = list.stream().filter(u -> "AVAILABLE".equals(u.state())).toList();
        var taken = list.stream().filter(u -> "TAKEN".equals(u.state())).toList();
        assertEquals(1, available.size());
        assertEquals(1, taken.size());

        assertEquals("B-3-01", available.getFirst().unitLabel());
        assertEquals((short) 3, available.getFirst().floorNo());
        assertEquals(0, available.getFirst().price().compareTo(new BigDecimal("9500000")),
                "its own price, which is what a buyer is quoted");

        /*
         * The privacy boundary, and the reason this is a separate record rather than the internal one with
         * fields blanked: there is nowhere on PublicUnitAvailability to put a buyer's name, a phone number or
         * what they paid. A record with those fields nulled is one refactor away from populating them.
         *
         * The sold unit went for 9,750,000 and that figure is not on the wire either — what a neighbour paid
         * is not something the next buyer gets to negotiate against.
         */
        assertTrue(taken.getFirst().price() == null
                        || taken.getFirst().price().compareTo(new BigDecimal("9750000")) != 0,
                "the price somebody actually paid is not published");

        var retained = list.stream().filter(u -> "UNAVAILABLE".equals(u.state())).toList();
        assertEquals(1, retained.size(),
                "retained and not-for-sale collapse to one answer: publishing which is which tells a "
                        + "competitor how much stock the developer is holding back");
    }

    @Test
    @DisplayName("a typology reference from another project cannot be read through this development")
    void unitsAreScopedToTheirDevelopment() {
        development.setListingState(AppConstant.LISTING_LIVE);
        development.setPublishedAt(OffsetDateTime.now());
        developments.save(development);

        Development other = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Somewhere Else").developmentType("APARTMENT").build());
        DevelopmentUnitType elsewhere = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(other.getId())
                .code("1B").name("One bedroom").propertyType("APARTMENT").bedrooms((short) 1).build());

        assertThrows(ResourceNotFoundException.class,
                () -> publicDevelopments.unitsFor(development.getReference(), elsewhere.getReference()));
    }

    @Test
    @DisplayName("a search card carries the breakdown, so a buyer sees whether their kind is left")
    void cardCarriesTheTypeBreakdown() {
        development.setListingState(AppConstant.LISTING_LIVE);
        development.setPublishedAt(OffsetDateTime.now());
        developments.save(development);
        typology.setUnitsTotal(70);
        typology.setUnitsAvailable(5);
        unitTypes.save(typology);

        var page = publicDevelopments.search(new PublicDevelopmentService.PublicDevelopmentSearchRequest());
        var card = page.getContent().stream()
                .filter(c -> development.getReference().equals(c.reference())).findFirst().orElseThrow();

        assertEquals(1, card.unitTypeCounts().size());
        assertEquals("Two bedroom", card.unitTypeCounts().getFirst().name());
        assertEquals(5, card.unitTypeCounts().getFirst().unitsAvailable());
        assertEquals(70, card.unitTypeCounts().getFirst().unitsTotal());
        assertTrue(card.unitTypes().isEmpty(),
                "and the full typology detail stays off the card — twenty cards would be twenty queries");
    }
}
