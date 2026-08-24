package com.hodi.modules.consent;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.audit.AuditService;
import com.hodi.security.principal.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The consent store (plan §3.8, BRD FR004–FR005).
 *
 * <h2>Two readers, and they want different things</h2>
 *
 * <p>A person reads {@link #mine()} to see and change their own position. The alert dispatcher reads
 * {@link #channelsFor} to decide whether it may send anything at all. Nothing else in the application is
 * allowed to decide that question: a service that looked at a user's email address and sent to it would be
 * routing around the store the regulation asks for.
 *
 * <h2>Every purpose always exists</h2>
 *
 * <p>{@link #mine()} materialises any missing (channel, purpose) pair rather than returning a partial grid.
 * A preferences screen that renders only rows that exist would silently hide a purpose the person has never
 * been asked about, and "we never asked" reads to a buyer exactly like "we do not do that". The
 * materialised row is an explicit recorded "no", with its source saying it was assumed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsentService {

    /** Every combination a person can hold a position on. The grid the preferences screen renders. */
    public static final List<String> CHANNELS =
            List.of(AppConstant.CONSENT_CHANNEL_EMAIL, AppConstant.CONSENT_CHANNEL_SMS);
    public static final List<String> PURPOSES = List.of(
            AppConstant.CONSENT_TRANSACTIONAL,
            AppConstant.CONSENT_PROPERTY_ALERTS,
            AppConstant.CONSENT_PROMOTIONAL);

    private static final Map<String, String> PURPOSE_LABELS = Map.of(
            AppConstant.CONSENT_TRANSACTIONAL, "Account and transaction messages",
            AppConstant.CONSENT_PROPERTY_ALERTS, "New listings matching my saved searches",
            AppConstant.CONSENT_PROMOTIONAL, "Offers and news from Hodi");

    private static final Map<String, String> PURPOSE_NOTES = Map.of(
            AppConstant.CONSENT_TRANSACTIONAL,
            "Password resets, confirmations and receipts. These cannot be switched off — they are how we "
                    + "carry out things you asked for.",
            AppConstant.CONSENT_PROPERTY_ALERTS,
            "Sent only when a search you saved finds something new.",
            AppConstant.CONSENT_PROMOTIONAL,
            "Occasional. Switching this off never affects the other two.");

    private final ConsentRepository repository;
    private final ConsentHistoryRepository history;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record ConsentRow(
            String purpose,
            String purposeLabel,
            String note,
            /** True when declining is not on offer — the database refuses it and the switch is shown fixed. */
            boolean mandatory,
            /** Channel code to whether it is granted, for every channel. */
            Map<String, Boolean> channels,
            OffsetDateTime lastChangedAt) {}

    public record ConsentHistoryRow(
            String purpose,
            String purposeLabel,
            String channel,
            Boolean previousGranted,
            boolean newGranted,
            String source,
            OffsetDateTime changedAt,
            String changedBy) {}

    /**
     * @param channels channel code to the new position; channels left out are unchanged
     */
    public record UpdateConsentRequest(String purpose, Map<String, Boolean> channels) {}

    // ── the person's own view ─────────────────────────────────────────────────

    @Transactional
    public List<ConsentRow> mine() {
        return forUser(AuthContext.requireUserId());
    }

    @Transactional
    public List<ConsentRow> forUser(Long userId) {
        Map<String, ConsentPreference> existing = new LinkedHashMap<>();
        for (ConsentPreference row : repository.findByUserIdOrderByPurposeAscChannelAsc(userId)) {
            existing.put(key(row.getChannel(), row.getPurpose()), row);
        }

        List<ConsentRow> out = new ArrayList<>(PURPOSES.size());
        for (String purpose : PURPOSES) {
            Map<String, Boolean> channels = new LinkedHashMap<>();
            OffsetDateTime lastChanged = null;
            for (String channel : CHANNELS) {
                ConsentPreference row = existing.get(key(channel, purpose));
                if (row == null) {
                    row = materialise(userId, channel, purpose);
                }
                channels.put(channel, row.isGranted());
                if (lastChanged == null || row.getCapturedAt().isAfter(lastChanged)) {
                    lastChanged = row.getCapturedAt();
                }
            }
            out.add(new ConsentRow(purpose, PURPOSE_LABELS.get(purpose), PURPOSE_NOTES.get(purpose),
                    AppConstant.CONSENT_TRANSACTIONAL.equals(purpose), channels, lastChanged));
        }
        return out;
    }

    /**
     * Changes one purpose across the channels named in the request.
     *
     * <p>One purpose per call rather than the whole grid: a request that carried every switch would make
     * "which one did they actually change" unanswerable from the request, and that is the question the
     * history exists to answer.
     */
    @Transactional
    public List<ConsentRow> update(UpdateConsentRequest request) {
        Long userId = AuthContext.requireUserId();
        String purpose = request.purpose() == null ? "" : request.purpose().trim().toUpperCase();

        if (!PURPOSES.contains(purpose)) {
            throw new HodiException("There is no such preference.", HttpStatus.BAD_REQUEST);
        }
        if (AppConstant.CONSENT_TRANSACTIONAL.equals(purpose)) {
            // Refused here so the person gets a sentence rather than a constraint violation, but the CHECK
            // is what makes it true — this branch is the explanation, not the enforcement.
            throw new HodiException(
                    "Account and transaction messages cannot be switched off — they carry out things you "
                            + "asked for.", HttpStatus.BAD_REQUEST);
        }
        Map<String, Boolean> wanted = request.channels() == null ? Map.of() : request.channels();

        String ip = remoteIp();
        String userAgent = userAgent();
        List<String> changed = new ArrayList<>(2);

        for (Map.Entry<String, Boolean> entry : wanted.entrySet()) {
            String channel = entry.getKey() == null ? "" : entry.getKey().trim().toUpperCase();
            if (!CHANNELS.contains(channel) || entry.getValue() == null) continue;

            ConsentPreference row = repository.findByUserIdAndChannelAndPurpose(userId, channel, purpose)
                    .orElseGet(() -> materialise(userId, channel, purpose));
            if (row.isGranted() == entry.getValue()) continue;

            row.setGranted(entry.getValue());
            row.setSource(AppConstant.CONSENT_SOURCE_PREFERENCES);
            row.setCapturedAt(OffsetDateTime.now());
            row.setCapturedIp(ip);
            row.setCapturedUserAgent(userAgent);
            row.setUpdatedBy(AuthContext.username());
            repository.save(row);
            changed.add(channel + "=" + entry.getValue());
        }

        if (!changed.isEmpty()) {
            // A second copy in the audit trail, which is also append-only. The consent history answers "what
            // did this person agree to"; the audit trail answers "what happened on the platform that day",
            // and a compliance question usually starts from one and ends at the other.
            audit.record(AppConstant.AUDIT_CONSENT_UPDATE, "ConsentPreference", userId, null,
                    purpose + " " + String.join(", ", changed));
        }
        return forUser(userId);
    }

    @Transactional(readOnly = true)
    public List<ConsentHistoryRow> myHistory(int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        return history.findByUserIdOrderByChangedAtDesc(
                        AuthContext.requireUserId(), PageRequest.of(0, capped))
                .map(h -> new ConsentHistoryRow(
                        h.getPurpose(),
                        PURPOSE_LABELS.getOrDefault(h.getPurpose(), h.getPurpose()),
                        h.getChannel(),
                        h.getPreviousGranted(),
                        h.isNewGranted(),
                        h.getSource(),
                        h.getChangedAt(),
                        h.getChangedBy()))
                .getContent();
    }

    // ── what the dispatcher asks ──────────────────────────────────────────────

    /**
     * The channels this person has agreed to be contacted on for this purpose.
     *
     * <p>Empty means send nothing. Not "fall back to email" — an empty result is a recorded refusal, and the
     * only correct handling of it is silence.
     */
    @Transactional(readOnly = true)
    public Set<String> channelsFor(Long userId, String purpose) {
        return Set.copyOf(repository.grantedChannels(userId, purpose));
    }

    // ── capture at registration ───────────────────────────────────────────────

    /**
     * Records a new account's opening position, in the same transaction as the registration.
     *
     * <p>The transactional consents are granted because the CHECK requires them and because they are how the
     * verification email the registrant is about to receive is sent. Everything else follows what they were
     * actually asked, which today is one tick about property alerts — never a pre-ticked box, and never a
     * marketing consent smuggled in behind an alerts one.
     */
    @Transactional
    public void captureAtRegistration(Long userId, boolean propertyAlerts) {
        String ip = remoteIp();
        String userAgent = userAgent();
        for (String channel : CHANNELS) {
            for (String purpose : PURPOSES) {
                boolean granted = AppConstant.CONSENT_TRANSACTIONAL.equals(purpose)
                        || (AppConstant.CONSENT_PROPERTY_ALERTS.equals(purpose) && propertyAlerts);
                repository.save(ConsentPreference.builder()
                        .userId(userId)
                        .channel(channel)
                        .purpose(purpose)
                        .granted(granted)
                        .source(AppConstant.CONSENT_SOURCE_REGISTRATION)
                        .capturedAt(OffsetDateTime.now())
                        .capturedIp(ip)
                        .capturedUserAgent(userAgent)
                        .createdBy("self-registration")
                        .updatedBy("self-registration")
                        .build());
            }
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Writes the missing row as a recorded "no", except where the CHECK requires otherwise.
     *
     * <p>Written rather than returned unsaved, because the history trigger fires on INSERT: the moment a
     * position exists it has a first history row saying where it came from, and {@code ASSUMED} is an honest
     * name for a position nobody was asked about.
     */
    private ConsentPreference materialise(Long userId, String channel, String purpose) {
        boolean mandatory = AppConstant.CONSENT_TRANSACTIONAL.equals(purpose);
        return repository.save(ConsentPreference.builder()
                .userId(userId)
                .channel(channel)
                .purpose(purpose)
                .granted(mandatory)
                .source("ASSUMED")
                .capturedAt(OffsetDateTime.now())
                .createdBy(AuthContext.username())
                .updatedBy(AuthContext.username())
                .build());
    }

    private static String key(String channel, String purpose) {
        return channel + '|' + purpose;
    }

    /**
     * The caller's address, when there is a request to read it from.
     *
     * <p>Pulled from the request context rather than passed down through every signature: the only callers
     * that matter are HTTP ones, and a scheduled or seeded write has no address to record — which is
     * information, so it is stored as null rather than as a placeholder.
     */
    private static String remoteIp() {
        HttpServletRequest request = currentRequest();
        if (request == null) return null;
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private static String userAgent() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getHeader("User-Agent");
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest()
                : null;
    }
}
