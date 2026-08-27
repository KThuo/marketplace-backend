package com.hodi.modules.developments;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface DevelopmentUnitRepository
        extends JpaRepository<DevelopmentUnit, Long>, JpaSpecificationExecutor<DevelopmentUnit> {

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

    @Query("select u.saleState as saleState, count(u) as tally from DevelopmentUnit u "
            + "where u.unitTypeId = :unitTypeId and u.status <> 5 group by u.saleState")
    List<StateTally> tallyForUnitType(@Param("unitTypeId") Long unitTypeId);

    @Query("select u.saleState as saleState, count(u) as tally from DevelopmentUnit u "
            + "where u.developmentId = :developmentId and u.status <> 5 group by u.saleState")
    List<StateTally> tallyForDevelopment(@Param("developmentId") Long developmentId);

    /**
     * The least-advanced construction status among a typology's live units.
     *
     * <p>Ordered by the build sequence rather than alphabetically — COMPLETE sorts before PLANNED in a string
     * comparison, which would report a finished typology as unstarted. The CASE is the sequence, written here
     * because it is the only place that needs it.
     */
    @Query("select u.constructionStatus from DevelopmentUnit u "
            + "where u.unitTypeId = :unitTypeId and u.status <> 5 "
            + "order by case u.constructionStatus "
            + "  when 'PLANNED' then 0 when 'UNDER_CONSTRUCTION' then 1 "
            + "  when 'COMPLETE' then 2 when 'HANDED_OVER' then 3 else 0 end")
    List<String> constructionStatusesForUnitType(@Param("unitTypeId") Long unitTypeId);

    @Query("select u.constructionStatus from DevelopmentUnit u "
            + "where u.developmentId = :developmentId and u.status <> 5 "
            + "order by case u.constructionStatus "
            + "  when 'PLANNED' then 0 when 'UNDER_CONSTRUCTION' then 1 "
            + "  when 'COMPLETE' then 2 when 'HANDED_OVER' then 3 else 0 end")
    List<String> constructionStatusesForDevelopment(@Param("developmentId") Long developmentId);

    /** The cheapest and dearest a buyer could pay across a whole development, for the card's range. */
    @Query("select min(coalesce(u.listPrice, t.listPrice)), max(coalesce(u.listPrice, t.listPrice)) "
            + "from DevelopmentUnit u join DevelopmentUnitType t on t.id = u.unitTypeId "
            + "where u.developmentId = :developmentId and u.status <> 5 "
            + "and u.saleState in ('AVAILABLE', 'HELD', 'RESERVED')")
    List<Object[]> priceRangeForDevelopment(@Param("developmentId") Long developmentId);

    boolean existsByReference(String reference);
    boolean existsByPayReference(String payReference);

    Optional<DevelopmentUnit> findByReference(String reference);

    /**
     * By the code a buyer quoted when paying.
     *
     * <p>Unscoped by state on purpose: a payment arriving three weeks after a cancellation still has to land
     * on the unit it names, and refusing to find it would put real money in an unmapped queue for no reason.
     * Whether it may be applied is the payment layer's decision, not this lookup's.
     */
    Optional<DevelopmentUnit> findByPayReference(String payReference);

    @Query("select count(u) from DevelopmentUnit u where u.developmentId = :developmentId "
            + "and upper(u.unitLabel) = upper(:label) and u.status <> 5 and u.id <> :exceptId")
    long countWithLabel(@Param("developmentId") Long developmentId,
                        @Param("label") String label,
                        @Param("exceptId") Long exceptId);

    /** What a buyer owns, for their own portal. Resolved from their identity, never an organisation filter. */
    @Query("select u from DevelopmentUnit u where u.buyerUserId = :userId and u.status <> 5 "
            + "order by u.soldAt desc nulls last, u.id desc")
    List<DevelopmentUnit> findForBuyer(@Param("userId") Long userId);

    @Query("select count(u) from DevelopmentUnit u where u.unitTypeId = :unitTypeId and u.status <> 5")
    long countForUnitType(@Param("unitTypeId") Long unitTypeId);
}
