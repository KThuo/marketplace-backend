package com.hodi.modules.notifications;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.Placeholders;
import com.hodi.common.util.SearchSpecs;
import com.hodi.enums.ConfigKey;
import com.hodi.infra.notify.MailTemplate;
import com.hodi.infra.notify.NotifyClient;
import com.hodi.infra.notify.NotifyResult;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.consent.ConsentService;
import com.hodi.modules.users.User;
import com.hodi.modules.users.UserRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One door for every message the platform sends to a person (notifications plan §3.3, §3.4).
 *
 * <h2>What goes through it</h2>
 *
 * <p>A notice — a subject, a line, a link, what it is about — for a user or for a contact with no
 * account. For a user, the consent store is asked for the purpose; each granted channel gets a row in
 * the log and a send; and if in-app is among them, a line in their inbox. For a contact — a walk-in
 * buyer known by the phone on their booking — the notice goes by email and SMS under the transactional
 * purpose, logged the same way, with no inbox to write to.
 *
 * <h2>What the log is for</h2>
 *
 * <p>"Did the buyer get the receipt" has an answer. A send the gateway refused is FAILED and tried again
 * with backoff by {@link NotificationRetrySweep} up to a cap; a channel that was switched off is SKIPPED
 * and left alone, because it was not a failure. The body is kept as composed, so a retry says what was
 * said on the day.
 *
 * <p>Best effort, as every sender was: nothing here throws into the caller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final int SMS_MAX = 480;

    private final NotificationLogRepository logs;
    private final NotificationRepository inbox;
    private final UserRepository users;
    private final ConsentService consent;
    private final NotificationCatalogue catalogue;
    private final NotifyClient notify;
    private final MailTemplate mail;
    private final ConfigurationService configs;

    // ── what is said ──────────────────────────────────────────────────────────

    /** What the message concerns, so the page for that thing can show what was sent about it. */
    public record About(String type, Long id, String ref) {}

    /**
     * @param eventCode a coarse name until the catalogue (phase 3) gives every event its own
     * @param purpose   the consent purpose it is sent under
     * @param path      where the link goes, relative to the public URL
     */
    public record Notice(String eventCode, String purpose, String subject, String line, String path, About about) {
        public static Notice transactional(String eventCode, String subject, String line, String path, About about) {
            return new Notice(eventCode, AppConstant.CONSENT_TRANSACTIONAL, subject, line, path, about);
        }
    }

    /** Somebody with no account: the name and contact the sales office took. */
    public record Contact(String name, String email, String phone) {}

    /**
     * A catalogued event: the code names the words, the model fills them, and the organisation whose
     * business it is may have overridden whether and where it goes.
     */
    public record Event(String code, Map<String, ?> model, String path, About about, Long orgTenantId, Long orgInstitutionId) {
        public static Event of(String code, Map<String, ?> model, String path, About about) {
            return new Event(code, model, path, about, null, null);
        }
        public Event forOrganisation(Long tenantId, Long institutionId) {
            return new Event(code, model, path, about, tenantId, institutionId);
        }
    }

    // ── delivering a catalogued event ─────────────────────────────────────────

    /** A catalogued event to one user: resolved for its organisation, filled, then delivered like any notice. */
    @Transactional
    public void event(Long userId, Event event) {
        resolveNotice(event).ifPresent(resolved -> toUser(userId, resolved.notice(), resolved.channels()));
    }

    @Transactional
    public void event(List<Long> userIds, Event event) {
        resolveNotice(event).ifPresent(resolved -> {
            for (Long userId : new LinkedHashSet<>(userIds)) toUser(userId, resolved.notice(), resolved.channels());
        });
    }

    @Transactional
    public void event(Contact contact, Event event) {
        resolveNotice(event).ifPresent(resolved -> toContact(contact, resolved.notice(), resolved.channels()));
    }

    private record ResolvedNotice(Notice notice, Set<String> channels) {}

    /** The catalogue's answer for this event and organisation, or empty when it is off or unknown. */
    private Optional<ResolvedNotice> resolveNotice(Event event) {
        Optional<NotificationCatalogue.Resolved> resolved = catalogue.resolve(event.code(), event.orgTenantId(), event.orgInstitutionId());
        if (resolved.isEmpty()) {
            log.warn("Notification event {} is not in the catalogue; nothing sent", event.code());
            return Optional.empty();
        }
        NotificationCatalogue.Resolved r = resolved.get();
        if (!r.enabled() || r.channels().isEmpty()) return Optional.empty();
        Map<String, Object> model = new java.util.HashMap<>();
        if (event.model() != null) model.putAll(event.model());
        model.putIfAbsent("platform", platformName());
        return Optional.of(new ResolvedNotice(
                new Notice(event.code(), r.purpose(), Placeholders.render(r.subject(), model), Placeholders.render(r.line(), model),
                        event.path(), event.about()),
                r.channels()));
    }

    // ── delivering ────────────────────────────────────────────────────────────

    /** To one user, on the channels they have agreed to for the notice's purpose. */
    @Transactional
    public void toUser(Long userId, Notice notice) {
        toUser(userId, notice, NotificationCatalogue.CHANNELS);
    }

    private void toUser(Long userId, Notice notice, Set<String> allowed) {
        if (userId == null) return;
        User user = users.findById(userId).orElse(null);
        if (user == null || !AppConstant.isLive(user.getStatus())) return;
        try {
            Set<String> channels = new LinkedHashSet<>(consent.channelsFor(user.getId(), notice.purpose()));
            channels.retainAll(allowed);
            String link = publicUrl() + notice.path();
            if (channels.contains(AppConstant.CONSENT_CHANNEL_IN_APP)) {
                inbox.save(Notification.builder()
                        .userId(user.getId()).eventCode(notice.eventCode())
                        .title(notice.subject()).line(notice.line()).link(notice.path())
                        .aboutType(notice.about() == null ? null : notice.about().type())
                        .aboutId(notice.about() == null ? null : notice.about().id())
                        .aboutRef(notice.about() == null ? null : notice.about().ref())
                        .build());
            }
            if (channels.contains(AppConstant.CONSENT_CHANNEL_EMAIL)) {
                attempt(queue(user.getId(), notice, AppConstant.CONSENT_CHANNEL_EMAIL, user.getEmail(),
                        mail.notice(user.getFirstName(), notice.line(), "Open it on " + platformName(), link)));
            }
            if (channels.contains(AppConstant.CONSENT_CHANNEL_SMS)) {
                attempt(queue(user.getId(), notice, AppConstant.CONSENT_CHANNEL_SMS, user.getPhone(),
                        sms(notice.line(), link)));
            }
        } catch (Exception e) {
            log.warn("Could not notify user {} about '{}': {}", user.getId(), notice.subject(), e.getMessage());
        }
    }

    /** To several users, each once. */
    @Transactional
    public void toUsers(List<Long> userIds, Notice notice) {
        for (Long userId : new LinkedHashSet<>(userIds)) toUser(userId, notice);
    }

    /** To somebody without an account, by the contact on record. Transactional only: nobody consented to more. */
    @Transactional
    public void toContact(Contact contact, Notice notice) {
        toContact(contact, notice, NotificationCatalogue.CHANNELS);
    }

    private void toContact(Contact contact, Notice notice, Set<String> allowed) {
        if (contact == null) return;
        try {
            String firstName = contact.name() == null ? "" : contact.name().trim().split("\\s+")[0];
            String link = publicUrl() + notice.path();
            if (allowed.contains(AppConstant.CONSENT_CHANNEL_EMAIL) && contact.email() != null && !contact.email().isBlank()) {
                attempt(queue(null, notice, AppConstant.CONSENT_CHANNEL_EMAIL, contact.email(),
                        mail.notice(firstName, notice.line(), "Open it on " + platformName(), link)));
            }
            if (allowed.contains(AppConstant.CONSENT_CHANNEL_SMS) && contact.phone() != null && !contact.phone().isBlank()
                    && !"-".equals(contact.phone().trim())) {
                attempt(queue(null, notice, AppConstant.CONSENT_CHANNEL_SMS, contact.phone(), sms(notice.line(), link)));
            }
        } catch (Exception e) {
            log.warn("Could not notify {} about '{}': {}", mask(contact.email() != null ? contact.email() : contact.phone()),
                    notice.subject(), e.getMessage());
        }
    }

    /**
     * A message a module composed itself — the saved-search digest, a receipt — recorded and sent through
     * the same door, with an inbox line when the person has in-app on for the purpose.
     */
    @Transactional
    public NotifyResult composed(Long userId, Notice notice, String channel, String address, String payload) {
        if (userId != null && AppConstant.CONSENT_CHANNEL_IN_APP.equals(channel)) {
            inbox.save(Notification.builder()
                    .userId(userId).eventCode(notice.eventCode())
                    .title(notice.subject()).line(notice.line()).link(notice.path())
                    .aboutType(notice.about() == null ? null : notice.about().type())
                    .aboutId(notice.about() == null ? null : notice.about().id())
                    .aboutRef(notice.about() == null ? null : notice.about().ref())
                    .build());
            return NotifyResult.ok("in-app");
        }
        return attempt(queue(userId, notice, channel, address, payload));
    }

    // ── the log ───────────────────────────────────────────────────────────────

    private NotificationLog queue(Long userId, Notice notice, String channel, String address, String payload) {
        return logs.save(NotificationLog.builder()
                .userId(userId).eventCode(notice.eventCode()).purpose(notice.purpose()).channel(channel)
                .recipient(address == null ? "" : address.trim()).recipientMasked(mask(address))
                .subject(notice.subject()).payload(payload)
                .aboutType(notice.about() == null ? null : notice.about().type())
                .aboutId(notice.about() == null ? null : notice.about().id())
                .aboutRef(notice.about() == null ? null : notice.about().ref())
                .build());
    }

    /** One try at the gateway, and the row says how it went. */
    NotifyResult attempt(NotificationLog row) {
        if (row.getRecipient() == null || row.getRecipient().isBlank()) {
            row.setStatus(NotificationLog.SKIPPED);
            row.setError("NO_ADDRESS");
            logs.save(row);
            return NotifyResult.skipped("NO_ADDRESS");
        }
        NotifyResult result;
        try {
            result = AppConstant.CONSENT_CHANNEL_SMS.equals(row.getChannel())
                    ? notify.sendSms(row.getRecipient(), row.getPayload(), null)
                    : notify.sendEmail(row.getRecipient(), row.getSubject(), row.getPayload(), null);
        } catch (RuntimeException e) {
            result = NotifyResult.failed(e.getMessage());
        }
        row.setAttempts(row.getAttempts() + 1);
        row.setLastAttemptAt(OffsetDateTime.now());
        if (result.success()) {
            row.setStatus(NotificationLog.SENT);
            row.setSentAt(OffsetDateTime.now());
            row.setProviderReference(result.correlationId());
            row.setError(null);
            row.setNextAttemptAt(null);
        } else if (result.skipped()) {
            // The channel is off, or there was no address: not a failure, and not worth trying again.
            row.setStatus(NotificationLog.SKIPPED);
            row.setError(result.error());
            row.setNextAttemptAt(null);
        } else {
            row.setStatus(NotificationLog.FAILED);
            row.setError(result.error());
            // 2, 4, 8, 16, 32 minutes: a gateway that is down for a moment gets its moment.
            row.setNextAttemptAt(OffsetDateTime.now().plusMinutes(1L << Math.min(row.getAttempts(), 6)));
        }
        logs.save(row);
        return result;
    }

    /** The failed sends whose turn has come, tried once more each. Returns how many were sent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int retryDue() {
        int cap = Math.max(1, configs.getInt(ConfigKey.NOTIFY_RETRY_MAX_ATTEMPTS, 5));
        int sent = 0;
        for (NotificationLog row : logs.findDueForRetry(OffsetDateTime.now(), cap)) {
            if (attempt(row).success()) sent++;
        }
        return sent;
    }

    /** A person retrying one now, from the log page. */
    @Transactional
    public LogRow retryNow(String hashId) {
        NotificationLog row = logs.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Message", hashId));
        attempt(row);
        return toLogRow(row);
    }

    // ── reading the log ───────────────────────────────────────────────────────

    public record LogRow(String id, String eventCode, String purpose, String channel, String recipient,
                         String subject, String status, String providerReference, String error, int attempts,
                         OffsetDateTime nextAttemptAt, String aboutType, String aboutRef,
                         OffsetDateTime createdAt, OffsetDateTime sentAt) {}

    @Getter @Setter
    public static class LogListRequest extends PagedDataRequest {
        private String channel;
        private String logStatus;
        private String eventCode;
    }

    @Transactional(readOnly = true)
    public PagedResponse<LogRow> list(LogListRequest request) {
        Specification<NotificationLog> spec = SearchSpecs.allOf(
                SearchSpecs.eq("channel", blank(request.getChannel())),
                SearchSpecs.eq("status", blank(request.getLogStatus())),
                SearchSpecs.eq("eventCode", blank(request.getEventCode())),
                request.getSearch() == null || request.getSearch().isBlank() ? null
                        : (root, query, cb) -> {
                            String like = "%" + request.getSearch().trim().toLowerCase() + "%";
                            return cb.or(cb.like(cb.lower(root.get("subject")), like),
                                    cb.like(cb.lower(root.get("recipientMasked")), like),
                                    cb.like(cb.lower(root.get("aboutRef")), like));
                        });
        return PagedResponse.from(logs.findAll(spec, request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt"))),
                this::toLogRow);
    }

    /** What was sent about one thing. The caller has already decided the caller may see the thing. */
    @Transactional(readOnly = true)
    public List<LogRow> about(String type, Long id) {
        return logs.findByAboutTypeAndAboutIdOrderByCreatedAtDesc(type, id).stream().map(this::toLogRow).toList();
    }

    // ── the inbox ─────────────────────────────────────────────────────────────

    public record InboxItem(String id, String eventCode, String title, String line, String link,
                            String aboutType, String aboutRef, OffsetDateTime readAt, OffsetDateTime createdAt) {}

    public record InboxSummary(long unread, List<InboxItem> latest) {}

    @Transactional(readOnly = true)
    public InboxSummary summary() {
        Long me = AuthContext.requireUserId();
        return new InboxSummary(inbox.countByUserIdAndReadAtIsNull(me),
                inbox.findTop8ByUserIdOrderByCreatedAtDesc(me).stream().map(this::toItem).toList());
    }

    @Transactional(readOnly = true)
    public PagedResponse<InboxItem> mine(PagedDataRequest request) {
        return PagedResponse.from(inbox.findByUserIdOrderByCreatedAtDesc(AuthContext.requireUserId(),
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt"))), this::toItem);
    }

    @Transactional
    public InboxItem markRead(String hashId) {
        Notification row = inbox.findByIdAndUserId(HashIdUtil.decodeId(hashId), AuthContext.requireUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Notification", hashId));
        if (row.getReadAt() == null) {
            row.setReadAt(OffsetDateTime.now());
            inbox.save(row);
        }
        return toItem(row);
    }

    @Transactional
    public int markAllRead() {
        return inbox.markAllRead(AuthContext.requireUserId(), OffsetDateTime.now());
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private InboxItem toItem(Notification n) {
        return new InboxItem(HashIdUtil.encodeId(n.getId()), n.getEventCode(), n.getTitle(), n.getLine(), n.getLink(),
                n.getAboutType(), n.getAboutRef(), n.getReadAt(), n.getCreatedAt());
    }

    private LogRow toLogRow(NotificationLog l) {
        return new LogRow(HashIdUtil.encodeId(l.getId()), l.getEventCode(), l.getPurpose(), l.getChannel(),
                l.getRecipientMasked(), l.getSubject(), l.getStatus(), l.getProviderReference(), l.getError(),
                l.getAttempts(), l.getNextAttemptAt(), l.getAboutType(), l.getAboutRef(), l.getCreatedAt(), l.getSentAt());
    }

    private String sms(String line, String link) {
        String text = "Hodi: " + line + " " + link;
        return text.length() <= SMS_MAX ? text : text.substring(0, SMS_MAX);
    }

    /** The same masking the gateway client logs with: two letters of an email, four digits of a phone. */
    static String mask(String contact) {
        if (contact == null || contact.isBlank()) return "";
        int at = contact.indexOf('@');
        if (at > 0) {
            String local = contact.substring(0, at);
            String kept = local.length() <= 2 ? local : local.substring(0, 2);
            return kept + "***" + contact.substring(at);
        }
        return contact.length() <= 4 ? "***" : "***" + contact.substring(contact.length() - 4);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    private String publicUrl() {
        String url = configs.getString(ConfigKey.PUBLIC_URL);
        if (url == null || url.isBlank()) return "";
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String platformName() {
        return configs.getString(ConfigKey.COMPANY_NAME);
    }
}
