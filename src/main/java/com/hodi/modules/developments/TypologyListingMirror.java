package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * A typology and its marketplace card are one home described in two tables. This keeps them saying the
 * same thing.
 *
 * <h2>Why there are two tables at all</h2>
 *
 * <p>The card is a {@code properties} row so that everything keyed on a listing — enquiries, viewings,
 * offers, saved searches, promotions, commissions — can point at a kind of home in a development without
 * any of those modules learning what a development is. {@code DevelopmentUnitTypeService.cardFor} builds
 * it from the typology, once, at the moment the typology is listed.
 *
 * <p>Once, and then never again. That was the bug: the card was a photograph of the typology taken on the
 * day it was listed. A two-bed corrected to a three-bed in the project screens went on advertising two
 * bedrooms to buyers; a price fixed on the card went on being the old price in the project's own figures.
 * Both screens showed a saved edit, and each was the other's stale copy.
 *
 * <h2>The rule</h2>
 *
 * <p>The facts a buyer decides on — what kind of property, how many bedrooms, bathrooms and parking
 * spaces, the floor area, the service charge, the price, the description and what it comes with — belong
 * to the home, not to the screen it was typed on. An edit on either side is an edit to both.
 *
 * <p>What is deliberately <em>not</em> shared:
 *
 * <ul>
 *   <li><b>Where it is.</b> County, town and the pin are the development's, copied onto the card when it
 *       is built. A typology is not somewhere else from the project it is in, so there is nothing here to
 *       reconcile — moving the project is what moves its cards.</li>
 *   <li><b>Availability and the "from" price.</b> {@code DevelopmentInventoryService} owns those: they are
 *       counted from the unit rows, not typed by anybody, and it remains their single writer.</li>
 *   <li><b>A title somebody wrote themselves.</b> See {@link #titleFor}.</li>
 * </ul>
 *
 * <h2>Why this cannot loop</h2>
 *
 * <p>Both directions write through repositories rather than through the two services that call this, so
 * neither arrival re-enters the other's update path. A save that changes nothing writes the same values
 * back and stops.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TypologyListingMirror {

    private final PropertyRepository properties;
    private final DevelopmentUnitTypeRepository unitTypes;
    private final DevelopmentRepository developments;
    private final AmenityService amenities;
    private final DevelopmentPublication publication;

    // ── the typology's edit, onto its card ───────────────────────────────────

    /**
     * Copies an edited typology onto the listing that sells it.
     *
     * @param previousName the typology's name before this edit, so a generated title can be recognised as
     *                     one and left alone when it is not
     */
    @Transactional
    public void toListing(DevelopmentUnitType type, String previousName) {
        Optional<Property> found = properties.findByUnitTypeId(type.getId());
        if (found.isEmpty()) return;

        Property card = found.get();
        String developmentName = developments.findById(type.getDevelopmentId())
                .map(Development::getName).orElse(null);

        card.setTitle(titleFor(card, type, previousName, developmentName));
        card.setDescription(type.getDescription());
        card.setPropertyType(type.getPropertyType());
        card.setBedrooms(type.getBedrooms());
        card.setBathrooms(type.getBathrooms());
        card.setParkingSpaces(type.getParkingSpaces());
        card.setFloorAreaSqm(type.getFloorAreaSqm());
        card.setServiceCharge(type.getServiceCharge());

        /*
         * The asking price, and the inventory's "from" figure wins where there is one.
         *
         * A typology with units priced individually advertises what the cheapest one still available costs,
         * which is a number nobody types and which recountUnitType writes. This fills the other case — a
         * typology with no generated units yet, whose price is simply the one on the type — where the card
         * would otherwise keep the figure it was drafted with for ever.
         */
        BigDecimal asking = type.getFromPrice() != null ? type.getFromPrice() : type.getListPrice();
        if (asking != null) card.setPrice(asking);

        card.setUpdatedBy(AuthContext.username());
        properties.save(card);

        amenities.apply(AmenityService.Scope.LISTING, card.getId(),
                amenities.codesFor(AmenityService.Scope.UNIT_TYPE, type.getId()));

        log.debug("Typology {} mirrored onto listing {}", type.getReference(), card.getReference());
    }

    /**
     * The card's title: derived from the typology until somebody writes their own.
     *
     * <p>{@code cardFor} titles a card "Two bedroom at Highrise Apartments", and a typology renamed to
     * "Three bedroom" should carry its card with it — otherwise the rename is exactly the edit that does
     * not arrive. But a seller may also have retitled the card ("Corner two-beds, west facing"), and
     * regenerating over that would delete their words on a save they made somewhere else entirely.
     *
     * <p>So the generated form is recognised rather than assumed: the title is replaced only when it is
     * still what this method would have produced for the old name. Anything else is somebody's own, and is
     * left exactly as it is.
     */
    private static String titleFor(Property card, DevelopmentUnitType type,
                                   String previousName, String developmentName) {
        String generatedBefore = generatedTitle(previousName, developmentName);
        boolean untouched = card.getTitle() == null || card.getTitle().equals(generatedBefore);
        return untouched ? generatedTitle(type.getName(), developmentName) : card.getTitle();
    }

    private static String generatedTitle(String typeName, String developmentName) {
        if (typeName == null) return null;
        return developmentName == null ? typeName : typeName + " at " + developmentName;
    }

    // ── the card's edit, back onto the typology ──────────────────────────────

    /**
     * Copies an edited listing back onto the typology it sells.
     *
     * <p>Called for a typology card and for nothing else. A unit's listing carries its own label, floor and
     * price, and writing those onto the typology would make one home's correction everybody's.
     *
     * <p>The re-approval rule travels with the facts. {@code DevelopmentUnitTypeService.update} sends a
     * project back to the bank when a material fact moves, because the bank approved those figures; an edit
     * arriving through the listing form changes the same figures and so has to do the same thing. Otherwise
     * the listing screen would be a way round the project's own gate — and the card is already pulled off
     * the marketplace by {@code PropertyService}, so only the project's side is left to say.
     */
    @Transactional
    public void toTypology(Property card) {
        if (card.getUnitTypeId() == null || !card.isUnitTypeListing()) return;
        Optional<DevelopmentUnitType> found = unitTypes.findById(card.getUnitTypeId());
        if (found.isEmpty()) return;

        DevelopmentUnitType type = found.get();
        String materialBefore = DevelopmentUnitTypeService.material(type);

        type.setDescription(card.getDescription());
        type.setPropertyType(card.getPropertyType());
        type.setBedrooms(card.getBedrooms());
        type.setBathrooms(card.getBathrooms());
        type.setParkingSpaces(card.getParkingSpaces());
        type.setFloorAreaSqm(card.getFloorAreaSqm());
        type.setServiceCharge(card.getServiceCharge());
        /*
         * Onto the list price, never onto the "from" price.
         *
         * `from` is counted from the units that are still for sale and belongs to the inventory writer. The
         * list price is the typology's own asking figure, which is what somebody typing a price on the card
         * means — and where units exist to be counted, the card will go back to showing their "from" figure
         * on the next recount. Said here so that is a decision rather than a surprise.
         */
        if (card.getPrice() != null) type.setListPrice(card.getPrice());

        type.setStatus(AppConstant.STATUS_EDITED);
        type.setStatusFlag(AppConstant.FLAG_EDITED);
        type.setUpdatedBy(AuthContext.username());
        DevelopmentUnitType saved = unitTypes.save(type);

        amenities.apply(AmenityService.Scope.UNIT_TYPE, saved.getId(),
                amenities.codesFor(AmenityService.Scope.LISTING, card.getId()));

        String materialAfter = DevelopmentUnitTypeService.material(saved);
        if (!materialBefore.equals(materialAfter)) {
            publication.requireReapproval(saved.getDevelopmentId(),
                    "The " + saved.getName() + " listing was edited: "
                            + movedFields(materialBefore, materialAfter) + ".");
        }

        log.debug("Listing {} mirrored back onto typology {}", card.getReference(), saved.getReference());
    }

    /** Which of the material facts moved, in the words the project's approvals queue uses. */
    private static String movedFields(String before, String after) {
        String[] was = before.split("\\|", -1);
        String[] now = after.split("\\|", -1);
        String[] fields = {"the kind of property", "bedrooms", "bathrooms", "parking",
                "floor area", "balcony area", "the price", "the service charge"};
        List<String> moved = new java.util.ArrayList<>();
        for (int i = 0; i < fields.length && i < was.length && i < now.length; i++) {
            if (!was[i].equals(now[i])) moved.add(fields[i]);
        }
        return moved.isEmpty() ? "its details changed" : String.join(" and ", moved) + " changed";
    }
}
