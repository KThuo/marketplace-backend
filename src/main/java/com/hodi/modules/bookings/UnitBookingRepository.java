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

    /**
     * The booking currently holding a unit, if any.
     *
     * <p>The same two states the partial unique index keys on, so this cannot find a row the index would have
     * allowed a second of. Used to refuse a second booking with a message rather than a constraint violation
     * — the index is still what guarantees it under a race.
     */
    @Query("select b from UnitBooking b where b.unitId = :unitId "
            + "and b.state in ('RESERVED', 'AGREED') and b.status <> 5")
    Optional<UnitBooking> findLiveForUnit(@Param("unitId") Long unitId);

    /** Every booking a unit has ever had, newest first. A cancelled one is part of the record. */
    @Query("select b from UnitBooking b where b.unitId = :unitId and b.status <> 5 "
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
}
