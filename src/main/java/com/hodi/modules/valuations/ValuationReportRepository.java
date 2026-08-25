package com.hodi.modules.valuations;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ValuationReportRepository extends JpaRepository<ValuationReport, Long> {

    Optional<ValuationReport> findByRequestId(Long requestId);
}
