package com.hodi.modules.buyerportal;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;

/**
 * A saved search that tells its owner when something new matches it (BRD FR022–FR024).
 *
 * <p>The criteria are columns, one for one with {@code PublicSearchRequest}. Not a JSON blob: the dispatcher
 * re-runs the search server-side through the same specification builder the marketplace uses, and a stored
 * blob would be a second parser for the same query — one that drifts the first time a facet is added.
 *
 * <p>There is no channel column. Which way an alert arrives is decided at send time from the consent store,
 * every time; a channel stored here could contradict a person's recorded refusal, with this row winning.
 */
@Entity
@Table(name = "search_alerts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SearchAlert {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(nullable = false, length = 120) private String name;

    // ── the criteria ──────────────────────────────────────────────────────────
    @Column(name = "search_term", length = 255) private String searchTerm;
    /** {@code SALE}, {@code RENT}, or null for both — which is what every alert saved before now meant. */
    @Column(name = "listing_type", length = 16) private String listingType;
    @Column(name = "property_type", length = 32) private String propertyType;
    @Column(length = 64) private String county;
    @Column(length = 64) private String town;
    @Column(name = "min_price", precision = 15, scale = 2) private BigDecimal minPrice;
    @Column(name = "max_price", precision = 15, scale = 2) private BigDecimal maxPrice;
    @Column(name = "min_bedrooms") private Short minBedrooms;
    @Column(name = "max_bedrooms") private Short maxBedrooms;
    @Column(name = "green_only", nullable = false) @Builder.Default private boolean greenOnly = false;

    @Column(nullable = false, length = 16)
    @Builder.Default private String frequency = AppConstant.ALERT_DAILY;

    /** The window's start. Only listings published after this are ever reported. */
    @Column(name = "last_run_at", nullable = false)
    @Builder.Default private OffsetDateTime lastRunAt = OffsetDateTime.now();

    @Column(name = "next_run_at", nullable = false)
    @Builder.Default private OffsetDateTime nextRunAt = OffsetDateTime.now();

    @Column(name = "last_match_count", nullable = false) @Builder.Default private Integer lastMatchCount = 0;
    @Column(name = "total_sent", nullable = false) @Builder.Default private Integer totalSent = 0;
    @Column(name = "last_outcome", length = 32) private String lastOutcome;

    @Column(nullable = false) @Builder.Default private Integer status = AppConstant.STATUS_ACTIVE;
    @Column(name = "status_flag", nullable = false, length = 32)
    @Builder.Default private String statusFlag = AppConstant.FLAG_ACTIVE;

    @CreationTimestamp @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", length = 64) private String createdBy;
    @Column(name = "updated_by", length = 64) private String updatedBy;

    @Column(name = "search_text", insertable = false, updatable = false)
    private String searchText;

    /**
     * When this alert should next be considered, given that it has just run.
     *
     * <p>Absolute times of day rather than "now plus twenty-four hours", so a daily alert stays a morning
     * alert instead of drifting an hour later every time the dispatcher is slow or the service restarts. The
     * hour is deliberately not configurable yet — one sensible time beats a setting nobody has asked for.
     */
    public OffsetDateTime scheduleAfter(OffsetDateTime from) {
        return switch (frequency == null ? AppConstant.ALERT_DAILY : frequency) {
            case AppConstant.ALERT_INSTANT -> from.plusMinutes(15);
            case AppConstant.ALERT_WEEKLY -> from.plusDays(1)
                    .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
                    .withHour(8).withMinute(0).withSecond(0).withNano(0);
            default -> from.plusDays(1).withHour(8).withMinute(0).withSecond(0).withNano(0);
        };
    }

    /** Whether this alert is switched on. Paused is {@code STATUS_INACTIVE}; archived is {@code 5}. */
    public boolean isRunning() {
        return AppConstant.isLive(status);
    }
}
