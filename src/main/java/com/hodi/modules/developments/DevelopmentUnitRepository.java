package com.hodi.modules.developments;

import com.hodi.modules.properties.Property;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The unit rows of {@code properties}.
 *
 * <p>A second repository over {@link Property}, narrowed to {@code listingKind = 'UNIT'} in every query, so the
 * inventory code reads and writes the same rows the marketplace, the bookings and the payments do. The derived
 * finder methods ({@code findByReference}, {@code existsByPayReference}) are not narrowed: a reference is unique
 * across every kind of row, and a pay reference exists only on a unit.
 */
public interface DevelopmentUnitRepository
        extends JpaRepository<Property, Long>, JpaSpecificationExecutor<Property> {

    /**
     * How many units sit in each sale state.
     *
     * <p>One grouped query rather than five counting queries, because the recount runs on every unit change
     * and a typology has four states worth counting. Returned as a projection rather than {@code Object[]} so
     * the caller reads a name instead of an index.
     */
    interface StateTally {
        String getSaleState();
        long getTally();
    }

    @Query("select u.saleState as saleState, count(u) as tally from Property u "
            + "where u.listingKind = 'UNIT' and u.unitTypeId = :unitTypeId and u.status <> 5 group by u.saleState")
    List<StateTally> tallyForUnitType(@Param("unitTypeId") Long unitTypeId);

    @Query("select u.saleState as saleState, count(u) as tally from Property u "
            + "where u.listingKind = 'UNIT' and u.developmentId = :developmentId and u.status <> 5 group by u.saleState")
    List<StateTally> tallyForDevelopment(@Param("developmentId") Long developmentId);

    /**
     * The least-advanced construction status among a typology's live units.
     *
     * <p>Ordered by the build sequence rather than alphabetically — COMPLETE sorts before PLANNED in a string
     * comparison, which would report a finished typology as unstarted. The CASE is the sequence, written here
     * because it is the only place that needs it.
     */
    @Query("select u.constructionStatus from Property u "
            + "where u.listingKind = 'UNIT' and u.unitTypeId = :unitTypeId and u.status <> 5 "
            + "order by case u.constructionStatus "
            + "  when 'PLANNED' then 0 when 'UNDER_CONSTRUCTION' then 1 "
            + "  when 'COMPLETE' then 2 when 'HANDED_OVER' then 3 else 0 end")
    List<String> constructionStatusesForUnitType(@Param("unitTypeId") Long unitTypeId);

    @Query("select u.constructionStatus from Property u "
            + "where u.listingKind = 'UNIT' and u.developmentId = :developmentId and u.status <> 5 "
            + "order by case u.constructionStatus "
            + "  when 'PLANNED' then 0 when 'UNDER_CONSTRUCTION' then 1 "
            + "  when 'COMPLETE' then 2 when 'HANDED_OVER' then 3 else 0 end")
    List<String> constructionStatusesForDevelopment(@Param("developmentId") Long developmentId);

    /** The cheapest and dearest a buyer could pay across a whole development, for the card's range. */
    @Query("select min(coalesce(u.price, t.listPrice)), max(coalesce(u.price, t.listPrice)) "
            + "from Property u join DevelopmentUnitType t on t.id = u.unitTypeId "
            + "where u.listingKind = 'UNIT' and u.developmentId = :developmentId and u.status <> 5 "
            + "and u.saleState in ('AVAILABLE', 'HELD', 'RESERVED')")
    List<Object[]> priceRangeForDevelopment(@Param("developmentId") Long developmentId);

    boolean existsByReference(String reference);
    boolean existsByPayReference(String payReference);

    Optional<Property> findByReference(String reference);

    /**
     * By the code a buyer quoted when paying.
     *
     * <p>Unscoped by state on purpose: a payment arriving three weeks after a cancellation still has to land
     * on the unit it names, and refusing to find it would put real money in an unmapped queue for no reason.
     * Whether it may be applied is the payment layer's decision, not this lookup's.
     */
    Optional<Property> findByPayReference(String payReference);

    @Query("select count(u) from Property u where u.listingKind = 'UNIT' and u.developmentId = :developmentId "
            + "and upper(u.unitLabel) = upper(:label) and u.status <> 5 and u.id <> :exceptId")
    long countWithLabel(@Param("developmentId") Long developmentId,
                        @Param("label") String label,
                        @Param("exceptId") Long exceptId);

    /** What a buyer owns, for their own portal. Resolved from their identity, never an organisation filter. */
    @Query("select u from Property u where u.listingKind = 'UNIT' and u.buyerUserId = :userId and u.status <> 5 "
            + "order by u.soldAt desc nulls last, u.id desc")
    List<Property> findForBuyer(@Param("userId") Long userId);

    /**
     * Every live unit of one typology, in the order somebody reads a building: block, then floor, then label.
     *
     * <p>For the public availability list. Archived units are excluded; sold ones are not, because what has
     * gone is half of what the list is for.
     */
    @Query("select u from Property u where u.listingKind = 'UNIT' and u.unitTypeId = :unitTypeId and u.status <> 5 "
            + "order by u.block nulls first, u.floorNo nulls first, u.unitLabel")
    List<Property> findPublicForUnitType(@Param("unitTypeId") Long unitTypeId);

    /**
     * How many of each typology are still available, for a page of developments.
     *
     * <p>Returns {@code [unitTypeId, available]}. The counts already live on the typology row, maintained by
     * the inventory service — this exists for the cases where a caller has ids and wants the figures without
     * loading the rows.
     */
    @Query("select u.unitTypeId, count(u) from Property u "
            + "where u.listingKind = 'UNIT' and u.unitTypeId in :unitTypeIds and u.saleState = 'AVAILABLE' and u.status <> 5 "
            + "group by u.unitTypeId")
    List<Object[]> availableCounts(@Param("unitTypeIds") java.util.Collection<Long> unitTypeIds);

    @Query("select count(u) from Property u where u.listingKind = 'UNIT' and u.unitTypeId = :unitTypeId and u.status <> 5")
    long countForUnitType(@Param("unitTypeId") Long unitTypeId);

    /** Every label in use, for the generator to check a whole plan against before writing any of it. */
    @Query("select u.unitLabel from Property u where u.listingKind = 'UNIT' and u.developmentId = :developmentId "
            + "and u.status <> 5")
    List<String> labelsForDevelopment(@Param("developmentId") Long developmentId);

    /** How many units a phase still holds. What stops a phase being archived out from under them. */
    @Query("select count(u) from Property u where u.listingKind = 'UNIT' and u.phaseId = :phaseId and u.status <> 5")
    long countForPhase(@Param("phaseId") Long phaseId);

    /** The project's name and place, onto every one of its unit rows. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Property p set p.developmentName = :name, p.developmentReference = :reference, "
            + "p.county = :county, p.town = :town, p.estate = :estate, p.addressLine = :addressLine, "
            + "p.latitude = :latitude, p.longitude = :longitude, "
            + "p.title = concat(:name, ' · ', p.unitLabel) "
            + "where p.developmentId = :developmentId and p.listingKind = 'UNIT' and p.status <> 5")
    int syncDetails(@Param("developmentId") Long developmentId, @Param("name") String name,
                    @Param("reference") String reference, @Param("county") String county,
                    @Param("town") String town, @Param("estate") String estate,
                    @Param("addressLine") String addressLine, @Param("latitude") java.math.BigDecimal latitude,
                    @Param("longitude") java.math.BigDecimal longitude);

    /**
     * The project's listing state, onto every unit row that is not sold and not waiting on its own decision.
     *
     * <p>{@code PENDING} is excluded for the same reason {@code SOLD} is: it is the unit's own fact rather
     * than the project's. A unit whose price was changed goes back to the bank by itself, and a project-wide
     * sync that published it again on the next unrelated approval would decide a question the bank had been
     * asked and not yet answered.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Property p set p.listingState = :state, p.publishedAt = :publishedAt "
            + "where p.developmentId = :developmentId and p.listingKind = 'UNIT' and p.status <> 5 "
            + "and p.saleState <> 'SOLD' and p.listingState <> 'PENDING'")
    int syncListingState(@Param("developmentId") Long developmentId, @Param("state") String state,
                         @Param("publishedAt") java.time.OffsetDateTime publishedAt);
}
