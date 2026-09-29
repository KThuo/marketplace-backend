package com.hodi.modules.bookings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface UnitBookingRepository
        extends JpaRepository<UnitBooking, Long>, JpaSpecificationExecutor<UnitBooking> {

    Optional<UnitBooking> findByReference(String reference);

    boolean existsByPayReference(String payReference);

    /**
     * By the code a buyer quoted when paying.
     *
     * <p>Unscoped by state on purpose: a payment arriving three weeks after a cancellation still has to find
     * the booking it names, so the matcher can say "cancelled" rather than "unknown". Whether it may be
     * credited is the payment layer's decision, not this lookup's.
     */
    Optional<UnitBooking> findByPayReference(String payReference);

    /**
     * Completed sales on developments the bank collects for, not yet settled (or settled, when
     * {@code settled} is true), scoped to an owner where one is given. What the settlements queue reads.
     */
    @Query("select b from UnitBooking b where b.state = 'COMPLETED' and b.status <> 5 "
            + "and ((:settled = true and b.settledAt is not null) or (:settled = false and b.settledAt is null)) "
            + "and b.developmentId in (select d.id from Development d where d.collectionMode = 'BANK' and d.status <> 5) "
            + "and (:tenantId is null or b.tenantId = :tenantId) "
            + "and (:institutionId is null or b.institutionId = :institutionId) "
            + "order by b.completedAt desc")
    org.springframework.data.domain.Page<UnitBooking> findForSettlement(@Param("settled") boolean settled,
            @Param("tenantId") Long tenantId, @Param("institutionId") Long institutionId,
            org.springframework.data.domain.Pageable pageable);

    /** A development's completed sales, for the owner's settlements card. */
    @Query("select b from UnitBooking b where b.developmentId = :developmentId and b.state = 'COMPLETED' and b.status <> 5")
    List<UnitBooking> findCompletedFor(@Param("developmentId") Long developmentId);

    /** Every booking an agent brought the buyer of, newest first — in every state, not only the paid ones. */
    @Query("select b from UnitBooking b where b.introducedByAgentId = :agentId and b.status <> 5 "
            + "order by b.createdAt desc")
    List<UnitBooking> findIntroducedBy(@Param("agentId") Long agentId);

    /** A buyer's own bookings, by identity: the ones they can pay and read receipts for. */
    @Query("select b from UnitBooking b where b.buyerUserId = :userId and b.status <> 5 "
            + "order by b.bookedOn desc, b.id desc")
    List<UnitBooking> findForBuyer(@Param("userId") Long userId);

    /**
     * The booking currently holding a unit, if any.
     *
     * <p>The same two states the partial unique index keys on, so this cannot find a row the index would have
     * allowed a second of. Used to refuse a second booking with a message rather than a constraint violation
     * — the index is still what guarantees it under a race.
     */
    @Query("select b from UnitBooking b where b.propertyId = :unitId "
            + "and b.state in ('RESERVED', 'AGREED') and b.status <> 5")
    Optional<UnitBooking> findLiveForUnit(@Param("unitId") Long unitId);

    /**
     * The live bookings for a page of units, in one query.
     *
     * <p>The inventory screen needs to know which units a booking holds, so it can offer the booking's actions
     * rather than the unit's hold path — the server refuses the wrong one, but a screen that offers it anyway
     * is a screen that produces a refusal for every click. Two hundred rows with a lookup each would be two
     * hundred queries behind one page, which is the batching the unit list already does for typologies.
     *
     * <p>An empty collection is the caller's job to short-circuit: {@code in ()} is not valid SQL.
     */
    @Query("select b from UnitBooking b where b.propertyId in :unitIds "
            + "and b.state in ('RESERVED', 'AGREED') and b.status <> 5")
    List<UnitBooking> findLiveForUnits(@Param("unitIds") java.util.Collection<Long> unitIds);

    /** Every booking a unit has ever had, newest first. A cancelled one is part of the record. */
    @Query("select b from UnitBooking b where b.propertyId = :unitId and b.status <> 5 "
            + "order by b.bookedOn desc, b.id desc")
    List<UnitBooking> findForUnit(@Param("unitId") Long unitId);

    @Query("select count(b) from UnitBooking b where b.developmentId = :developmentId "
            + "and b.state in ('RESERVED', 'AGREED') and b.status <> 5")
    long countLiveForDevelopment(@Param("developmentId") Long developmentId);

    /**
     * Reservations already past their expiry.
     *
     * <p>The sweep's query. Deliberately not "expiring soon" — a booking is lapsed or it is not, and a job that
     * acted on the nearly-expired would be closing bookings early on the strength of a scheduler's timing.
     */
    @Query("select b from UnitBooking b where b.state = 'RESERVED' and b.status <> 5 "
            + "and b.expiresAt is not null and b.expiresAt < :now order by b.expiresAt")
    List<UnitBooking> findLapsed(@Param("now") OffsetDateTime now);

    /** Holds that lapse before the horizon and have not been reminded. */
    @Query("select b from UnitBooking b where b.state = 'RESERVED' and b.status <> 5 "
            + "and b.expiresAt is not null and b.expiresAt > :now and b.expiresAt < :horizon "
            + "and b.expiryReminderSentAt is null order by b.expiresAt")
    List<UnitBooking> findExpiringUnreminded(@Param("now") OffsetDateTime now, @Param("horizon") OffsetDateTime horizon);
}
