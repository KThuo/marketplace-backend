package com.hodi.modules.properties;

import com.hodi.common.AppConstant;
import com.hodi.modules.developments.UnitFeatureRepository;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.properties.PropertyDtos.SavePropertyRequest;
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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saving a listing without changing its amenities.
 *
 * <p>Which is the commonest save there is, and it was a 500. {@code applyAmenities} deleted every row and
 * re-inserted the wanted set, and Hibernate orders inserts before deletes inside a transaction — so an
 * amenity the seller kept met its own predecessor and {@code uk_unit_feature_unit} refused it.
 *
 * <p>The interesting assertions here are not that the amenities save. They are that a kept amenity keeps its
 * original row, and that the second save succeeds at all.
 */
@SpringBootTest
@Transactional
class ListingAmenitiesIT {

    @Autowired PropertyService service;
    @Autowired PropertyRepository properties;
    @Autowired UnitFeatureRepository features;
    @Autowired JdbcTemplate jdbc;

    private String listingId;

    @BeforeEach
    void signInAndList() {
        Long tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        String tenantName = jdbc.queryForObject(
                "select name from tenants where id = ?", String.class, tenantId);

        User user = User.builder().id(1L).username("seller-amenities").password("x")
                .email("a@example.invalid").firstName("Ama").lastName("Seller")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenantId).tenantName(tenantName)
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("PROPERTIES_VIEW", "PROPERTIES_CREATE", "PROPERTIES_UPDATE"),
                List.of(tenantId), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        listingId = service.create(listing(List.of("BOREHOLE", "SOLAR", "CCTV"))).id();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private SavePropertyRequest listing(List<String> amenities) {
        return new SavePropertyRequest(
                "Four bedrooms at Sunset", "Four-bedroom apartment.", "APARTMENT", "SALE", null,
                new BigDecimal("22000000"), new BigDecimal("6000"), false,
                (short) 4, (short) 4, (short) 3, new BigDecimal("92"), null, null,
                "Nairobi", "Nairobi", "Kasarani", "Sunset Road",
                new BigDecimal("-1.231488"), new BigDecimal("36.903934"),
                false, null, null, false, true, true,
                null, null, null,
                amenities);
    }

    private List<String> codesOn(String hashId) {
        return features.findForUnit(HashIdUtil.decodeId(hashId)).stream()
                .map(com.hodi.modules.developments.UnitFeature::getFeatureCode)
                .sorted()
                .toList();
    }

    @Test
    @DisplayName("saving again with the same amenities does not collide with itself")
    void resavingTheSameSetSucceeds() {
        // The reported failure, exactly: the second save re-sent every code it already had.
        service.update(listingId, listing(List.of("BOREHOLE", "SOLAR", "CCTV")));

        assertEquals(List.of("BOREHOLE", "CCTV", "SOLAR"), codesOn(listingId));
    }

    @Test
    @DisplayName("an amenity that was kept keeps its original row")
    void keptAmenitiesAreNotRestamped() {
        var before = features.findForUnit(HashIdUtil.decodeId(listingId)).stream()
                .filter(f -> "SOLAR".equals(f.getFeatureCode()))
                .findFirst().orElseThrow();

        service.update(listingId, listing(List.of("SOLAR", "GYM")));

        var after = features.findForUnit(HashIdUtil.decodeId(listingId)).stream()
                .filter(f -> "SOLAR".equals(f.getFeatureCode()))
                .findFirst().orElseThrow();
        // Same row, not a replacement: re-inserting would restamp created_at with today and created_by
        // with whoever pressed save, losing when the seller actually chose it.
        assertEquals(before.getId(), after.getId());
        assertEquals(before.getCreatedAt(), after.getCreatedAt());
    }

    @Test
    @DisplayName("adding and removing in one save does both")
    void theDifferenceIsApplied() {
        service.update(listingId, listing(List.of("SOLAR", "GYM", "SWIMMING_POOL")));

        assertEquals(List.of("GYM", "SOLAR", "SWIMMING_POOL"), codesOn(listingId));
    }

    @Test
    @DisplayName("clearing them all leaves none")
    void emptyListRemovesEverything() {
        service.update(listingId, listing(List.of()));

        assertTrue(codesOn(listingId).isEmpty());
    }

    @Test
    @DisplayName("null leaves them alone, so a screen that does not edit amenities cannot wipe them")
    void nullIsNotAnInstruction() {
        service.update(listingId, listing(null));

        assertEquals(List.of("BOREHOLE", "CCTV", "SOLAR"), codesOn(listingId));
    }

    @Test
    @DisplayName("an amenity the platform does not have is refused by name")
    void unknownCodesAreRefused() {
        var refused = assertThrows(RuntimeException.class,
                () -> service.update(listingId, listing(List.of("SOLAR", "HELIPAD"))));
        assertTrue(refused.getMessage().contains("HELIPAD"), refused.getMessage());
        // And nothing was half-applied.
        assertEquals(List.of("BOREHOLE", "CCTV", "SOLAR"), codesOn(listingId));
    }

    @Test
    @DisplayName("the listing still saves")
    void theRestOfTheSaveIsUnaffected() {
        var updated = service.update(listingId, listing(List.of("SOLAR")));
        assertNotNull(updated.reference());
        assertEquals("Four bedrooms at Sunset", updated.title());
        assertTrue(properties.findById(HashIdUtil.decodeId(listingId)).isPresent());
    }
}
