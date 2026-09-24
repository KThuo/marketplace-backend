package com.hodi.modules.tours;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentRepository;
import com.hodi.modules.developments.DevelopmentTourService;
import com.hodi.modules.developments.DevelopmentUnitType;
import com.hodi.modules.developments.DevelopmentUnitTypeRepository;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.modules.properties.PropertyTourService;
import com.hodi.modules.tours.TourDtos.EditTourRequest;
import com.hodi.modules.tours.TourDtos.SaveTourRequest;
import com.hodi.modules.tours.TourDtos.TourResponse;
import com.hodi.modules.users.User;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.UserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
 * Walkthrough videos, through the services a seller's screens and a buyer's page call.
 *
 * <p>What is worth a database for: that a tour pasted on a typology card lands where its units can see it,
 * that a project's tour is inherited and cannot be deleted from one listing, that a draft's tours are as
 * invisible as the draft, and that YouTube's answer about a video is acted on — without the test needing
 * YouTube, which is what {@link FakeYouTube} is for.
 */
@SpringBootTest
@Transactional
class VirtualToursIT {

    /** Answers by id, so each test can pick the verdict it needs by the video it pastes. */
    @TestConfiguration
    static class FakeYouTube {
        @Bean @Primary
        VideoCheck videoCheck() {
            return videoId -> switch (videoId) {
                case "missingvid0" -> new VideoCheck.Result(VideoCheck.Verdict.MISSING, null);
                case "privatevid0" -> new VideoCheck.Result(VideoCheck.Verdict.NOT_EMBEDDABLE, null);
                case "offlinevid0" -> VideoCheck.Result.unknown();
                default -> new VideoCheck.Result(VideoCheck.Verdict.PLAYABLE, "Walkthrough " + videoId);
            };
        }
    }

    @Autowired PropertyTourService listingTours;
    @Autowired DevelopmentTourService projectTours;
    @Autowired PropertyRepository properties;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentUnitTypeRepository unitTypes;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private Property house;
    private Development development;
    private DevelopmentUnitType typology;
    private Property card;
    private Property unit;

    @BeforeEach
    void signInAndBuild() {
        tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        User user = User.builder().id(1L).username("tour-seller").password("x")
                .email("t@example.invalid").firstName("Tia").lastName("Tour")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenantId).tenantName("Test Seller")
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("PROPERTIES_MEDIA", "DEVELOPMENTS_MEDIA"), List.of(tenantId), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        house = properties.save(listing("A house in Karen", null, null, AppConstant.LISTING_DRAFT));

