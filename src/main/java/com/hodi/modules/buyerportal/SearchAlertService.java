package com.hodi.modules.buyerportal;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.modules.consent.ConsentService;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Saved searches, which are alerts (BRD FR022–FR024).
 *
 * <p>Identity-scoped exactly like the shortlist: every method starts from the signed-in person and there is
 * no endpoint that takes a user id. Ids here <em>are</em> HashId-encoded with the caller's own salt, unlike
 * the shortlist's references — an alert is the caller's own row rather than a marketplace object, so it was
 * never encoded under the public salt and cannot be confused with one.
 *
 * <h2>What this service will not do</h2>
 *
 * <p>It does not send anything. {@link SearchAlertDispatcher} does, on a schedule, and only through the
 * channels the consent store says are open. What this class contributes to that is
 * {@link AlertResponse#deliverable} — so somebody who has switched every channel off is told that their
 * alert is saved but silent, on the screen where they created it, rather than discovering it by never
 * hearing anything.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchAlertService {

    /** One person's standing searches. A limit, because each one is work the dispatcher does forever. */
    private static final int MAX_ALERTS = 20;

    private final SearchAlertRepository repository;
    private final ConsentService consent;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record AlertResponse(
            String id,
            String name,
            String searchTerm,
            String propertyType,
            String county,
            String town,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Short minBedrooms,
            Short maxBedrooms,
            boolean greenOnly,
            String frequency,
            boolean running,
            /** False when every channel is switched off — the alert is saved and cannot reach anybody. */
            boolean deliverable,
            /** The channels it would arrive on today, from the consent store. */
            List<String> channels,
            OffsetDateTime lastRunAt,
            OffsetDateTime nextRunAt,
            Integer lastMatchCount,
            Integer totalSent,
            String lastOutcome,
            OffsetDateTime createdAt) {}

    public record SaveAlertRequest(
            @NotBlank(message = "Give this search a name you will recognise")
            @Size(max = 120, message = "That name is too long")
            String name,
            String searchTerm,
            String propertyType,
            String county,
            String town,
            BigDecimal minPrice,
            BigDecimal maxPrice,
            Short minBedrooms,
            Short maxBedrooms,
            Boolean greenOnly,
            /** {@code INSTANT}, {@code DAILY} or {@code WEEKLY}. Anything else is refused. */
            String frequency) {}

    // ── the person's own alerts ───────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<AlertResponse> mine(PagedDataRequest request) {
        Long userId = AuthContext.requireUserId();
        // Asked once for the whole page rather than per row: the answer is the same for every alert this
        // person holds, because consent is per person and purpose, not per search.
        Set<String> channels = consent.channelsFor(userId, AppConstant.CONSENT_PROPERTY_ALERTS);
        var page = repository.findMine(userId,
                request.toPageable(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(page, alert -> toResponse(alert, channels));
    }

    @Transactional(readOnly = true)
    public long myRunningCount() {
        return repository.countRunning(AuthContext.requireUserId());
    }

    @Transactional
    public AlertResponse create(SaveAlertRequest request) {
        Long userId = AuthContext.requireUserId();
        if (repository.countRunning(userId) >= MAX_ALERTS) {
            throw new HodiException(
                    "You already have " + MAX_ALERTS + " saved searches running. Pause or delete one first.",
                    HttpStatus.CONFLICT);
        }

        SearchAlert alert = SearchAlert.builder()
                .userId(userId)
                .createdBy(AuthContext.username())
                .updatedBy(AuthContext.username())
                // The window opens now. A search saved today reports what appears from today, not the
                // hundred listings the buyer has just finished scrolling past.
                .lastRunAt(OffsetDateTime.now())
                .build();
        apply(alert, request);
        alert.setNextRunAt(alert.scheduleAfter(OffsetDateTime.now()));

        SearchAlert saved = repository.save(alert);
        return toResponse(saved, consent.channelsFor(userId, AppConstant.CONSENT_PROPERTY_ALERTS));
    }

    @Transactional
    public AlertResponse update(String hashId, SaveAlertRequest request) {
        SearchAlert alert = mineOrThrow(hashId);
        apply(alert, request);
        alert.setUpdatedBy(AuthContext.username());
        alert.setStatus(AppConstant.STATUS_EDITED);
        alert.setStatusFlag(AppConstant.FLAG_EDITED);
        alert.setNextRunAt(alert.scheduleAfter(OffsetDateTime.now()));
        return toResponse(repository.save(alert),
                consent.channelsFor(alert.getUserId(), AppConstant.CONSENT_PROPERTY_ALERTS));
    }

    /**
     * Pauses or resumes an alert.
     *
     * <p>A pause is {@code STATUS_INACTIVE} rather than a delete, because "when did they stop asking to be
     * told" is a question that gets asked about a standing instruction — and because resuming should not
     * mean rebuilding the criteria.
     */
    @Transactional
    public AlertResponse setRunning(String hashId, boolean running) {
        SearchAlert alert = mineOrThrow(hashId);
        alert.setStatus(running ? AppConstant.STATUS_ACTIVE : AppConstant.STATUS_INACTIVE);
        alert.setStatusFlag(running ? AppConstant.FLAG_ACTIVE : AppConstant.FLAG_INACTIVE);
        alert.setUpdatedBy(AuthContext.username());
        if (running) {
            // Resuming re-opens the window from now: a month of listings the buyer did not hear about while
            // it was paused is a mail nobody wants to receive.
            alert.setLastRunAt(OffsetDateTime.now());
            alert.setNextRunAt(alert.scheduleAfter(OffsetDateTime.now()));
        }
        return toResponse(repository.save(alert),
                consent.channelsFor(alert.getUserId(), AppConstant.CONSENT_PROPERTY_ALERTS));
    }

    @Transactional
    public void delete(String hashId) {
        SearchAlert alert = mineOrThrow(hashId);
        alert.setStatus(AppConstant.STATUS_DELETED);
        alert.setStatusFlag(AppConstant.FLAG_DELETED);
        alert.setUpdatedBy(AuthContext.username());
        repository.save(alert);
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Loads an alert, or refuses.
     *
     * <p>The user id is part of the query, not a check after loading: "load it, then decide whether it is
     * theirs" is the shape that leaks somebody else's row the day a branch is refactored. A wrong id and
     * another person's id are both a 404 here, which is also the answer that says least.
     */
    private SearchAlert mineOrThrow(String hashId) {
        Long id = HashIdUtil.decodeId(hashId);
        return repository.findMineById(id, AuthContext.requireUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Saved search", hashId));
    }

    private void apply(SearchAlert alert, SaveAlertRequest request) {
        alert.setName(request.name().trim());
        alert.setSearchTerm(blankToNull(request.searchTerm()));
        alert.setPropertyType(blankToNull(request.propertyType()));
        alert.setCounty(blankToNull(request.county()));
        alert.setTown(blankToNull(request.town()));
        alert.setMinPrice(request.minPrice());
        alert.setMaxPrice(request.maxPrice());
        alert.setMinBedrooms(request.minBedrooms());
        alert.setMaxBedrooms(request.maxBedrooms());
        alert.setGreenOnly(Boolean.TRUE.equals(request.greenOnly()));
        alert.setFrequency(frequency(request.frequency()));

        // The database CHECKs say the same; refused here so the person reads a sentence rather than a
        // constraint name.
        if (alert.getMinPrice() != null && alert.getMaxPrice() != null
                && alert.getMaxPrice().compareTo(alert.getMinPrice()) < 0) {
            throw new HodiException("The most you will pay cannot be less than the least.",
                    HttpStatus.BAD_REQUEST);
        }
        if (alert.getMinBedrooms() != null && alert.getMaxBedrooms() != null
                && alert.getMaxBedrooms() < alert.getMinBedrooms()) {
            throw new HodiException("The bedroom range is the wrong way round.", HttpStatus.BAD_REQUEST);
        }
    }

    /** An allowlist rather than the string as given — the column has a CHECK and this is its readable half. */
    private static String frequency(String requested) {
        String value = requested == null ? "" : requested.trim().toUpperCase();
        return switch (value) {
            case AppConstant.ALERT_INSTANT, AppConstant.ALERT_WEEKLY -> value;
            default -> AppConstant.ALERT_DAILY;
        };
    }

    private AlertResponse toResponse(SearchAlert alert, Set<String> channels) {
        return new AlertResponse(
                HashIdUtil.encodeId(alert.getId()),
                alert.getName(),
                alert.getSearchTerm(),
                alert.getPropertyType(),
                alert.getCounty(),
                alert.getTown(),
                alert.getMinPrice(),
                alert.getMaxPrice(),
                alert.getMinBedrooms(),
                alert.getMaxBedrooms(),
                alert.isGreenOnly(),
                alert.getFrequency(),
                alert.isRunning(),
                !channels.isEmpty(),
                List.copyOf(channels),
                alert.getLastRunAt(),
                alert.getNextRunAt(),
                alert.getLastMatchCount(),
                alert.getTotalSent(),
                alert.getLastOutcome(),
                alert.getCreatedAt());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
