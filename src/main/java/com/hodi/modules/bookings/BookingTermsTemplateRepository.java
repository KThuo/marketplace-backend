package com.hodi.modules.bookings;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingTermsTemplateRepository extends JpaRepository<BookingTermsTemplate, Long> {

    Optional<BookingTermsTemplate> findTopByOrderByVersionDesc();

    Optional<BookingTermsTemplate> findByVersion(Integer version);

    List<BookingTermsTemplate> findAllByOrderByVersionDesc();
}