        development = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Tour Heights").developmentType("APARTMENT").county("Nairobi").town("Nairobi")
                .listingState(AppConstant.LISTING_DRAFT).build());
        typology = unitTypes.save(DevelopmentUnitType.builder()
                .reference(RrnGenerator.generate("UT")).developmentId(development.getId())
                .code("2B").name("Two bedroom").propertyType("APARTMENT").bedrooms((short) 2)
                .listPrice(new BigDecimal("9500000")).currency("KES").build());
        Property typologyCard = listing("Two bedroom at Tour Heights", development.getId(), typology.getId(),
                AppConstant.LISTING_DRAFT);
        typologyCard.setListingKind(AppConstant.LISTING_KIND_TYPOLOGY);
        card = properties.save(typologyCard);
        Property oneUnit = listing("Unit B-101", development.getId(), typology.getId(), AppConstant.LISTING_DRAFT);
        oneUnit.setListingKind(AppConstant.LISTING_KIND_UNIT);
        oneUnit.setUnitLabel("B-101");
        oneUnit.setSaleState(AppConstant.UNIT_AVAILABLE);
        unit = properties.save(oneUnit);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Property listing(String title, Long developmentId, Long unitTypeId, String state) {
        return Property.builder()
                .reference(RrnGenerator.generate("PR")).tenantId(tenantId).title(title)
                .propertyType("APARTMENT").listingType(AppConstant.LISTING_TYPE_SALE)
                .price(new BigDecimal("9500000")).currency("KES").bedrooms((short) 2)
                .county("Nairobi").town("Nairobi")
                .developmentId(developmentId).unitTypeId(unitTypeId)
                .listingState(state)
                .publishedAt(AppConstant.LISTING_LIVE.equals(state) ? OffsetDateTime.now() : null)
                .status(AppConstant.STATUS_ACTIVE).statusFlag(AppConstant.FLAG_ACTIVE)
                .build();
    }

    private static String id(Property p) { return HashIdUtil.encodeId(p.getId()); }
    private String id(Development d) { return HashIdUtil.encodeId(d.getId()); }

    private static SaveTourRequest paste(String url) { return new SaveTourRequest(url, null, null); }

    // ── a house ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a pasted link is kept as its id, its timestamp and YouTube's title, with the rooms parsed")
    void aHouseOwnsItsTour() {
        TourResponse added = listingTours.add(id(house), new SaveTourRequest(
                "https://youtu.be/houseTour01?t=1m5s&si=share", null, "0:00 Gate\n1:12 Kitchen"));

        assertEquals("houseTour01", added.videoId());
        assertEquals(65, added.startSeconds());
        assertEquals("Walkthrough houseTour01", added.title(), "YouTube's title, when the seller gave none");
        assertEquals(List.of(new TourChapters.Chapter(0, "Gate"), new TourChapters.Chapter(72, "Kitchen")),
                added.chapters());
        assertEquals(VirtualTourService.SOURCE_OWN, added.source());
        assertEquals("https://i.ytimg.com/vi/houseTour01/hqdefault.jpg", added.thumbnailUrl(),
                "built from the id on a fixed host, never from what was pasted");
        assertNull(added.unverified());

        String stored = jdbc.queryForObject("select video_id from virtual_tours where id = ?", String.class,
                HashIdUtil.decodeId(added.id()));
        assertEquals("houseTour01", stored, "the column holds the id, not the link");
        assertEquals(1, listingTours.list(id(house)).size());
    }

    @Test
    @DisplayName("a video that does not exist, or will not play embedded, is refused at paste time")
    void youTubesAnswerIsActedOn() {
        assertTrue(assertThrows(HodiException.class, () -> listingTours.add(id(house), paste("missingvid0")))
                .getMessage().contains("no video"));
        assertTrue(assertThrows(HodiException.class, () -> listingTours.add(id(house), paste("privatevid0")))
                .getMessage().contains("private"));
        assertTrue(listingTours.list(id(house)).isEmpty());
    }

    @Test
    @DisplayName("but YouTube being unreachable saves the tour, untitled and marked, rather than blaming the seller")
    void unreachableFailsOpen() {
        TourResponse added = listingTours.add(id(house), paste("offlinevid0"));
        assertEquals(Boolean.TRUE, added.unverified());
        assertNull(added.title());
    }

    @Test
    @DisplayName("the same video twice is refused, and six is the most one listing carries")
    void duplicatesAndTheCap() {
        listingTours.add(id(house), paste("https://www.youtube.com/watch?v=aaaaaaaaaa1"));
        HodiException twice = assertThrows(HodiException.class,
                () -> listingTours.add(id(house), paste("https://youtu.be/aaaaaaaaaa1")));
        assertTrue(twice.getMessage().contains("already"), "another link shape is still the same video");

        for (int i = 2; i <= VirtualTourService.MAX_PER_OWNER; i++) {
            listingTours.add(id(house), paste("aaaaaaaaaa" + i));
        }
        HodiException full = assertThrows(HodiException.class, () -> listingTours.add(id(house), paste("aaaaaaaaaa9")));
        assertTrue(full.getMessage().contains("most tours"));
    }

    @Test
    @DisplayName("tours can be reordered, edited and removed, and the order closes up after a removal")
    void editingTheList() {
        TourResponse first = listingTours.add(id(house), paste("bbbbbbbbbb1"));
        listingTours.add(id(house), paste("bbbbbbbbbb2"));
        TourResponse third = listingTours.add(id(house), paste("bbbbbbbbbb3"));

        List<TourResponse> moved = listingTours.move(id(house), third.id(), 0);
        assertEquals(List.of("bbbbbbbbbb3", "bbbbbbbbbb1", "bbbbbbbbbb2"),
                moved.stream().map(TourResponse::videoId).toList());

        TourResponse edited = listingTours.edit(id(house), first.id(),
                new EditTourRequest("The garden", "0:00 Lawn\n0:40 Borehole", 10));
        assertEquals("The garden", edited.title());
        assertEquals(10, edited.startSeconds());
        assertEquals("0:00 Lawn\n0:40 Borehole", edited.chaptersText());

        listingTours.remove(id(house), third.id());
        List<TourResponse> left = listingTours.list(id(house));
        assertEquals(List.of("bbbbbbbbbb1", "bbbbbbbbbb2"), left.stream().map(TourResponse::videoId).toList());
        assertEquals(List.of(0, 1), left.stream().map(TourResponse::sortOrder).toList());
    }

    // ── a development ────────────────────────────────────────────────────────

    @Test
    @DisplayName("a tour pasted on the typology card is the typology's, and every unit of that kind shows it")
    void theTypologyShares() {
        TourResponse added = listingTours.add(id(card), paste("showUnit001"));

        assertEquals(VirtualTourService.SOURCE_TYPOLOGY, added.source());
        assertEquals(List.of("showUnit001"), listingTours.list(id(unit)).stream().map(TourResponse::videoId).toList(),
                "the unit reads the typology's tour rather than an empty list of its own");
        assertEquals(1, projectTours.list(id(development), "UNIT_TYPE", HashIdUtil.encodeId(typology.getId())).size(),
                "and the unit-type editor sees the same row");
    }

    @Test
    @DisplayName("the project's flythrough is on every listing in it, and cannot be removed from one of them")
    void theProjectIsInherited() {
        TourResponse flythrough = projectTours.add(id(development), "DEVELOPMENT", null, paste("flyThrough1"));
        listingTours.add(id(card), paste("showUnit002"));

        List<TourResponse> onTheUnit = listingTours.list(id(unit));
        assertEquals(List.of("showUnit002", "flyThrough1"), onTheUnit.stream().map(TourResponse::videoId).toList(),
                "the specific home first, then the estate");
        assertEquals(VirtualTourService.SOURCE_DEVELOPMENT, onTheUnit.get(1).source());

        HodiException refused = assertThrows(HodiException.class,
                () -> listingTours.remove(id(unit), flythrough.id()));
        assertTrue(refused.getMessage().contains("belongs to the development"));
    }

    @Test
    @DisplayName("another development's typology is not found through this one")
    void aTypologyMustBelong() {
        Development other = developments.save(Development.builder()
                .reference(RrnGenerator.generate("DV")).tenantId(tenantId).sellingTenantId(tenantId)
                .name("Elsewhere").developmentType("APARTMENT").build());
        assertThrows(ResourceNotFoundException.class, () -> projectTours.add(id(other), "UNIT_TYPE",
                HashIdUtil.encodeId(typology.getId()), paste("strayTour01")));
        assertThrows(HodiException.class, () -> projectTours.add(id(development), "DEVELOPMENT_PHASE",
                null, paste("strayTour02")));
    }

    // ── the marketplace ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a draft's tours are as invisible as the draft; a live listing's are anybody's")
    void onlyLiveListingsArePublic() {
        listingTours.add(id(house), paste("publicTour1"));
        assertThrows(ResourceNotFoundException.class, () -> listingTours.publicFor(house.getReference()));

        house.setListingState(AppConstant.LISTING_LIVE);
        house.setPublishedAt(OffsetDateTime.now());
        properties.saveAndFlush(house);
        assertEquals(1, listingTours.publicFor(house.getReference()).size());
    }

    @Test
    @DisplayName("a page of cards learns which have a tour from any of the three places one can come from")
    void theCardFlag() {
        Property bare = properties.save(listing("No video here", null, null, AppConstant.LISTING_DRAFT));
        listingTours.add(id(house), paste("cardTour001"));
        projectTours.add(id(development), "DEVELOPMENT", null, paste("cardTour002"));

        Set<Long> toured = listingTours.withTours(List.of(house, bare, card, unit));
        assertEquals(Set.of(house.getId(), card.getId(), unit.getId()), toured,
                "the house by its own, the card and the unit by the project's");
    }
}
