package com.hodi.modules.bookings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BookingInstalmentRepository extends JpaRepository<BookingInstalment, Long> {

    /**
     * The current schedule: the highest plan number, live rows only.
     *
     * <p>A rescheduled booking has two plans and only one of them is what was last agreed. Returning both
     * would double every total, which is the sort of bug that reads as a pricing error rather than a query
     * one.
     */
    @Query("select i from BookingInstalment i where i.bookingId = :bookingId and i.status <> 5 "
            + "and i.planNo = (select max(i2.planNo) from BookingInstalment i2 "
            + "                 where i2.bookingId = :bookingId and i2.status <> 5) "
            + "order by i.sequenceNo")
    List<BookingInstalment> findCurrentPlan(@Param("bookingId") Long bookingId);

    /** Every version, for showing what changed when a schedule was moved. */
    @Query("select i from BookingInstalment i where i.bookingId = :bookingId and i.status <> 5 "
            + "order by i.planNo desc, i.sequenceNo")
    List<BookingInstalment> findAllPlans(@Param("bookingId") Long bookingId);

    @Query("select coalesce(max(i.planNo), 0) from BookingInstalment i "
            + "where i.bookingId = :bookingId and i.status <> 5")
    short currentPlanNo(@Param("bookingId") Long bookingId);
}
