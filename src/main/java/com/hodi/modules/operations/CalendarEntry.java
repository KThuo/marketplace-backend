package com.hodi.modules.operations;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;

/**
 * One thing happening at a time (M12).
 *
 * <p>Mostly a projection: viewings, valuation appointments and auctions already have times, in three
 * different modules, and somebody whose job is the week ahead should not open three screens to find it. An
 * entry points back at the row it came from and is rewritten when that row moves — it is not a second source
 * of truth.
 *
 * <p>A {@code MANUAL} entry has no source and is the only kind the calendar itself owns.
 */
@Entity
@Table(name = "event_calendar_entries")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CalendarEntry {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16) private String reference;

    @Column(name = "source_type", nullable = false, length = 24) private String sourceType;
    @Column(name = "source_id") private Long sourceId;
    @Column(name = "source_ref", length = 16) private String sourceRef;

    @Column(name = "tenant_id") private Long tenantId;
    @Column(name = "tenant_name", length = 255) private String tenantName;

    @Column(nullable = false, length = 255) private String title;
    @Column(columnDefinition = "TEXT") private String detail;
    @Column(length = 255) private String location;

    @Column(name = "starts_at", nullable = false) private OffsetDateTime startsAt;
    @Column(name = "ends_at") private OffsetDateTime endsAt;
    @Column(name = "all_day", nullable = false) @Builder.Default private boolean allDay = false;

    @Column(name = "owner_user_id") private Long ownerUserId;
    @Column(name = "owner_name", length = 160) private String ownerName;

    @Column(nullable = false, length = 16)
    @Builder.Default private String state = OperationsConstants.ENTRY_SCHEDULED;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", updatable = false) private OffsetDateTime createdAt;
    @UpdateTimestamp @Column(name = "updated_at") private OffsetDateTime updatedAt;
    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false) private String searchText;

    /** Typed by somebody rather than projected, and therefore editable here. */
    public boolean isManual() {
        return OperationsConstants.SOURCE_MANUAL.equals(sourceType);
    }
}
