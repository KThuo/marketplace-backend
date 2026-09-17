package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.developments.DevelopmentUnitTypeDtos.SaveUnitTypeRequest;
import com.hodi.modules.media.MediaAsset;
import com.hodi.modules.media.MediaAssetRepository;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyDtos.SavePropertyRequest;
import com.hodi.modules.properties.PropertyMediaService;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.modules.properties.PropertyService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A typology and the card that sells it are one thing described twice.
 *
 * <p>The card is a {@code properties} row so that ten tables which key on a listing — enquiries, viewings,
 * offers, saved searches, promotions — can point at a kind of home in a development without learning about
 * developments. It is not a second description of that home, and every place where the two could disagree is
 * a place a buyer is told something the seller did not say.
 *
 * <p>Three things they share, and each was reported separately by somebody using the screens: the pictures,
 * the facts, and which way an edit travels.
 */
@SpringBootTest
@Transactional
class TypologyAndListingAreOneIT {

    @Autowired PropertyService propertyService;
    @Autowired PropertyMediaService propertyMedia;
    @Autowired DevelopmentUnitTypeService unitTypeService;
    @Autowired PropertyRepository properties;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired MediaAssetRepository mediaAssets;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private Development development;
    private DevelopmentUnitType typology;
    private Property listing;

