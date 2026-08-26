package com.hodi.modules.operations;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface CalendarEntryRepository
        extends JpaRepository<CalendarEntry, Long>, JpaSpecificationExecutor<CalendarEntry> {

    Optional<CalendarEntry> findByReference(String reference);

    /** The projected entry for one source row, so re-projecting updates rather than duplicates. */
    Optional<CalendarEntry> findBySourceTypeAndSourceId(String sourceType, Long sourceId);
}
