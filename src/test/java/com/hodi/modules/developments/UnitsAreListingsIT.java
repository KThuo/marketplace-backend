package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.developments.DevelopmentDtos.SaveDevelopmentRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.GenerateUnitsRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.SellUnitRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.UnitListRequest;
import com.hodi.modules.developments.DevelopmentUnitTypeDtos.SaveUnitTypeRequest;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyDtos.PropertyListRequest;
import com.hodi.modules.properties.PropertyService;
import com.hodi.modules.properties.PublicPropertyService;
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

import static org.junit.jupiter.api.Assertions.*;

/**
 * A unit is a property.
 *
 * <p>The rule this checks is the one the whole change rests on: a home in a development is one row in
 * {@code properties}, and that row is what is booked, paid for and sold. So generating units writes listings;
 * publishing the project publishes them; selling one marks one row SOLD; and the listing screens neither list
 * nor edit them, because the inventory does.
 */
@SpringBootTest
@Transactional
class UnitsAreListingsIT {

    @Autowired DevelopmentService developmentService;
    @Autowired DevelopmentUnitTypeService typeService;
    @Autowired DevelopmentUnitService units;
    @Autowired DevelopmentUnitRepository unitRows;
    @Autowired DevelopmentRepository developments;
    @Autowired DevelopmentInventoryService inventory;
    @Autowired PropertyService properties;
    @Autowired PublicPropertyService publicProperties;
    @Autowired JdbcTemplate jdbc;

    private Long tenantId;
    private String developmentId;
    private String typeId;

    @BeforeEach
    void signInAndBuild() {
        tenantId = jdbc.queryForObject("select id from tenants where status <> 5 order by id limit 1", Long.class);
        String tenantName = jdbc.queryForObject("select name from tenants where id = ?", String.class, tenantId);
        User user = User.builder().id(1L).username("seller-rows").password("x").email("r@example.invalid")
                .firstName("Rowan").lastName("Rows").status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L).profileType(AppConstant.ACTOR_SELLER)
                .userTypeCode("SELLER_OWNER").tenantId(tenantId).tenantName(tenantName)
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile,
                Set.of("PROPERTIES_VIEW", "PROPERTIES_UPDATE", "PROPERTIES_WITHDRAW"), List.of(tenantId), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        developmentId = developmentService.create(new SaveDevelopmentRequest(
                "Rowhouse Gardens", "Twelve homes.", "APARTMENT", AppConstant.DEV_PURPOSE_FOR_SALE,
                "Acacia Builders", null, "Nairobi", "Nairobi", "Kilimani", null, null, null,
                12, null, null, null, null, null, null)).id();
        typeId = typeService.create(developmentId, new SaveUnitTypeRequest(
                "3BED", "Three bedroom", "A hundred and ten square metres.", "APARTMENT",
                (short) 3, (short) 2, (short) 1, new BigDecimal("110"), null,
                new BigDecimal("14500000"), new BigDecimal("15000"), 12, 10)).id();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private List<Property> rows() {
        Long id = HashIdUtil.decodeId(developmentId);
        return unitRows.findAll().stream()
                .filter(p -> id.equals(p.getDevelopmentId()) && p.isUnit()).toList();
    }

    @Test
    @DisplayName("generating units writes property rows that read like listings")
    void generatedUnitsAreProperties() {
        units.generate(developmentId, new GenerateUnitsRequest(typeId, null, 3, "A", (short) 1, (short) 3, null, null));

        List<Property> rows = rows();
        assertEquals(3, rows.size());
        Property first = rows.getFirst();
        assertEquals(AppConstant.LISTING_KIND_UNIT, first.getListingKind());
        assertEquals("Rowhouse Gardens · " + first.getUnitLabel(), first.getTitle());
        assertEquals("APARTMENT", first.getPropertyType());
        assertEquals(tenantId, first.getTenantId(), "the row belongs to the selling organisation");
        assertEquals("Kilimani", first.getEstate());
        assertEquals(AppConstant.LISTING_DRAFT, first.getListingState(), "a draft project's units are drafts");
        assertEquals(AppConstant.UNIT_AVAILABLE, first.getSaleState());
        assertNull(first.getPrice(), "no price of its own means its typology's");
        assertEquals(new BigDecimal("14500000"), first.effectivePrice(new BigDecimal("14500000")));
    }