    @BeforeEach
    void signInAndBuild() {
        tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        User user = User.builder().id(1L).username("one-thing-test").password("x")
                .email("o@example.invalid").firstName("Ola").lastName("One")
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
                .name("Highrise Apartments").developmentType("APARTMENT")
                .county("Nairobi").town("Nairobi")
                .listingState(AppConstant.LISTING_DRAFT).build());
        typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").description("Ninety-two square metres.")
                .propertyType("APARTMENT").bedrooms((short) 2).bathrooms((short) 2)
                .parkingSpaces((short) 1).floorAreaSqm(new BigDecimal("92.00"))
                .serviceCharge(new BigDecimal("8000"))
                .listPrice(new BigDecimal("9500000")).currency("KES").build());
        listing = properties.save(Property.builder()
                .listingKind(AppConstant.LISTING_KIND_TYPOLOGY)
                .reference(RrnGenerator.generate("PR")).tenantId(tenantId)
                .title("Two bedroom at Highrise Apartments")
                .description("Ninety-two square metres.")
                .propertyType("APARTMENT").listingType(AppConstant.LISTING_TYPE_SALE)
                .price(new BigDecimal("9500000")).currency("KES")
                .bedrooms((short) 2).bathrooms((short) 2).parkingSpaces((short) 1)
                .floorAreaSqm(new BigDecimal("92.00")).serviceCharge(new BigDecimal("8000"))
                .county("Nairobi").town("Nairobi")
                .developmentId(development.getId()).unitTypeId(typology.getId())
                .listingState(AppConstant.LISTING_DRAFT)
                .status(AppConstant.STATUS_ACTIVE).statusFlag(AppConstant.FLAG_ACTIVE)
                .build());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MediaAsset asset(String ownerType, Long ownerId, String kind, String name, boolean primary) {
        return mediaAssets.save(MediaAsset.builder()
                .ownerType(ownerType).ownerId(ownerId).tenantId(tenantId).mediaKind(kind)
                .storageKey("t" + tenantId + "/" + ownerType.toLowerCase() + "/2026/" + name)
                .primary(primary).publicVisible(true).build());
    }

    private SaveUnitTypeRequest typologyAs(String name, Short bedrooms, String description) {
        return new SaveUnitTypeRequest("2B", name, description, "APARTMENT", bedrooms,
                (short) 2, (short) 1, new BigDecimal("92.00"), null,
                new BigDecimal("9500000"), new BigDecimal("8000"), null, null, null);
    }

    private SavePropertyRequest listingAs(String title, Short bedrooms, String description) {
        return new SavePropertyRequest(title, description, "APARTMENT",
                AppConstant.LISTING_TYPE_SALE, null, null, new BigDecimal("9500000"),
                new BigDecimal("8000"), false, bedrooms, (short) 2, (short) 1,
                new BigDecimal("92.00"), null, null,
                "Nairobi", "Nairobi", null, null, null, null,
                false, null, null, false, false, false,
                null, null, null, null);
    }

    // ── the pictures ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("a photograph on the typology is the listing's photograph")
    void theGalleryIsShared() {
        mediaAssets.save(MediaAsset.builder()
                .ownerType(AppConstant.MEDIA_OWNER_UNIT_TYPE).ownerId(typology.getId())
                .tenantId(tenantId).mediaKind(AppConstant.MEDIA_KIND_PHOTO)
                .storageKey("t" + tenantId + "/unit-types/2026/one.jpg")
                .primary(true).publicVisible(true).build());

        assertEquals(1, propertyMedia.list(HashIdUtil.encodeId(listing.getId())).size(),
                "the listing reads the typology's gallery rather than an empty one of its own");
    }

    @Test
    @DisplayName("and the workspace card shows it, without the cached key being written first")
    void theCardResolvesTheSharedCover() {
        mediaAssets.save(MediaAsset.builder()
                .ownerType(AppConstant.MEDIA_OWNER_UNIT_TYPE).ownerId(typology.getId())
                .tenantId(tenantId).mediaKind(AppConstant.MEDIA_KIND_PHOTO)
                .storageKey("t" + tenantId + "/unit-types/2026/one.jpg")
                .primary(true).publicVisible(true).build());

        var card = propertyService.find(HashIdUtil.encodeId(listing.getId()));

        assertNotNull(card.primaryImageUrl(),
                "the listings screen showed a coverless card beside a typology showing four photographs");
        assertEquals(1, card.photoCount(),
                "and told the seller they had none");
    }

    @Test
    @DisplayName("the project's photographs and plans are on the listing too, beside the typology's")
    void theProjectsGalleryIsInherited() {
        asset(AppConstant.MEDIA_OWNER_UNIT_TYPE, typology.getId(),
                AppConstant.MEDIA_KIND_FLOOR_PLAN, "unit-plan.png", true);
        asset(AppConstant.MEDIA_OWNER_DEVELOPMENT, development.getId(),
                AppConstant.MEDIA_KIND_PHOTO, "site-one.jpg", true);
        asset(AppConstant.MEDIA_OWNER_DEVELOPMENT, development.getId(),
                AppConstant.MEDIA_KIND_SITE_PLAN, "masterplan.png", false);

        List<com.hodi.modules.properties.PropertyDtos.MediaResponse> gallery =
                propertyMedia.list(HashIdUtil.encodeId(listing.getId()));

        assertEquals(3, gallery.size(),
                "a typology holding one plan used to hide every photograph the project had");
        assertTrue(gallery.stream().anyMatch(m -> "DEVELOPMENT".equals(m.source())
                        && AppConstant.MEDIA_KIND_PHOTO.equals(m.mediaKind())),
                "the site photography is what a buyer sees, so it is what the seller edits against");
        assertTrue(gallery.stream().anyMatch(m -> "DEVELOPMENT".equals(m.source())
                        && AppConstant.MEDIA_KIND_SITE_PLAN.equals(m.mediaKind())),
                "and the masterplan with it");
        assertTrue(gallery.stream().anyMatch(m -> "TYPOLOGY".equals(m.source())),
                "the typology's own plan is still there");
    }

    @Test
    @DisplayName("but the project's file cannot be deleted from a listing form")
    void theProjectsFileIsNotTheListingsToRemove() {
        MediaAsset projects = asset(AppConstant.MEDIA_OWNER_DEVELOPMENT, development.getId(),
                AppConstant.MEDIA_KIND_PHOTO, "site-one.jpg", true);

        HodiException e = assertThrows(HodiException.class, () -> propertyMedia.remove(
                HashIdUtil.encodeId(listing.getId()), HashIdUtil.encodeId(projects.getId())));

        assertTrue(e.getMessage().contains("development"), e.getMessage());
        assertEquals(1, mediaAssets.findForOwner(
                        AppConstant.MEDIA_OWNER_DEVELOPMENT, development.getId()).size(),
                "one listing's bin icon must not empty the project's album");
    }

    @Test
    @DisplayName("platform staff see the gallery they are allowed to see the listing through")
    void platformStaffCanReadTheGallery() {
        asset(AppConstant.MEDIA_OWNER_UNIT_TYPE, typology.getId(),
                AppConstant.MEDIA_KIND_PHOTO, "show-unit.jpg", true);
        signInAsPlatformStaff();

        assertEquals(1, propertyMedia.list(HashIdUtil.encodeId(listing.getId())).size(),
                "the bank runs this marketplace; a listing it can open is not a gallery it cannot");
    }

    @Test
    @DisplayName("and a seller from another organisation still sees nothing")
    void anotherSellersGalleryIsNotReadable() {
        asset(AppConstant.MEDIA_OWNER_UNIT_TYPE, typology.getId(),
                AppConstant.MEDIA_KIND_PHOTO, "show-unit.jpg", true);
        signInAsAnotherSeller();

        assertThrows(ResourceNotFoundException.class,
                () -> propertyMedia.list(HashIdUtil.encodeId(listing.getId())),
                "opening up the read for the platform must not open it for everybody");
    }

    /** A seller with a tenant of their own, which is not this listing's. */
    private void signInAsAnotherSeller() {
        SecurityContextHolder.clearContext();
        Long otherTenant = tenantId + 9_000_000L;
        User user = User.builder().id(3L).username("other-seller-test").password("x")
                .email("x@example.invalid").firstName("Otto").lastName("Other")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(3L).userId(3L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(otherTenant).tenantName("Someone Else")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("PROPERTIES_UPDATE"), List.of(otherTenant), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    /** The bank's own staff: no tenant of their own, and unrestricted visibility. */
    private void signInAsPlatformStaff() {
        SecurityContextHolder.clearContext();
        User user = User.builder().id(2L).username("platform-test").password("x")
                .email("p@example.invalid").firstName("Pat").lastName("Platform")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(2L).userId(2L)
                .profileType(AppConstant.ACTOR_PLATFORM).userTypeCode("SUPER_ADMIN")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("PROPERTIES_UPDATE"), List.of(), true, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    // ── the facts ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("editing the typology changes what the listing says")
    void theTypologysEditReachesTheListing() {
        unitTypeService.update(HashIdUtil.encodeId(development.getId()),
                HashIdUtil.encodeId(typology.getId()),
                typologyAs("Three bedroom", (short) 3, "A hundred and eight square metres."));

        Property after = properties.findById(listing.getId()).orElseThrow();
        assertEquals((short) 3, after.getBedrooms(),
                "a two-bed became a three-bed; the card still advertises a two-bed");
        assertTrue(after.getTitle().contains("Three bedroom"),
                "the card is titled after the typology it sells: " + after.getTitle());
        assertEquals("A hundred and eight square metres.", after.getDescription());
    }

    @Test
    @DisplayName("a title somebody wrote themselves survives a rename")
    void aHandWrittenTitleIsNotRegenerated() {
        listing.setTitle("Corner two-beds, west facing");
        properties.save(listing);

        unitTypeService.update(HashIdUtil.encodeId(development.getId()),
                HashIdUtil.encodeId(typology.getId()),
                typologyAs("Three bedroom", (short) 3, "Ninety-two square metres."));

        Property after = properties.findById(listing.getId()).orElseThrow();
        assertEquals("Corner two-beds, west facing", after.getTitle(),
                "a rename in the project screens must not delete words written on the card");
        assertEquals((short) 3, after.getBedrooms(), "the facts still travel");
    }

    @Test
    @DisplayName("editing the listing changes what the typology says")
    void theListingsEditReachesTheTypology() {
        propertyService.update(HashIdUtil.encodeId(listing.getId()),
                listingAs("Three bedroom at Highrise Apartments", (short) 3,
                        "A hundred and eight square metres."));

        DevelopmentUnitType after = unitTypes.findById(typology.getId()).orElseThrow();
        assertEquals((short) 3, after.getBedrooms(),
                "the seller corrected the card; the project still plans a two-bed");
        assertEquals("A hundred and eight square metres.", after.getDescription());
    }

    @Test
    @DisplayName("an ordinary house has no typology to write back to, and is untouched")
    void aHouseIsLeftAlone() {
        Property house = properties.save(Property.builder()
                .listingKind(AppConstant.LISTING_KIND_HOUSE)
                .reference(RrnGenerator.generate("PR")).tenantId(tenantId)
                .title("Four bedroom in Karen").propertyType("HOUSE")
                .listingType(AppConstant.LISTING_TYPE_SALE)
                .price(new BigDecimal("28000000")).currency("KES").bedrooms((short) 4)
                .county("Nairobi").town("Nairobi")
                .listingState(AppConstant.LISTING_DRAFT)
                .status(AppConstant.STATUS_ACTIVE).statusFlag(AppConstant.FLAG_ACTIVE)
                .build());

        propertyService.update(HashIdUtil.encodeId(house.getId()),
                listingAs("Five bedroom in Karen", (short) 5, "With a cottage."));

        assertEquals((short) 5, properties.findById(house.getId()).orElseThrow().getBedrooms());
        assertEquals((short) 2, unitTypes.findById(typology.getId()).orElseThrow().getBedrooms(),
                "a house's edit is nobody else's");
    }
}
