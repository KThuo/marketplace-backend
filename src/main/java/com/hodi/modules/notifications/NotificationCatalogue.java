package com.hodi.modules.notifications;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.Placeholders;
import com.hodi.enums.ConfigKey;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * The catalogue: what the platform says, to whom, whether, where, and in which words (plan §3.2).
 *
 * <h2>Three layers</h2>
 *
 * <p>The platform's row is the default. An organisation — a seller, or the bank — may switch an event
 * off or choose channels for the events that concern it, and may reword them when
 * {@code notify.organisation.wording.enabled} says so; a blank override field means "as the platform
 * says". Consent is the third layer and the last word: the person's own channels, intersected.
 *
 * <p>Wording is versioned by the audit trail — every change records the words before and after — rather
 * than by a table of its own; a message once sent is kept as sent in the log.
 */
@Service
@RequiredArgsConstructor
public class NotificationCatalogue {

    public static final Set<String> CHANNELS = Set.of(AppConstant.CONSENT_CHANNEL_EMAIL,
            AppConstant.CONSENT_CHANNEL_SMS, AppConstant.CONSENT_CHANNEL_IN_APP);

    private final NotificationEventRepository events;
    private final NotificationEventOverrideRepository overrides;
    private final ConfigurationService configs;
    private final AuditService audit;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    /** What the sender gets: whether to send, where, and the words to fill. */
    public record Resolved(String code, String purpose, boolean enabled, Set<String> channels, String subject, String line,
                           boolean composed) {}

    public record EventRow(String code, String audience, String purpose, boolean enabled, List<String> channels,
                           String subject, String line, String description, List<String> placeholders, boolean composed,
                           OffsetDateTime updatedAt, String updatedBy,
                           /** The organisation's own answer, when reading as one; null fields inherit. */
                           OverrideRow override,
                           /** What is in force for that organisation, layers applied. */
                           Boolean effectiveEnabled, List<String> effectiveChannels) {}

    public record OverrideRow(Boolean enabled, List<String> channels, String subject, String line,
                              OffsetDateTime updatedAt, String updatedBy) {}

    public record SaveEventRequest(Boolean enabled, List<String> channels, String subject, String line) {}

    /** Null or absent fields clear the override for that field — "as the platform says". */
    public record SaveOverrideRequest(Boolean enabled, List<String> channels, String subject, String line) {}

    // ── resolving, for the sender ─────────────────────────────────────────────

    /**
     * The event as it applies for an organisation. Empty when the code is unknown — the catalogue is the
     * source of truth, and an event nobody catalogued is not sent.
     */
    @Transactional(readOnly = true)
    public Optional<Resolved> resolve(String code, Long tenantId, Long institutionId) {
        return events.findById(code).map(event -> {
            NotificationEventOverride override = tenantId == null && institutionId == null ? null
                    : overrides.findFor(code, tenantId, institutionId).orElse(null);
            return apply(event, override, organisationsMayReword());
        });
    }

    /** The layering, said once, as a pure function so a test can hold it. */
    static Resolved apply(NotificationEvent event, NotificationEventOverride override, boolean mayReword) {
        boolean enabled = event.isEnabled();
        Set<String> channels = channelSet(event.getChannels());
        String subject = event.getSubject();
        String line = event.getLine();
        if (override != null) {
            if (override.getEnabled() != null) enabled = enabled && override.getEnabled();
            if (override.getChannels() != null && !override.getChannels().isBlank()) {
                channels = new LinkedHashSet<>(channels);
                channels.retainAll(channelSet(override.getChannels()));
            }
            if (mayReword && !event.isComposed()) {
                if (override.getSubject() != null && !override.getSubject().isBlank()) subject = override.getSubject();
                if (override.getLine() != null && !override.getLine().isBlank()) line = override.getLine();
            }
        }
        return new Resolved(event.getCode(), event.getPurpose(), enabled, channels, subject, line, event.isComposed());
    }

    public boolean organisationsMayReword() {
        return configs.getBoolean(ConfigKey.NOTIFY_ORGANISATION_WORDING_ENABLED);
    }

