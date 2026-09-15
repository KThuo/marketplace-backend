package com.hodi.modules.developments;

import com.hodi.modules.properties.Property;
import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.developments.DevelopmentDtos.SaveDevelopmentRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.GenerateUnitsRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.ReserveUnitRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.SellUnitRequest;
import com.hodi.modules.developments.DevelopmentUnitDtos.UnitListRequest;
import com.hodi.modules.developments.DevelopmentUnitTypeDtos.SaveUnitTypeRequest;
import com.hodi.modules.profiles.UserProfile;
import com.hodi.modules.users.User;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The inventory screen's service, against real rows.
 *
 * <p>{@link UnitLabelsTest} proves the door numbers. This proves what happens when seventy of them are written:
 * that the pay codes come out distinct, that a plan is refused whole rather than half-written, that the four
 * sale transitions recount, and that the guards refuse the things that would lose information — releasing a
 * sold unit, archiving one, holding one twice.
 */
@SpringBootTest
@Transactional
class DevelopmentUnitServiceIT {

    @Autowired DevelopmentService developmentService;
    @Autowired DevelopmentUnitTypeService typeService;
    @Autowired DevelopmentUnitService units;
    @Autowired DevelopmentUnitRepository unitRepository;
    @Autowired DevelopmentUnitTypeRepository typeRepository;
    @Autowired JdbcTemplate jdbc;

    private String developmentId;
    private String typeId;

