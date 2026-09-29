package com.hodi.modules.valuations;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ValuationEventRepository extends JpaRepository<ValuationEvent, Long> {

    List<ValuationEvent> findByRequestIdOrderByCreatedAtAsc(Long requestId);

    long countByRequestIdAndAction(Long requestId, String action);
}
