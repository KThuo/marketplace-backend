package com.hodi.modules.valuations;

import com.hodi.common.AppConstant;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A valuer on the platform's panel (M5, plan §3.5).
 *
 * <p>A person, not an organisation. What makes them assignable is professional indemnity cover that is
 * current and large enough for the job (FR041), panel membership that has not been suspended, and a
 * willingness to travel to the county the property is in.
 */
@Entity
@Table(name = "valuer_profiles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ValuerProfile {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "profile_id", nullable = false) private Long profileId;

    @Column(nullable = false, length = 16) private String reference;
    @Column(name = "full_name", length = 160) private String fullName;
    @Column(name = "firm_name", length = 255) private String firmName;

    @Column(name = "registration_number", length = 64) private String registrationNumber;
    @Column(name = "registration_body", length = 64) private String registrationBody;
    @Column(name = "registered_until") private LocalDate registeredUntil;

    @Column(name = "pi_insurer", length = 160) private String piInsurer;
    @Column(name = "pi_policy_number", length = 64) private String piPolicyNumber;
    @Column(name = "pi_sum_assured", precision = 15, scale = 2) private BigDecimal piSumAssured;
    @Column(name = "pi_expires_on") private LocalDate piExpiresOn;

    @Column(columnDefinition = "TEXT") private String counties;
    @Column(columnDefinition = "TEXT") private String specialisations;

    @Column(name = "on_panel", nullable = false) @Builder.Default private boolean onPanel = true;
    @Column(name = "panel_note", columnDefinition = "TEXT") private String panelNote;

    @Column(name = "open_assignments", nullable = false) @Builder.Default private Integer openAssignments = 0;
    @Column(name = "completed_count", nullable = false) @Builder.Default private Integer completedCount = 0;
    @Column(name = "last_assigned_at") private OffsetDateTime lastAssignedAt;
    /** The expiry date the last lapse warning was about, so the sweep says it once. */
    @Column(name = "lapse_warned_for") private LocalDate lapseWarnedFor;

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

    /** Cover in force today. A policy that lapsed in March is not cover in June. */
    public boolean hasCurrentPi() {
        return piSumAssured != null && piSumAssured.signum() > 0
                && (piExpiresOn == null || !piExpiresOn.isBefore(LocalDate.now()));
    }

    /**
     * Whether their cover would meet a job of this value (FR041).
     *
     * <p>{@code >=}, not {@code >}: cover of exactly the property's value meets it.
     */
    public boolean coversValue(BigDecimal value) {
        if (!hasCurrentPi()) return false;
        return value == null || piSumAssured.compareTo(value) >= 0;
    }

    /**
     * Whether they will travel to this county.
     *
     * <p>Exact-token matching over the CSV, never a {@code LIKE} — the same rule the module matrix follows,
     * and for the same reason: "Kisumu" inside "Kisumu West" is a coincidence, not a match anybody meant.
     */
    public boolean covers(String county) {
        if (county == null || county.isBlank()) return true;
        Set<String> mine = countySet();
        return mine.isEmpty() || mine.contains(county.trim().toUpperCase());
    }

    /** Empty means "anywhere" — a valuer who has not narrowed themselves has not opted out of anywhere. */
    public Set<String> countySet() {
        if (counties == null || counties.isBlank()) return Set.of();
        return Arrays.stream(counties.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .collect(Collectors.toSet());
    }

    /**
     * The first of cover and registration to run out, or null when neither has a date.
     *
     * <p>What the lapse warning is about: a valuer whose cover ends in May and registration in June is warned
     * about May, and about June when its turn comes.
     */
    public LocalDate nextLapseOn() {
        if (piExpiresOn == null) return registeredUntil;
        if (registeredUntil == null) return piExpiresOn;
        return piExpiresOn.isBefore(registeredUntil) ? piExpiresOn : registeredUntil;
    }

    /** Assignable right now: on the panel, live, registered and covered. */
    public boolean isAvailable() {
        return onPanel && AppConstant.isLive(status) && hasCurrentPi()
                && (registeredUntil == null || !registeredUntil.isBefore(LocalDate.now()));
    }
}
