package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.properties.Property;
import com.hodi.modules.properties.PropertyRepository;
import com.hodi.security.principal.AuthContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * What moves a project's public face, and who has to agree to it.
 *
 * <p>Its own collaborator rather than more methods on {@link DevelopmentService}, and the reason is a bean
 * cycle rather than taste. A unit type's price and a unit's price both have to send their project back for
 * approval, so {@code DevelopmentUnitTypeService} and {@code DevelopmentUnitService} need to call it — and
 * both of those are already injected into {@code DevelopmentService}. Putting the method there made
 * Spring refuse to start.
 *
 * <p>What it needs is the repository, the inventory sync, the listings and the approvals queue. None of
 * those is {@code DevelopmentService}, so the cycle disappears rather than being deferred with
 * {@code @Lazy} — which would have worked and left the knot in place.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevelopmentPublication {

    private final DevelopmentRepository developments;
    private final DevelopmentInventoryService inventory;
    private final PropertyRepository properties;
    private final ApprovalService approvals;

    /**
     * The project's money or inventory changed, so the bank looks at it again.
     *
     * <p>The seller sells through the bank, so a price the bank has not seen must not be the price on the
     * marketplace. Changing a unit type's price, changing a unit's price and adding units to a live project
     * all land here, and so does editing the project itself.
     *
     * <p><strong>The live page comes down while it waits</strong>, and that is the part worth being explicit
     * about rather than discovering. A stale price in front of buyers is the thing being prevented, and
     * leaving the page up with the old figure while the new one waits would prevent nothing. A draft or
     * private project has no public face, so nothing happens to it.
     *
     * <p>Tolerant of a request already waiting: three edits in a row have made one project stale, not three,
     * and the second must not fail over an edit nobody knew was raising anything. The waiting request is
     * restated rather than left alone — the checker is shown what changed last, because that is the most
     * current description of what is wrong with the project.
     *
     * @param reason what the checker is shown, in the words of whatever changed
     */
    @Transactional
    public void requireReapproval(Long developmentId, String reason) {
        Development development = developments.findById(developmentId).orElse(null);
        if (development == null) return;

        boolean live = development.isLive();
        boolean alreadyWaiting = AppConstant.LISTING_PENDING.equals(development.getListingState());
        /*
         * A draft or private project has no public face and nothing waiting, so an edit to it is just an
         * edit. Everything else is either on the marketplace or on its way back to it.
         */
        if (!live && !alreadyWaiting) return;

        Development saved = development;
        if (live) {
            development.setListingState(AppConstant.LISTING_PENDING);
            development.setPublishedAt(null);
            development.setUpdatedBy(AuthContext.username());
            saved = developments.save(development);

            inventory.syncUnitRows(saved);
            moveTypologies(saved, AppConstant.LISTING_DRAFT, null);
        }

        /*
         * Restated even when the project is already waiting, and that is the case this method got wrong
         * first time: it returned early on anything not live, so a second edit before anybody looked left
         * the checker reading the first reason. Two edits make one project stale, and the description of
         * what is wrong with it should be the newer one.
         */
        approvals.submitOrRestate(AppConstant.APPROVAL_ENTITY_DEVELOPMENT, saved.getId(),
                AppConstant.APPROVAL_ACTION_PUBLISH, ownerScopeId(saved), null,
                saved.getReference() + " — " + saved.getName(), reason);
        log.info("Development {} needs approval again: {}", saved.getReference(), reason);
    }

    /**
     * Moves every typology card with the project it belongs to.
     *
     * <p>The cards are what the marketplace shows, and they used to hold an approval each — publishing one
     * project with three typologies meant four round trips, three of them for rows nobody had drafted by
     * hand. A second queue for a decision the bank has just made on the thing they belong to is a queue that
     * only ever gets rubber-stamped.
     *
     * <p>A card sold out on its own is left alone: its state is its own fact and outlives the project's, the
     * same rule a sold unit already gets.
     */
    @Transactional
    public void moveTypologies(Development development, String state, OffsetDateTime publishedAt) {
        for (Property card : properties.findTypologiesForDevelopment(development.getId())) {
            if (AppConstant.LISTING_SOLD.equals(card.getListingState())) continue;
            card.setListingState(state);
            card.setPublishedAt(publishedAt);
            card.setUpdatedBy(AuthContext.username());
            properties.save(card);
        }
    }

    /** Whose queue the request belongs in: whoever markets it, else whoever owns it. */
    static Long ownerScopeId(Development development) {
        return development.getSellingTenantId() != null
                ? development.getSellingTenantId()
                : development.getTenantId();
    }
}