    @Test
    @DisplayName("the project's state and name reach every unit row; a sold one stays sold")
    void unitRowsFollowTheProject() {
        units.generate(developmentId, new GenerateUnitsRequest(typeId, null, 2, "B", (short) 1, (short) 2, null,
                new BigDecimal("15000000")));
        Development development = developments.findById(HashIdUtil.decodeId(developmentId)).orElseThrow();

        // Renaming the project renames its homes.
        development.setName("Rowhouse Gardens Phase 1");
        developments.save(development);
        inventory.syncUnitRows(development);
        assertTrue(rows().stream().allMatch(p -> p.getTitle().startsWith("Rowhouse Gardens Phase 1 · ")));

        // Going live puts every unit on the marketplace; selling one takes it off as SOLD.
        development.setListingState(AppConstant.LISTING_LIVE);
        development.setPublishedAt(java.time.OffsetDateTime.now());
        developments.save(development);
        inventory.syncUnitRows(development);
        assertTrue(rows().stream().allMatch(p -> AppConstant.LISTING_LIVE.equals(p.getListingState())));

        Property sold = rows().getFirst();
        units.sell(developmentId, HashIdUtil.encodeId(sold.getId()),
                new SellUnitRequest("Ada Buyer", "+254700000001", null, null, null));
        Property after = unitRows.findById(sold.getId()).orElseThrow();
        assertEquals(AppConstant.UNIT_SOLD, after.getSaleState());
        assertEquals(AppConstant.LISTING_SOLD, after.getListingState(), "one row says sold, in both columns");
        assertEquals(new BigDecimal("15000000.00"), after.getSoldPrice());

        // Taking the project down does not un-sell a home.
        development.setListingState(AppConstant.LISTING_WITHDRAWN);
        developments.save(development);
        inventory.syncUnitRows(development);
        assertEquals(AppConstant.LISTING_SOLD, unitRows.findById(sold.getId()).orElseThrow().getListingState());
        assertTrue(rows().stream().filter(p -> !p.getId().equals(sold.getId()))
                .allMatch(p -> AppConstant.LISTING_WITHDRAWN.equals(p.getListingState())));
    }

    @Test
    @DisplayName("a unit is read through the public listing endpoint, with its own detail beside the listing's")
    void unitIsReadAsAListing() {
        units.generate(developmentId, new GenerateUnitsRequest(typeId, null, 1, "D", (short) 2, (short) 1, null,
                new BigDecimal("15500000")));
        Development development = developments.findById(HashIdUtil.decodeId(developmentId)).orElseThrow();
        development.setListingState(AppConstant.LISTING_LIVE);
        development.setPublishedAt(java.time.OffsetDateTime.now());
        developments.save(development);
        inventory.syncUnitRows(development);
        Property unit = rows().getFirst();

        var listing = publicProperties.findByReference(unit.getReference());
        assertEquals(unit.getReference(), listing.reference(), "the same reference a lead or a payment quotes");
        assertNotNull(listing.unit(), "a unit's page reads the listing endpoint and finds its own block");
        assertEquals(unit.getUnitLabel(), listing.unit().unitLabel());
        assertEquals("AVAILABLE", listing.unit().state());
        assertEquals(0, new BigDecimal("15500000").compareTo(listing.unit().price()));
        assertEquals("Rowhouse Gardens", listing.unit().developmentName());
        assertEquals((short) 3, listing.unit().bedrooms(), "inherited from its kind");

        // A house has no unit block, and a unit of a project that is not live is not found.
        development.setListingState(AppConstant.LISTING_WITHDRAWN);
        developments.save(development);
        inventory.syncUnitRows(development);
        assertThrows(com.hodi.common.exception.ResourceNotFoundException.class,
                () -> publicProperties.findByReference(unit.getReference()));
    }

    @Test
    @DisplayName("the listing screens neither list nor edit a unit row")
    void listingScreensLeaveUnitsToTheInventory() {
        units.generate(developmentId, new GenerateUnitsRequest(typeId, null, 2, "C", (short) 1, (short) 2, null, null));
        Property unit = rows().getFirst();

        PropertyListRequest all = new PropertyListRequest();
        all.setSize(200);
        assertTrue(properties.list(all).getContent().stream()
                .noneMatch(r -> r.id().equals(HashIdUtil.encodeId(unit.getId()))),
                "two hundred homes in the listing list would bury the listings");

        HodiException refused = assertThrows(HodiException.class,
                () -> properties.withdraw(HashIdUtil.encodeId(unit.getId()), "not from here"));
        assertTrue(refused.getMessage().contains("inventory"), refused.getMessage());

        // The inventory screen sees exactly the two.
        assertEquals(2, units.list(developmentId, new UnitListRequest()).getTotalElements());
    }
}