    // ── the platform's catalogue ──────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<EventRow> all() {
        return events.findAllByOrderBySortOrderAscCodeAsc().stream().map(e -> toRow(e, null, false)).toList();
    }

    @Transactional
    public EventRow save(String code, SaveEventRequest request) {
        NotificationEvent event = require(code);
        String before = snapshot(event.isEnabled(), event.getChannels(), event.getSubject(), event.getLine());
        if (request.enabled() != null) event.setEnabled(request.enabled());
        if (request.channels() != null) event.setChannels(channelCsv(request.channels(), true));
        if (!event.isComposed()) {
            if (request.subject() != null && !request.subject().isBlank()) event.setSubject(checkWording(request.subject().trim(), event, 255));
            if (request.line() != null && !request.line().isBlank()) event.setLine(checkWording(request.line().trim(), event, 4000));
        }
        event.setUpdatedAt(OffsetDateTime.now());
        event.setUpdatedBy(AuthContext.username());
        events.save(event);
        audit.record(AppConstant.ACTION_UPDATE, "NotificationEvent", 0L, before,
                snapshot(event.isEnabled(), event.getChannels(), event.getSubject(), event.getLine()));
        return toRow(event, null, false);
    }

    // ── an organisation's overrides ───────────────────────────────────────────

    /** The catalogue as the caller's organisation sees it, with its own answers and what is in force. */
    @Transactional(readOnly = true)
    public List<EventRow> mine() {
        UserPrincipal caller = requireOrganisation();
        boolean mayReword = organisationsMayReword();
        Map<String, NotificationEventOverride> own = new HashMap<>();
        for (NotificationEventOverride o : overrides.findAllFor(caller.getTenantId(), caller.getInstitutionId())) own.put(o.getEventCode(), o);
        return events.findAllByOrderBySortOrderAscCodeAsc().stream()
                .filter(e -> concerns(e, caller))
                .map(e -> toRow(e, own.get(e.getCode()), mayReword))
                .toList();
    }

    @Transactional
    public EventRow saveMine(String code, SaveOverrideRequest request) {
        UserPrincipal caller = requireOrganisation();
        NotificationEvent event = require(code);
        if (!concerns(event, caller)) {
            throw new HodiException("That event does not concern your organisation.", HttpStatus.FORBIDDEN);
        }
        boolean mayReword = organisationsMayReword();
        if (!mayReword && ((request.subject() != null && !request.subject().isBlank()) || (request.line() != null && !request.line().isBlank()))) {
            throw new HodiException("The platform has not opened the wording to organisations.", HttpStatus.FORBIDDEN);
        }
        NotificationEventOverride row = overrides.findFor(code, caller.getTenantId(), caller.getInstitutionId())
                .orElseGet(() -> NotificationEventOverride.builder().eventCode(code)
                        .tenantId(caller.getTenantId()).institutionId(caller.getTenantId() == null ? caller.getInstitutionId() : null)
                        .build());
        String before = snapshot(row.getEnabled(), row.getChannels(), row.getSubject(), row.getLine());
        row.setEnabled(request.enabled());
        row.setChannels(request.channels() == null || request.channels().isEmpty() ? null : channelCsv(request.channels(), false));
        row.setSubject(request.subject() == null || request.subject().isBlank() ? null : checkWording(request.subject().trim(), event, 255));
        row.setLine(request.line() == null || request.line().isBlank() ? null : checkWording(request.line().trim(), event, 4000));
        row.setUpdatedAt(OffsetDateTime.now());
        row.setUpdatedBy(AuthContext.username());
        if (row.getEnabled() == null && row.getChannels() == null && row.getSubject() == null && row.getLine() == null) {
            if (row.getId() != null) overrides.delete(row);
            audit.record(AppConstant.ACTION_UPDATE, "NotificationEventOverride", row.getId() == null ? 0L : row.getId(), before, "cleared");
            return toRow(event, null, mayReword);
        }
        overrides.save(row);
        audit.record(AppConstant.ACTION_UPDATE, "NotificationEventOverride", row.getId(), before,
                snapshot(row.getEnabled(), row.getChannels(), row.getSubject(), row.getLine()));
        return toRow(event, row, mayReword);
    }

    /** Which events an organisation has a say in: what it sends its buyers, and what its own staff receive. */
    static boolean concerns(NotificationEvent event, UserPrincipal caller) {
        return switch (event.getAudience()) {
            case "PLATFORM_STAFF", "VALUER" -> false;
            case "REQUESTER" -> true;
            case "SELLER_STAFF" -> caller.getTenantId() != null;
            default -> true; // BUYER: the organisation's buyers
        };
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private NotificationEvent require(String code) {
        return events.findById(code == null ? "" : code.trim().toUpperCase())
                .orElseThrow(() -> new ResourceNotFoundException("Notification", code));
    }

    private static UserPrincipal requireOrganisation() {
        UserPrincipal caller = AuthContext.require();
        if (caller.getTenantId() == null && caller.getInstitutionId() == null) {
            throw new HodiException("Only an organisation has overrides; the platform edits the catalogue itself.", HttpStatus.FORBIDDEN);
        }
        return caller;
    }

    /** A wording that names a placeholder the event does not fill would send a sentence with a hole in it. */
    private static String checkWording(String text, NotificationEvent event, int max) {
        if (text.length() > max) throw new HodiException("That is too long.", HttpStatus.BAD_REQUEST);
        Set<String> known = new HashSet<>(Arrays.asList(event.getPlaceholders().split(",")));
        for (String name : Placeholders.namesIn(text)) {
            if (!known.contains(name)) {
                throw new HodiException("This message does not have a {{" + name + "}}. It has: " + event.getPlaceholders() + ".",
                        HttpStatus.BAD_REQUEST);
            }
        }
        return text;
    }

    private static Set<String> channelSet(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null) return out;
        for (String c : csv.split(",")) {
            String v = c.trim().toUpperCase();
            if (CHANNELS.contains(v)) out.add(v);
        }
        return out;
    }

    private static String channelCsv(List<String> channels, boolean atLeastOne) {
        Set<String> set = channelSet(String.join(",", channels));
        if (atLeastOne && set.isEmpty()) throw new HodiException("Choose at least one channel.", HttpStatus.BAD_REQUEST);
        return String.join(",", set);
    }

    private static String snapshot(Boolean enabled, String channels, String subject, String line) {
        return "enabled=" + enabled + " channels=" + channels + " subject=" + subject + " line=" + line;
    }

    private EventRow toRow(NotificationEvent e, NotificationEventOverride o, boolean mayReword) {
        Resolved effective = o == null ? null : apply(e, o, mayReword);
        return new EventRow(e.getCode(), e.getAudience(), e.getPurpose(), e.isEnabled(), new ArrayList<>(channelSet(e.getChannels())),
                e.getSubject(), e.getLine(), e.getDescription(),
                e.getPlaceholders().isBlank() ? List.of() : Arrays.asList(e.getPlaceholders().split(",")),
                e.isComposed(), e.getUpdatedAt(), e.getUpdatedBy(),
                o == null ? null : new OverrideRow(o.getEnabled(), o.getChannels() == null ? null : new ArrayList<>(channelSet(o.getChannels())),
                        o.getSubject(), o.getLine(), o.getUpdatedAt(), o.getUpdatedBy()),
                effective == null ? null : effective.enabled(),
                effective == null ? null : new ArrayList<>(effective.channels()));
    }
}
