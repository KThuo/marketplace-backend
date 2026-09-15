package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Every priced unit type of a published project has a card on the marketplace.
 *
 * <h2>The rule this defends</h2>
 *
 * <p>A development is browsed through its typologies, not its units. Ten one-beds and twenty two-beds are
 * two rows — "16 of 24 available" — and clicking one drills into the individual homes. Thirty rows for one
 * building is the thing the typology card exists to prevent, and it is also why a development needs no
 * separate treatment in the marketplace search: its cards are ordinary {@code properties} rows and are
 * found by the same query as a house.
 *
 * <p>That only works if the cards exist.
 *
 * <h2>Why a reconciler and not just the submit path</h2>
 *
 * <p>{@code DevelopmentUnitTypeService.ensureListings} creates them, and it runs when a project is
 * submitted. Anything published through the application is therefore correct, and Palm Heights — created
 * and submitted this way — has exactly the card it should.
 *
 * <p>What has no path at all is a project that reached {@code LIVE} without passing through submit: a
 * seeded demo row, a state set by a migration, or a project published before {@code ensureListings}
 * existed. The demo development is the case in hand — four priced unit types, one card, so three kinds of
 * home were on the market according to the project's own record and absent from every buyer's search
 * results. Nothing self-heals it, because nothing runs again on a project that is already live.
 *
 * <p>So this reconciles on boot, in the same spirit as the seeder's owner-group top-up: something built
 * once, never revisited, and silently wrong afterwards. It is idempotent — {@code ensureListings} skips a
 * type that already has a card — so it costs one query per published project on every restart and changes
 * nothing once the estate is correct.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TypologyCardReconciler {

    private final DevelopmentRepository developments;
    private final DevelopmentUnitTypeRepository types;
    private final DevelopmentUnitRepository units;
    private final DevelopmentUnitTypeService unitTypes;
    private final DevelopmentPublication publication;
    private final TransactionTemplate newTransaction;

    /** Shares the seeder's switch: an environment that does not want boot-time repair does not want this. */
    @Value("${hodi.seed.enabled:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnBoot() {
        if (!enabled) return;
        try {
            newTransaction.executeWithoutResult(status -> reconcile());
        } catch (RuntimeException e) {
            // Loud, but never fatal: a missing card is a listing nobody can find, not a platform that
            // should refuse to start.
            log.error("Could not reconcile typology cards — some projects may be missing listings", e);
        }
    }

    /**
     * Gives every priced unit type of a published project the card it should have.
     *
     * <p>A card is created as a draft, because that is what {@code cardFor} builds and what a project still
     * awaiting approval should have. On a project that is already live it is then moved live with the
     * project, carrying the project's own {@code publishedAt} — a card marked live at a different moment
     * from the thing it belongs to would sort wrongly against every other listing and would claim the
     * marketplace had it earlier than it did.
     */
    @Transactional
    public int reconcile() {
        int created = 0;
        for (Development development : developments.findAll()) {
            if (AppConstant.STATUS_DELETED == development.getStatus()) continue;

            boolean live = AppConstant.LISTING_LIVE.equals(development.getListingState());
            boolean waiting = AppConstant.LISTING_PENDING.equals(development.getListingState());
            // A draft or private project has no public face, so a missing card is not yet a missing listing.
            if (!live && !waiting) continue;

            /*
             * Only a project whose every kind has homes behind it.
             *
             * ensureListings recounts each type it touches, and a recount is derived from the unit rows
             * that exist — so running it against a type with no generated units writes zero over whatever
             * the type says it is planning. That is destructive on exactly the rows this reconciler exists
             * to help: a project carrying planned figures it has not generated yet.
             *
             * It is also the wrong card to create. A typology card is a thing a buyer can buy, and one
             * reading "0 of 0 available" is worse in a search result than not being there — it advertises
             * a kind of home and then refuses to sell it. Such a type is left alone, and the project is
             * skipped whole rather than half-reconciled, so what is on the marketplace stays something
             * somebody chose rather than something a restart decided.
             */
            if (!everyKindHasUnits(development)) {
                log.warn("Development {} has unit types with no generated units — leaving its cards alone",
                        development.getReference());
                continue;
            }

            int drafted = unitTypes.ensureListings(development);
            if (drafted == 0) continue;

            if (live) {
                publication.moveTypologies(development, AppConstant.LISTING_LIVE,
                        development.getPublishedAt());
            }
            created += drafted;
            log.warn("Development {} was missing {} typology card(s) on the marketplace — created",
                    development.getReference(), drafted);
        }
        if (created > 0) log.info("Typology reconciliation created {} card(s)", created);
        return created;
    }

    /** True when every priced kind of home in this project has at least one unit row behind it. */
    private boolean everyKindHasUnits(Development development) {
        for (DevelopmentUnitType type : types.findForDevelopment(development.getId())) {
            if (type.getListPrice() == null && type.getFromPrice() == null) continue;
            if (units.countForUnitType(type.getId()) == 0) return false;
        }
        return true;
    }
}