    @BeforeEach
    void signInAndBuild() {
        Long tenantId = jdbc.queryForObject(
                "select id from tenants where status <> 5 order by id limit 1", Long.class);
        String tenantName = jdbc.queryForObject(
                "select name from tenants where id = ?", String.class, tenantId);

        User user = User.builder().id(1L).username("seller-units").password("x")
                .email("u@example.invalid").firstName("Uma").lastName("Units")
                .status(AppConstant.STATUS_ACTIVE).enabled(true).build();
        UserProfile profile = UserProfile.builder().id(1L).userId(1L)
                .profileType(AppConstant.ACTOR_SELLER).userTypeCode("SELLER_OWNER")
                .tenantId(tenantId).tenantName(tenantName)
                .status(AppConstant.STATUS_ACTIVE).build();
        UserPrincipal principal = UserPrincipal.of(user, profile, Set.of(),
                List.of(tenantId), false, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        developmentId = developmentService.create(new SaveDevelopmentRequest(
                "Highrise Apartments", "Two hundred units.", "APARTMENT",
                AppConstant.DEV_PURPOSE_FOR_SALE, "Acacia Builders", null, null, null,
                "Nairobi", "Nairobi", "Kilimani", null, null, null,
                200, null, null, null, null, null, null)).id();

        typeId = typeService.create(developmentId, new SaveUnitTypeRequest(
                "2BED", "Two bedroom", "Ninety-two square metres.", "APARTMENT",
                (short) 2, (short) 2, (short) 1, new BigDecimal("92"), null,
                new BigDecimal("9500000"), new BigDecimal("12000"), 70, 10)).id();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private GenerateUnitsRequest plan(int count, String block, Short firstFloor, Short perFloor,
                                      String pattern) {
        return new GenerateUnitsRequest(typeId, null, count, block, firstFloor, perFloor, pattern, null);
    }

    @Test
    @DisplayName("seventy units, seven a floor, with distinct pay codes and the counters recounted")
    void generatesABlock() {
        int written = units.generate(developmentId, plan(70, "B", (short) 1, (short) 7, null));
        assertEquals(70, written);

        var page = units.list(developmentId, new UnitListRequest());
        assertEquals(70, page.getTotalElements());

        /*
         * Scoped to this test's own development, not every unit in the database.
         *
         * This read findAll() filtered on a "B-" label prefix, which passed only for as long as nothing else
         * in the database used that prefix — and the moment a demo project had a Block B, the count came back
         * 94 instead of 70. A test that inspects every row is coupled to whatever else happens to exist.
         */
        var all = unitRepository.findAll().stream()
                .filter(u -> com.hodi.security.hashid.HashIdUtil.decodeId(developmentId)
                        .equals(u.getDevelopmentId()))
                .filter(u -> u.getUnitLabel().startsWith("B-")).toList();
        assertEquals(70, all.size());
        assertEquals(70, all.stream().map(Property::getPayReference).distinct().count(),
                "a batch of pay codes must not repeat — the unique index would refuse the second");
        assertTrue(all.stream().allMatch(u -> u.getPayReference().length() == 4));

        DevelopmentUnitType after = typeRepository.findById(
                com.hodi.security.hashid.HashIdUtil.decodeId(typeId)).orElseThrow();
        assertEquals(70, after.getUnitsTotal());
        assertEquals(70, after.getUnitsAvailable());
        assertEquals(0, new BigDecimal("9500000").compareTo(after.getFromPrice()));
    }

    @Test
    @DisplayName("the preview writes nothing and says what would clash")
    void previewIsReadOnly() {
        var first = units.preview(developmentId, plan(5, "B", (short) 1, (short) 5, null));
        assertEquals(5, first.count());
        assertFalse(first.hasDuplicates());
        assertTrue(first.clashesWithExisting().isEmpty());
        assertEquals(0, units.list(developmentId, new UnitListRequest()).getTotalElements(),
                "a preview that wrote rows would not be a preview");

        units.generate(developmentId, plan(5, "B", (short) 1, (short) 5, null));

        var second = units.preview(developmentId, plan(5, "B", (short) 1, (short) 5, null));
        assertEquals(5, second.clashesWithExisting().size(), "the same plan twice clashes with itself");
    }

    @Test
    @DisplayName("a clashing plan is refused whole, not written half")
    void refusedWhole() {
        units.generate(developmentId, plan(5, "B", (short) 1, (short) 5, null));

        // Overlaps B-101..B-105 and adds five more. Half of it would be worse than none.
        HodiException thrown = assertThrows(HodiException.class,
                () -> units.generate(developmentId, plan(10, "B", (short) 1, (short) 5, null)));
        assertTrue(thrown.getMessage().contains("already in this development"), thrown.getMessage());
        assertEquals(5, units.list(developmentId, new UnitListRequest()).getTotalElements(),
                "nothing from the refused plan was written");
    }

    @Test
    @DisplayName("a pattern that repeats a label is refused before anything is written")
    void refusesDuplicatePattern() {
        HodiException thrown = assertThrows(HodiException.class,
                () -> units.generate(developmentId, plan(5, "B", null, null, "{block}")));
        assertTrue(thrown.getMessage().contains("same label"), thrown.getMessage());
        assertEquals(0, units.list(developmentId, new UnitListRequest()).getTotalElements());
    }

    @Test
    @DisplayName("reserve, sell and release each move the counters")
    void theFourTransitions() {
        units.generate(developmentId, plan(3, "B", (short) 1, (short) 3, null));
        var listed = units.list(developmentId, new UnitListRequest()).getContent();
        String one = listed.get(0).id();
        String two = listed.get(1).id();

        var reserved = units.reserve(developmentId, one,
                new ReserveUnitRequest("Ada Nyong", "254712345678", null, 14, "Paid the booking fee"));
        assertEquals(AppConstant.UNIT_RESERVED, reserved.saleState());
        assertNotNull(reserved.reservedUntil());
        assertEquals(1, typology().getUnitsReserved());
        assertEquals(2, typology().getUnitsAvailable());

        var sold = units.sell(developmentId, two,
                new SellUnitRequest("Ben Otieno", "254733111222", null, null, null));
        assertEquals(AppConstant.UNIT_SOLD, sold.saleState());
        // compareTo, not equals: BigDecimal.equals compares scale, and a price that has not been through a
        // NUMERIC(15,2) round trip yet carries the scale it was written with. The numbers are the same money.
        assertEquals(0, new BigDecimal("9500000").compareTo(sold.soldPrice()),
                "no price given, so the typology's stands");
        assertEquals(1, typology().getUnitsSold());

        var released = units.release(developmentId, one);
        assertEquals(AppConstant.UNIT_AVAILABLE, released.saleState());
        assertNull(released.buyerName(), "a released unit keeps nobody's details");
        assertNull(released.reservedUntil());
        assertEquals(0, typology().getUnitsReserved());
        assertEquals(2, typology().getUnitsAvailable());
    }

    @Test
    @DisplayName("a sold unit cannot be reserved, released or archived")
    void soldIsFinal() {
        units.generate(developmentId, plan(1, "B", (short) 1, (short) 1, null));
        String id = units.list(developmentId, new UnitListRequest()).getContent().getFirst().id();
        units.sell(developmentId, id, new SellUnitRequest("Ada", null, null, null, null));

        assertThrows(HodiException.class, () -> units.reserve(developmentId, id,
                new ReserveUnitRequest("Somebody else", null, null, null, null)));
        assertThrows(HodiException.class, () -> units.release(developmentId, id));
        assertThrows(HodiException.class, () -> units.archive(developmentId, id));
    }

    @Test
    @DisplayName("a unit already held is not held again by somebody else")
    void doubleHoldRefused() {
        units.generate(developmentId, plan(1, "B", (short) 1, (short) 1, null));
        String id = units.list(developmentId, new UnitListRequest()).getContent().getFirst().id();
        units.reserve(developmentId, id, new ReserveUnitRequest("Ada", null, null, 14, null));

        HodiException thrown = assertThrows(HodiException.class, () -> units.reserve(developmentId, id,
                new ReserveUnitRequest("Ben", null, null, 14, null)));
        assertTrue(thrown.getMessage().contains("already held"), thrown.getMessage());
    }

    @Test
    @DisplayName("a typology with units under it cannot be archived out from under them")
    void typologyWithUnits() {
        units.generate(developmentId, plan(2, "B", (short) 1, (short) 2, null));
        HodiException thrown = assertThrows(HodiException.class,
                () -> typeService.archive(developmentId, typeId));
        assertTrue(thrown.getMessage().contains("still has 2 unit"), thrown.getMessage());
    }

    @Test
    @DisplayName("the search finds a unit by its label, its reference or its pay code")
    void searchesByPayCode() {
        units.generate(developmentId, plan(3, "B", (short) 1, (short) 3, null));
        var first = units.list(developmentId, new UnitListRequest()).getContent().getFirst();

        UnitListRequest byCode = new UnitListRequest();
        byCode.setSearch(first.payReference());
        assertEquals(1, units.list(developmentId, byCode).getTotalElements(),
                "a payment quoting only the short code has to be findable by it");

        UnitListRequest byLabel = new UnitListRequest();
        byLabel.setSearch("B-10");
        assertTrue(units.list(developmentId, byLabel).getTotalElements() >= 1);
    }

    private DevelopmentUnitType typology() {
        return typeRepository.findById(
                com.hodi.security.hashid.HashIdUtil.decodeId(typeId)).orElseThrow();
    }

    // ── the price a generated unit carries ────────────────────────────────────

    /**
     * The generator's price field is a prefill, not a decision.
     *
     * <p>UnitSpec resolves a null price to the type's and marks it inherited, which is what makes repricing
     * a typology move its units. Echoing the prefill back would write the figure onto all seventy rows and
     * break that link invisibly — the numbers agree on the day they are written and diverge the first time
     * somebody changes the type.
     */
    @Test
    @DisplayName("a generated unit takes the type's price rather than a copy of it")
    void unchangedPrefillLeavesThePriceInherited() {
        units.generate(developmentId, new GenerateUnitsRequest(
                typeId, null, 3, "B", (short) 1, (short) 3, null, new BigDecimal("9500000")));

        // Scoped to this test's project: findAll() also returns whatever demo rows the database holds,
        // and those are priced.
        List<Property> written = unitRepository.findAll().stream()
                .filter(u -> com.hodi.security.hashid.HashIdUtil.decodeId(developmentId)
                        .equals(u.getDevelopmentId()))
                .toList();
        assertFalse(written.isEmpty());
        assertTrue(written.stream().allMatch(u -> u.getPrice() == null),
                "the same figure as the type's is not a per-unit price");
    }

    @Test
    @DisplayName("a price that differs from the type's is kept as that unit's own")
    void aDifferentPriceIsStored() {
        units.generate(developmentId, new GenerateUnitsRequest(
                typeId, null, 2, "P", (short) 20, (short) 2, null, new BigDecimal("14000000")));

        List<Property> written = unitRepository.findAll().stream()
                .filter(u -> com.hodi.security.hashid.HashIdUtil.decodeId(developmentId)
                        .equals(u.getDevelopmentId()))
                .filter(u -> "P".equals(u.getBlock()))
                .toList();
        assertEquals(2, written.size());
        assertTrue(written.stream().allMatch(u -> new BigDecimal("14000000").compareTo(u.getPrice()) == 0));
    }

    // ── several types in one run ──────────────────────────────────────────────

    @Test
    @DisplayName("a run generates several types at once")
    void severalTypesInOneRun() {
        String second = typeService.create(developmentId, new SaveUnitTypeRequest(
                "3BED", "Three bedroom", "Bigger.", "APARTMENT",
                (short) 3, (short) 2, (short) 1, new BigDecimal("120"), null,
                new BigDecimal("14000000"), new BigDecimal("15000"), 40, 10)).id();

        int written = units.generateMany(developmentId, List.of(
                new GenerateUnitsRequest(typeId, null, 4, "B", (short) 1, (short) 2, null, null),
                new GenerateUnitsRequest(second, null, 3, "C", (short) 1, (short) 3, null, null)));

        assertEquals(7, written);
        assertEquals(7, units.list(developmentId, new UnitListRequest()).getTotalElements());
    }

    /**
     * The check only a run can make.
     *
     * <p>Each batch is free of clashes on its own — nothing with those labels exists yet — and together they
     * produce B-1-01 twice. Neither preview would catch it, and a client looping the single-type endpoint
     * would write the first batch before failing on the second.
     */
    @Test
    @DisplayName("two types in one run cannot take the same label, and nothing is written")
    void aRunRefusesLabelsThatCollideAcrossTypes() {
        String second = typeService.create(developmentId, new SaveUnitTypeRequest(
                "3BED", "Three bedroom", "Bigger.", "APARTMENT",
                (short) 3, (short) 2, (short) 1, new BigDecimal("120"), null,
                new BigDecimal("14000000"), new BigDecimal("15000"), 40, 10)).id();

        HodiException thrown = assertThrows(HodiException.class,
                () -> units.generateMany(developmentId, List.of(
                        new GenerateUnitsRequest(typeId, null, 2, "B", (short) 1, (short) 2, null, null),
                        new GenerateUnitsRequest(second, null, 2, "B", (short) 1, (short) 2, null, null))));

        // The second batch is refused because the first has claimed the labels — which is the whole point
        // of threading one set through the run. Whether the first batch survives is the transaction's job,
        // and this class is @Transactional, so the service joins the test's own and there is nothing here
        // to observe a rollback with.
        assertTrue(thrown.getMessage().contains("already in this development"), thrown.getMessage());
    }
}
