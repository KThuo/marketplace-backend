package com.hodi.infra.notify;

import com.hodi.enums.ConfigKey;
import com.hodi.modules.configurations.ConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Calls the notify service for SMS and email.
 *
 * <p>Base URL, API key and the two channel switches all come from {@link ConfigurationService}, so they are
 * rotatable without a redeploy — and, because those three keys are marked overridable (plan §7.2), a merchant's
 * own credentials are picked up automatically whenever a tenant is bound. The caller does not choose which
 * account to bill; the tenant context does.
 *
 * <p><strong>Success is what the envelope says, not what the HTTP status says.</strong> The service answers
 * {@code {"status": "00" | <error code>, "message": ..., "data": ...}} and documents {@code 00} (or {@code 0})
 * as the only success — an insufficient unit balance ({@code 01}) and a rejected email
 * ({@code EMAIL_SEND_FAILED}) are error codes carried inside an otherwise ordinary response. Reading the HTTP
 * status alone, as this client did until 26 Aug 2026, recorded both as delivered and dropped the message.
 *
 * <p>Every call logs the outbound URL with the API key masked and the inbound status and body, so a delivery
 * question can be answered from these logs without reaching for the notify service's own. The
 * {@code sendSensitive*} variants additionally mask the message body: an OTP or a password-reset link in an
 * application log is a way past authentication for anyone who can read logs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyClient {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    /** The documented success codes. Anything else in {@code status} is an error code. */
    private static final Set<String> DELIVERED = Set.of("00", "0");

    private static final String SMS_PATH = "/api/v1/messages/sendsms";
    private static final String EMAIL_PATH = "/api/v1/email/send";

    private final ConfigurationService configs;
    private final EmailSender emailSender;

    /**
     * Nothing leaves the process.
     *
     * <p>The integration tests book homes and accept offers against the shared development database, and
     * every one of those tells a buyer by SMS — a real message, at real cost, to whichever number the fixture
     * carried. The test run sets this and every send is logged instead. Set from a system property, not the
     * configuration table, because the table is the same one the running application reads.
     */
    @org.springframework.beans.factory.annotation.Value("${hodi.notify.dry-run:false}")
    private boolean dryRun;

    // ── SMS ───────────────────────────────────────────────────────────────────

    public NotifyResult sendSms(String phone, String text, String recipientName) {
        return sendSms(phone, text, recipientName, false);
    }

    /**
     * The body still goes to the wire as-is — the recipient needs the code — but is replaced with a length
     * marker in every log line. Use for OTPs, reset links, and anything whose presence in application logs
     * would let a log reader bypass authentication.
     *
     * <p><strong>Ignores {@code notify.sms.enabled}.</strong> That switch is a recipient's preference about
     * being notified; a confirmation code is not a notification someone can opt out of and still complete
     * the action it gates — an owner who has switched SMS off still needs the code to approve their own
     * payment. Only a missing phone number or API key skips it here, the same as the regular channel.
     */
    public NotifyResult sendSensitiveSms(String phone, String text, String recipientName) {
        return sendSms(phone, text, recipientName, true);
    }

    private NotifyResult sendSms(String phone, String text, String recipientName, boolean sensitive) {
        if (dryRun) {
            log.info("Dry run — SMS to {} not sent: {}", mask(phone), text);
            return NotifyResult.skipped("DRY_RUN");
        }
        if (!sensitive && !configs.getBoolean(ConfigKey.NOTIFY_SMS_ENABLED)) {
            log.debug("SMS is switched off — nothing sent to {}", mask(phone));
            return NotifyResult.skipped("SMS_DISABLED");
        }
        if (phone == null || phone.isBlank()) {
            return NotifyResult.skipped("NO_PHONE");
        }
        String apiKey = configs.getString(ConfigKey.NOTIFY_API_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            // Skipped, not failed: an unconfigured environment is a setup state, not a delivery failure, and
            // retrying it every minute would bury the real failures.
            log.warn("Notify API key is not configured — SMS to {} dropped", mask(phone));
            return NotifyResult.skipped("NO_API_KEY");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apikey", apiKey);
        body.put("phoneNo", phone);
        body.put("text", text);
        if (recipientName != null) body.put("recipientName", recipientName);
        String senderId = configs.getString(ConfigKey.NOTIFY_SMS_SENDER_ID);
        if (senderId != null && !senderId.isBlank()) body.put("senderId", senderId);

        int length = text == null ? 0 : text.length();
        log.info("SMS request: POST {}{} body={}", baseUrl(), SMS_PATH,
                redact(body, sensitive ? Set.of("text") : Set.of()));
        try {
            ResponseEntity<String> response = client().post()
                    .uri(SMS_PATH)
                    .header("X-Authorization", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);
            NotifyResult result = interpret(response.getBody());
            // warn, not info, when the gateway declined it: a message that did not arrive should be findable
            // at the same level as an HTTP rejection, because to the recipient it is the same event.
            if (result.success()) {
                log.info("SMS response: status={} body={} (phone={} len={} corr={})",
                        response.getStatusCode().value(), truncate(response.getBody(), 800),
                        mask(phone), length, result.correlationId());
            } else {
                log.warn("SMS declined by the gateway: status={} body={} (phone={} len={} reason={})",
                        response.getStatusCode().value(), truncate(response.getBody(), 800),
                        mask(phone), length, result.error());
            }
            return result;
        } catch (RestClientResponseException e) {
            log.warn("SMS rejected: status={} body={} (phone={} len={})",
                    e.getStatusCode().value(), truncate(e.getResponseBodyAsString(), 800),
                    mask(phone), length);
            return NotifyResult.failed(httpError(e));
        } catch (Exception e) {
            log.warn("SMS send failed: phone={} len={} err={}", mask(phone), length, e.getMessage());
            return NotifyResult.failed(e.getMessage());
        }
    }

    // ── Email ─────────────────────────────────────────────────────────────────

    public NotifyResult sendEmail(String to, String subject, String htmlBody, String recipientName) {
        return sendEmail(to, subject, htmlBody, recipientName, List.of(), false);
    }

    /**
     * Body masked in logs but delivered intact. Use for reset links and one-time codes.
     *
     * <p>Ignores {@code notify.email.enabled} for the same reason {@link #sendSensitiveSms} ignores {@code
     * notify.sms.enabled} — see that method's note.
     */
    public NotifyResult sendSensitiveEmail(String to, String subject, String htmlBody, String recipientName) {
        return sendEmail(to, subject, htmlBody, recipientName, List.of(), true);
    }

    /** {@code attachments} are base64 payloads; the notify service detects the type. */
    public NotifyResult sendEmail(String to, String subject, String htmlBody, String recipientName,
                                  List<String> attachments) {
        return sendEmail(to, subject, htmlBody, recipientName, attachments, false);
    }

    private NotifyResult sendEmail(String to, String subject, String htmlBody, String recipientName,
                                   List<String> attachments, boolean sensitive) {
        if (dryRun) {
            log.info("Dry run — email to {} not sent: {}", to, subject);
            return NotifyResult.skipped("DRY_RUN");
        }
        if (!sensitive && !configs.getBoolean(ConfigKey.NOTIFY_EMAIL_ENABLED)) {
            log.debug("Email is switched off — nothing sent to {}", mask(to));
            return NotifyResult.skipped("EMAIL_DISABLED");
        }
        if (to == null || to.isBlank()) {
            return NotifyResult.skipped("NO_EMAIL");
        }
        String apiKey = configs.getString(ConfigKey.NOTIFY_API_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Notify API key is not configured — email to {} dropped", mask(to));
            return NotifyResult.skipped("NO_API_KEY");
        }

        // Resolved per send, not per client: the From address depends on the tenant bound right now, and the
        // dispatcher rebinds it row by row.
        EmailSender.From from = emailSender.resolve();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("apikey", apiKey);
        body.put("to", to);
        body.put("from", from.address());
        if (from.name() != null && !from.name().isBlank()) body.put("fromName", from.name());
        body.put("subject", subject);
        body.put("content", htmlBody);
        if (recipientName != null) body.put("recipient", recipientName);
        body.put("attachments", attachments == null ? List.of() : attachments);

        int length = htmlBody == null ? 0 : htmlBody.length();
        log.info("Email request: POST {}{} body={}", baseUrl(), EMAIL_PATH,
                redact(body, sensitive ? Set.of("content") : Set.of()));
        try {
            ResponseEntity<String> response = client().post()
                    .uri(EMAIL_PATH)
                    .header("X-Authorization", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toEntity(String.class);
            NotifyResult result = interpret(response.getBody());
            if (result.success()) {
                log.info("Email response: status={} body={} (from={} to={} subject='{}' len={} corr={})",
                        response.getStatusCode().value(), truncate(response.getBody(), 800),
                        from.address(), mask(to), subject == null ? "" : subject, length,
                        result.correlationId());
            } else {
                log.warn("Email declined by the gateway: status={} body={} (from={} to={} subject='{}' "
                                + "len={} reason={})",
                        response.getStatusCode().value(), truncate(response.getBody(), 800),
                        from.address(), mask(to), subject == null ? "" : subject, length, result.error());
            }
            return result;
        } catch (RestClientResponseException e) {
            log.warn("Email rejected: status={} body={} (to={} subject='{}' len={})",
                    e.getStatusCode().value(), truncate(e.getResponseBodyAsString(), 800),
                    mask(to), subject == null ? "" : subject, length);
            return NotifyResult.failed(httpError(e));
        } catch (Exception e) {
            log.warn("Email send failed: to={} subject='{}' len={} err={}",
                    mask(to), subject == null ? "" : subject, length, e.getMessage());
            return NotifyResult.failed(e.getMessage());
        }
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * The outcome of a 2xx response, read out of the envelope.
     *
     * <p>{@code data.id} is the service's own id for the message and is what a delivery question can actually
     * be traced with, so it becomes the correlation id when the response carries one. SMS answers with
     * {@code data: null}, and there a locally generated id is still worth having: it ties this log line to
     * whatever row stores it.
     *
     * <p>A body that is not the documented envelope counts as a failure rather than a success. It is the less
     * comfortable default — a provider that ever answered 204 with an empty body would have every send marked
     * failed — but the asymmetry is deliberate: a false failure is visible and retryable, while a false
     * success loses the message with nothing left to show that it happened.
     *
     * <p>One documented case this cannot catch: a blacklisted number comes back {@code status: "00"} with the
     * reason in {@code message}, so it is a success by the contract and delivered nothing. The reason is in
     * the response body logged by the caller; distinguishing it structurally is not possible, and matching on
     * the provider's prose would break the first time they reword it.
     */
    private static NotifyResult interpret(String rawBody) {
        Object parsed;
        try {
            parsed = JSON.readValue(rawBody == null ? "" : rawBody, Object.class);
        } catch (RuntimeException e) {
            return NotifyResult.failed("BAD_ENVELOPE");
        }
        if (!(parsed instanceof Map<?, ?> envelope)) return NotifyResult.failed("BAD_ENVELOPE");

        String code = text(envelope.get("status"));
        String message = text(envelope.get("message"));
        if (!DELIVERED.contains(code)) return NotifyResult.failed(reason(code, message));

        // "" rather than null: SMS answers with `data: null`, which leaves this untouched, and the check
        // below is the ordinary success path for every OTP the platform sends.
        String providerId = "";
        if (envelope.get("data") instanceof Map<?, ?> payload) {
            // A success code over `sent: false` is a contradiction. Believe the flag: it is the one the
            // service sets from the outcome of the send itself.
            if (Boolean.FALSE.equals(payload.get("sent"))) {
                return NotifyResult.failed(reason(code.isBlank() ? "NOT_SENT" : code, message));
            }
            providerId = text(payload.get("id"));
        }
        return NotifyResult.ok(providerId.isBlank() ? UUID.randomUUID().toString() : providerId);
    }

    /** An error envelope on an HTTP error, so the code that explains the rejection is not thrown away. */
    private static String httpError(RestClientResponseException e) {
        String http = "HTTP " + e.getStatusCode().value();
        try {
            if (JSON.readValue(e.getResponseBodyAsString(), Object.class) instanceof Map<?, ?> envelope) {
                String code = text(envelope.get("status"));
                if (!code.isBlank()) return http + " " + reason(code, text(envelope.get("message")));
            }
        } catch (RuntimeException ignored) {
            // Not an envelope — the status on its own is the whole story.
        }
        return http;
    }

    /** "01 — You have insufficient Unit balance": the code to act on, the message to read. */
    private static String reason(String code, String message) {
        if (code.isBlank()) return "BAD_ENVELOPE";
        return message.isBlank() ? code : code + " — " + truncate(message, 160);
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String baseUrl() {
        return configs.getString(ConfigKey.NOTIFY_BASE_URL);
    }

    /**
     * Built per call rather than cached as a field: the base URL is runtime configuration and may be a tenant
     * override, so a client captured at startup would send one merchant's messages to another's endpoint.
     */
    private RestClient client() {
        return RestClient.builder()
                .baseUrl(baseUrl())
                .requestFactory(timeoutFactory())
                .build();
    }

    /**
     * An explicit timeout, because the dispatcher is a single-threaded scheduled poll: one unresponsive gateway
     * without this would stall every other pending notification behind it indefinitely.
     */
    private static ClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT);
        factory.setReadTimeout(TIMEOUT);
        return factory;
    }

    /**
     * Serialises a request body for logging with the API key masked, plus any field the caller nominates —
     * used by the sensitive variants so an OTP reaches the recipient but not the log.
     */
    private static String redact(Map<String, Object> body, Set<String> alsoMask) {
        Map<String, Object> safe = new LinkedHashMap<>(body);
        if (safe.containsKey("apikey")) safe.put("apikey", "***");
        for (String field : alsoMask) {
            Object value = safe.get(field);
            if (value != null) {
                safe.put(field, "<redacted " + value.toString().length() + " chars>");
            }
        }
        try {
            return truncate(JSON.writeValueAsString(safe), 1200);
        } catch (RuntimeException e) {
            return safe.toString();
        }
    }

    /**
     * Partially masks a phone number or email in logs. A delivery question needs enough to identify the row,
     * not the whole contact detail of every person the system has ever messaged.
     */
    private static String mask(String contact) {
        if (contact == null || contact.isBlank()) return "";
        int at = contact.indexOf('@');
        if (at > 0) {
            String local = contact.substring(0, at);
            String kept = local.length() <= 2 ? local : local.substring(0, 2);
            return kept + "***" + contact.substring(at);
        }
        return contact.length() <= 4 ? "***" : "***" + contact.substring(contact.length() - 4);
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…(+" + (value.length() - max) + ")";
    }
}
