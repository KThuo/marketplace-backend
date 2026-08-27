package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.media.MediaAsset;
import com.hodi.modules.media.MediaAssetRepository;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.modules.properties.PropertyService;
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
    @DisplayName("a typology's listing is not a card of its own in search")
    void typologyListingIsNotInTheSearchList() {
        Property listing = typologyListing(AppConstant.LISTING_LIVE);

        var page = publicProperties.search(new PublicSearchRequest());
        assertTrue(page.getContent().stream()
                        .noneMatch(c -> listing.getReference().equals(c.reference())),
                "the project has one card, not one per typology");
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
    @DisplayName("the totals on the filter bar count the cards the page will show")
    void facetsCountWhatTheListShows() {
        long before = publicProperties.facets().liveCount();
        typologyListing(AppConstant.LISTING_LIVE);
        long after = publicProperties.facets().liveCount();

        assertEquals(before, after,
                "a count that included typologies could not be reconciled with the page");
    }
}
