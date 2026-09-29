package com.hodi.modules.notifications;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface NotificationLogRepository
        extends JpaRepository<NotificationLog, Long>, JpaSpecificationExecutor<NotificationLog> {

    /** Failed sends whose turn has come. The retry sweep's question. */
    @Query("select l from NotificationLog l where l.status = 'FAILED' and l.attempts < :cap "
            + "and (l.nextAttemptAt is null or l.nextAttemptAt <= :now) order by l.nextAttemptAt asc nulls first")
    List<NotificationLog> findDueForRetry(@Param("now") OffsetDateTime now, @Param("cap") int cap);

    List<NotificationLog> findByAboutTypeAndAboutIdOrderByCreatedAtDesc(String aboutType, Long aboutId);
}
