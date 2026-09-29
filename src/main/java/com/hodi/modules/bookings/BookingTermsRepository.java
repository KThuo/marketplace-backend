package com.hodi.modules.bookings;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BookingTermsRepository extends JpaRepository<BookingTerms, Long> {

    /** The terms in force on a booking: the latest not superseded. */
    @Query("select t from BookingTerms t where t.bookingId = :bookingId and t.supersededAt is null "
            + "order by t.presentedAt desc, t.id desc limit 1")
    Optional<BookingTerms> findCurrent(@Param("bookingId") Long bookingId);
}
