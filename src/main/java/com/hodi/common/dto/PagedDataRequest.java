package com.hodi.common.dto;

import com.hodi.common.AppConstant;
import com.hodi.common.util.SearchSpecs;
import lombok.Data;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * Query-parameter bundle for every paged, filtered list endpoint. Bound by Spring's model-attribute
 * binding, so field names map 1:1 to query params
 * ({@code ?search=…&status=1&page=0&size=20}).
 *
 * <p>One object rather than a growing list of {@code @RequestParam}s: a list endpoint accumulates
 * filters, and each one otherwise has to be threaded through the controller signature, the service
 * signature and every call site. Module-specific filters go on a subclass — see
 * {@code UserListRequest} — so the shared shape stays the same everywhere.
 */
@Data
public class PagedDataRequest {

    /** Free-text term, tokenized server-side and matched against the trigram-indexed column. */
    private String search;

    /** Exact status match ({@code AppConstant.STATUS_*}); null means every non-archived row. */
    private Integer status;

    /**
     * Coarse lifecycle filter — {@code active} or {@code inactive}, anything else meaning no constraint.
     *
     * <p>This exists because the list screens present a lifecycle as tabs, and "active" is a set rather than a
     * value: every update stamps {@code STATUS_EDITED} and a row mid-deactivation is still in force, so a tab
     * backed by {@code status = 1} would hide most of the table. Kept separate from {@link #status} so an exact
     * match is still available to a caller that wants one.
     */
    private String lifecycle;

    /** Inclusive lower-bound day; the time of day is applied server-side. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate from;

    /** Inclusive upper-bound day; the time of day is applied server-side. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate to;

    private Integer page = 0;
    private Integer size = SearchSpecs.DEFAULT_PAGE_SIZE;

    /**
     * The statuses this request should match, or null for "no constraint beyond the archived guard".
     *
     * <p>An explicit {@link #status} wins over {@link #lifecycle} — it is the more specific request, and a caller
     * that named one status meant that status. Resolved here rather than in each service so thirteen list
     * endpoints cannot disagree about what "active" includes.
     */
    public List<Integer> effectiveStatuses() {
        if (status != null) return List.of(status);
        if (lifecycle == null) return null;
        return switch (lifecycle.trim().toLowerCase()) {
            // Mirrors AppConstant.isLive: everything except Inactive and the archived rows already excluded.
            case "active" -> List.of(
                    AppConstant.STATUS_NEW,
                    AppConstant.STATUS_ACTIVE,
                    AppConstant.STATUS_EDITED,
                    AppConstant.STATUS_DEACTIVATING);
            case "inactive" -> List.of(AppConstant.STATUS_INACTIVE);
            default -> null;
        };
    }

    /** Start of day on the lower bound, defaulting to three months back when none is supplied. */
    public LocalDateTime fromOrDefault() {
        return (from != null ? from : LocalDate.now().minusMonths(3)).atStartOfDay();
    }

    /** End of day on the upper bound, defaulting to today. */
    public LocalDateTime toOrDefault() {
        return (to != null ? to : LocalDate.now()).atTime(LocalTime.MAX);
    }

    /** Clamped so a malformed or hostile request cannot ask for the whole table. */
    public Pageable toPageable(Sort sort) {
        return SearchSpecs.page(page, size, sort);
    }
}
