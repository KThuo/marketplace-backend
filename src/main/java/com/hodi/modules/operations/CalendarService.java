package com.hodi.modules.operations;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.security.TenantScope;
import com.hodi.security.principal.AuthContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * The diary (M12).
 *
 * <h2>Mostly a projection</h2>
 *
 * <p>Viewings, valuation appointments and auctions already have times, in three different modules.
 * {@link #project} is called by those modules when a date is set or moved, and it upserts one entry per
 * source row — so the calendar shows the same fact rather than a second version of it.
 *
 * <p>The alternative was a query that unions three tables on every read. That would be correct and would
 * make "show me next week" a three-way union with three different date columns, three different scoping
 * rules and no place to put the meeting somebody wants to add by hand.
 *
 * <h2>Projected entries are not editable here</h2>
 *
 * <p>Moving a viewing means moving the viewing. An entry that could be dragged in the calendar and left the
 * site visit where it was would be a diary that lies — so a projected entry refuses edits and says where to
 * make them.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalendarService {

    private final CalendarEntryRepository repository;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record EntryResponse(
            String reference,
            String sourceType,
            String sourceRef,
            String title,
            String detail,
            String location,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            boolean allDay,
            String tenantName,
            String ownerName,
            String state,
            /** False for anything projected from another module — see the class comment. */
            boolean editable) {}

    public record SaveEntryRequest(
            @NotBlank(message = "Give it a title") @Size(max = 255) String title,
            String detail,
            @Size(max = 255) String location,
            @NotNull(message = "When?") OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            Boolean allDay) {}

    @Getter
    @Setter
    public static class EntryListRequest extends PagedDataRequest {
        /**
         * The window comes from the inherited {@code from}/{@code to} day range rather than from two
         * timestamps of its own. A calendar is asked for in days — "this week", "next month" — and a second
         * pair of date fields on the same request is two ways to say one thing.
         */
        private String sourceType;
        /** When true, only the caller's own entries rather than the whole organisation's. */
        private Boolean mine;
    }

    // ── what the other modules call ───────────────────────────────────────────

    /**
     * Records that something is happening, or moves it if it already was.
     *
     * <p>Idempotent by (source type, source id): the modules call it on every change, and a projection that
     * duplicated would put the same viewing in the diary twice.
     *
     * <p>Never throws into the caller. A viewing being confirmed must not fail because a diary entry could
     * not be written — the diary is a convenience over facts that live elsewhere, and losing an entry is
     * recoverable in a way that losing the viewing is not.
     */
    @Transactional
    public void project(String sourceType, Long sourceId, String sourceRef,
                        String title, String detail, String location,
                        OffsetDateTime startsAt, Long tenantId, String tenantName,
                        Long ownerUserId, String ownerName) {
        try {
            if (startsAt == null) {
                // Nothing to put in a diary yet. A requested viewing with no agreed time is not an event.
                repository.findBySourceTypeAndSourceId(sourceType, sourceId)
                        .ifPresent(repository::delete);
                return;
            }
            CalendarEntry entry = repository.findBySourceTypeAndSourceId(sourceType, sourceId)
                    .orElseGet(() -> CalendarEntry.builder()
                            .reference(RrnGenerator.generate("CE"))
                            .sourceType(sourceType)
                            .sourceId(sourceId)
                            .createdBy(AuthContext.username())
                            .build());
            entry.setSourceRef(sourceRef);
            entry.setTitle(title);
            entry.setDetail(detail);
            entry.setLocation(location);
            entry.setStartsAt(startsAt);
            entry.setTenantId(tenantId);
            entry.setTenantName(tenantName);
            entry.setOwnerUserId(ownerUserId);
            entry.setOwnerName(ownerName);
            entry.setUpdatedBy(AuthContext.username());
            repository.save(entry);
        } catch (RuntimeException e) {
            log.warn("Could not project {} {} into the calendar: {}", sourceType, sourceRef,
                    e.getMessage());
        }
    }

    /** Marks a projected entry done or cancelled when its source reaches an end state. */
    @Transactional
    public void closeProjection(String sourceType, Long sourceId, boolean cancelled) {
        try {
            repository.findBySourceTypeAndSourceId(sourceType, sourceId).ifPresent(entry -> {
                entry.setState(cancelled
                        ? OperationsConstants.ENTRY_CANCELLED : OperationsConstants.ENTRY_DONE);
                repository.save(entry);
            });
        } catch (RuntimeException e) {
            log.warn("Could not close the calendar entry for {} {}: {}", sourceType, sourceId,
                    e.getMessage());
        }
    }

    // ── the diary itself ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<EntryResponse> list(EntryListRequest request) {
        Specification<CalendarEntry> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("sourceType", request.getSourceType() == null
                        || request.getSourceType().isBlank()
                        ? null : request.getSourceType().trim().toUpperCase(Locale.ROOT)),
                // The organisation's diary, scoped the way everything else is.
                TenantScope.restrict("tenantId"),
                Boolean.TRUE.equals(request.getMine())
                        ? SearchSpecs.eq("ownerUserId", AuthContext.requireUserId())
                        : null,
                request.getFrom() == null ? null
                        : (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("startsAt"),
                                request.getFrom().atStartOfDay().atOffset(ZoneOffset.UTC)),
                // Exclusive on the day after, so "to 30 June" includes everything on 30 June.
                request.getTo() == null ? null
                        : (root, query, cb) -> cb.lessThan(root.get("startsAt"),
                                request.getTo().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)));
        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.ASC, "startsAt")));
        return PagedResponse.from(page, this::toResponse);
    }

    @Transactional
    public EntryResponse create(SaveEntryRequest request) {
        CalendarEntry entry = CalendarEntry.builder()
                .reference(RrnGenerator.generate("CE"))
                .sourceType(OperationsConstants.SOURCE_MANUAL)
                .tenantId(TenantScope.ownTenantId())
                .ownerUserId(AuthContext.userId())
                .ownerName(AuthContext.current().map(p -> p.getFullName()).orElse(null))
                .createdBy(AuthContext.username())
                .build();
        apply(entry, request);
        CalendarEntry saved = repository.save(entry);
        audit.record(AppConstant.ACTION_CREATE, "CalendarEntry", saved.getId(), null, snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public EntryResponse update(String reference, SaveEntryRequest request) {
        CalendarEntry entry = require(reference);
        assertManual(entry);
        String before = snapshot(entry);
        apply(entry, request);
        entry.setUpdatedBy(AuthContext.username());
        CalendarEntry saved = repository.save(entry);
        audit.record(AppConstant.ACTION_UPDATE, "CalendarEntry", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public void archive(String reference) {
        CalendarEntry entry = require(reference);
        assertManual(entry);
        entry.setStatus(AppConstant.STATUS_DELETED);
        entry.setStatusFlag(AppConstant.FLAG_DELETED);
        entry.setUpdatedBy(AuthContext.username());
        repository.save(entry);
        audit.record(AppConstant.ACTION_DELETE, "CalendarEntry", entry.getId(), null, snapshot(entry));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void apply(CalendarEntry entry, SaveEntryRequest request) {
        entry.setTitle(request.title().trim());
        entry.setDetail(blankToNull(request.detail()));
        entry.setLocation(blankToNull(request.location()));
        entry.setStartsAt(request.startsAt());
        entry.setEndsAt(request.endsAt());
        entry.setAllDay(Boolean.TRUE.equals(request.allDay()));
        if (entry.getEndsAt() != null && entry.getEndsAt().isBefore(entry.getStartsAt())) {
            throw new HodiException("It cannot end before it starts.", HttpStatus.BAD_REQUEST);
        }
    }

    /** Where to make the change, rather than a bare refusal. */
    private void assertManual(CalendarEntry entry) {
        if (entry.isManual()) return;
        String where = switch (entry.getSourceType()) {
            case OperationsConstants.SOURCE_SITE_VISIT -> "the viewing itself";
            case OperationsConstants.SOURCE_VALUATION -> "the valuation";
            case OperationsConstants.SOURCE_AUCTION -> "the auction lot";
            default -> "the record it came from";
        };
        throw new HodiException(
                "This is a diary entry for something else — change " + where + " and it moves here too.",
                HttpStatus.CONFLICT);
    }

    private CalendarEntry require(String reference) {
        CalendarEntry entry = repository.findByReference(reference)
                .orElseThrow(() -> new ResourceNotFoundException("Diary entry", reference));
        TenantScope.assertAllowed(entry.getTenantId());
        return entry;
    }

    private EntryResponse toResponse(CalendarEntry e) {
        return new EntryResponse(e.getReference(), e.getSourceType(), e.getSourceRef(), e.getTitle(),
                e.getDetail(), e.getLocation(), e.getStartsAt(), e.getEndsAt(), e.isAllDay(),
                e.getTenantName(), e.getOwnerName(), e.getState(), e.isManual());
    }

    private static String snapshot(CalendarEntry e) {
        return "{\"reference\":\"%s\",\"title\":\"%s\",\"startsAt\":\"%s\"}".formatted(
                e.getReference(), e.getTitle(), String.valueOf(e.getStartsAt()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
