package com.hodi.common.util;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared building blocks for indexed list queries, so every module searches and pages the same way.
 *
 * <p>A free-text term is split on whitespace and ANDed as substring matches against the entity's
 * generated {@code searchText} column, which is backed by a pg_trgm GIN index. That makes the search
 * word-order independent and partial-word: "nairobi cash" finds "Cashier — Nairobi Branch". A single
 * {@code LIKE '%term%'} over the whole phrase would not.
 *
 * <p>Everything happens in Postgres — filter, sort, limit — rather than loading a table into the JVM
 * and scanning it, which is what the trigram index exists to make possible.
 */
public final class SearchSpecs {

    /** Guard against a client asking for the whole table in one page. */
    public static final int MAX_PAGE_SIZE = 200;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private SearchSpecs() {}

    /**
     * A predicate matching every whitespace-separated token in {@code term} against {@code field}.
     * Returns null when there is nothing to search for, so callers can skip it cleanly.
     */
    public static <T> Specification<T> fuzzy(String field, String term) {
        if (term == null || term.isBlank()) return null;
        List<String> tokens = new ArrayList<>();
        for (String token : term.trim().toLowerCase().split("\\s+")) {
            if (!token.isEmpty()) tokens.add(token);
        }
        if (tokens.isEmpty()) return null;

        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>(tokens.size());
            // The column is already lowercased by the database, so the term is lowered rather than the
            // column — wrapping the column in lower() would make the index unusable.
            tokens.forEach(t -> predicates.add(cb.like(root.get(field), "%" + t + "%")));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    /** Equality on a field, or null when the filter was not supplied. */
    public static <T> Specification<T> eq(String field, Object value) {
        if (value == null) return null;
        return (root, query, cb) -> cb.equal(root.get(field), value);
    }

    /** Hides soft-archived rows ({@code status = 5}) from every list. */
    public static <T> Specification<T> notArchived() {
        return (root, query, cb) -> cb.notEqual(root.get("status"), 5);
    }

    /**
     * Membership of a status set, or null when the filter was not supplied.
     *
     * <p>Separate from {@link #eq} because a lifecycle is a *set*, not a value: "active" spans New, Active,
     * Edited and Deactivating, since every update in this codebase stamps {@code STATUS_EDITED} and a row mid
     * -deactivation is still in force. Filtering a list on {@code status = 1} would silently hide every row
     * anybody had ever edited — which is most of them.
     *
     * @see com.hodi.common.dto.PagedDataRequest#effectiveStatuses()
     */
    public static <T> Specification<T> statusIn(List<Integer> statuses) {
        if (statuses == null || statuses.isEmpty()) return null;
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    /** Combines the non-null specs with AND; returns an always-true spec when all are null. */
    /**
     * An inclusive date window on one field, with either end optional.
     *
     * <p>Hand-rolled in three services before this existed, and about to be hand-rolled in several more as
     * the list screens gain date filters — which is how five slightly different interpretations of
     * "inclusive" get written. Null at either end means unbounded at that end, so "everything since March"
     * and "everything up to March" are the same call with one argument missing.
     *
     * <p>For a field that stores an instant rather than a date, the caller passes the instant: this only
     * promises to compare what it is given, and a timestamp compared against a bare date would silently drop
     * the last day.
     */
    public static <T, C extends Comparable<? super C>> Specification<T> between(
            String field, C from, C to) {
        if (from == null && to == null) return null;
        return (root, query, cb) -> {
            var path = root.<C>get(field);
            if (from == null) return cb.lessThanOrEqualTo(path, to);
            if (to == null) return cb.greaterThanOrEqualTo(path, from);
            return cb.between(path, from, to);
        };
    }

    /**
     * The same window, for a field that stores a timestamp rather than a date.
     *
     * <p>A list screen sends two days, because that is what somebody picks. Comparing those bare dates
     * against a timestamp column drops the whole of the last day — everything after midnight on it, which is
     * all of it. So both ends are widened here, once, rather than in each service that owns a timestamped
     * list.
     *
     * <p>The offset is the server's own. A tenant and its shop are in one place, and a filter that said
     * "today" in UTC while the shop's day ran on EAT would put the first three hours of every morning in the
     * wrong day.
     */
    public static <T> Specification<T> betweenDays(String field, LocalDate from, LocalDate to) {
        ZoneOffset offset = OffsetDateTime.now().getOffset();
        return between(
                field,
                from == null ? null : from.atStartOfDay().atOffset(offset),
                to == null ? null : to.atTime(LocalTime.MAX).atOffset(offset));
    }

    @SafeVarargs
    public static <T> Specification<T> allOf(Specification<T>... specs) {
        Specification<T> combined = null;
        for (Specification<T> spec : specs) {
            if (spec == null) continue;
            combined = combined == null ? spec : combined.and(spec);
        }
        return combined == null ? (root, query, cb) -> cb.conjunction() : combined;
    }

    /** Clamps page and size so a malformed or hostile request cannot ask for everything. */
    public static Pageable page(Integer page, Integer size, Sort sort) {
        int p = page == null || page < 0 ? 0 : page;
        int s = size == null || size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(p, s, sort);
    }
}
