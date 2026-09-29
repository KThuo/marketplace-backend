package com.hodi.modules.valuations;

import com.hodi.enums.ConfigKey;
import com.hodi.infra.notify.MailTemplate;
import com.hodi.infra.notify.NotifyClient;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.notifications.NotificationService;
import com.hodi.modules.notifications.NotificationService.About;
import com.hodi.modules.notifications.NotificationService.Event;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.users.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tells the three parties to a valuation what the other two did (M5, plan §3.4).
 *
 * <p>Thin: each method names the event and fills its figures; the catalogue holds the words and the
 * requester organisation's say, the notification service asks consent, records, sends and retries. The
 * valuer hears of assignment, a send-back, a cancellation, an overdue date and a coming lapse; the
 * platform's holders of the relevant permission hear of a raise, a hand-back, a report and a lapsing
 * valuer; the requester's staff hear that it was taken on, inspected, approved or cancelled.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ValuationNotifier {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'at' HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy");

    private final UserProfileRepository profiles;
    private final NotificationService notifications;
    private final NotifyClient notify;
    private final MailTemplate mail;
    private final ConfigurationService configs;

    // ── the valuer ────────────────────────────────────────────────────────────

    public void valuerAssigned(ValuationRequest job, ValuerProfile valuer) {
        Map<String, Object> m = model(job);
        m.put("details", (job.getCounty() == null ? "" : " in " + job.getCounty())
                + (job.getDueOn() == null ? "" : ", due by " + DAY.format(job.getDueOn())));
        toValuer(valuer, "VALUATION_ASSIGNED", m, jobPath(job), about(job), job);
    }

    public void valuerSentBack(ValuationRequest job, ValuerProfile valuer, String reason) {
        Map<String, Object> m = model(job);
        m.put("reason", reasonSaid(reason));
        toValuer(valuer, "VALUATION_SENT_BACK", m, jobPath(job), about(job), job);
    }

    public void valuerCancelled(ValuationRequest job, ValuerProfile valuer, String reason) {
        Map<String, Object> m = model(job);
        m.put("reason", reasonSaid(reason));
        toValuer(valuer, "VALUATION_CANCELLED_VALUER", m, jobPath(job), about(job), job);
    }

    /** Thirty days out, or whatever the setting says: to the valuer, and to whoever manages the panel. */
    public void lapseWarning(ValuerProfile valuer, LocalDate on, String what) {
        Map<String, Object> m = new HashMap<>();
        m.put("valuer", valuer.getFullName());
        m.put("what", what);
        m.put("on", DAY.format(on));
        toValuer(valuer, "VALUER_LAPSE_WARNING", m, "/app/valuers", null, null);
        toPlatform("VALUER_PANEL_MANAGE", "VALUER_LAPSE_WARNING_PANEL", m, "/app/valuers", null);
    }

    /** A new panel member's credential, which they must change on first sign-in. Sensitive: not catalogued, not logged with a body. */
    public void welcome(User valuer, String username, String temporaryPassword) {
        try {
            String link = publicUrl() + "/login";
            notify.sendSensitiveEmail(valuer.getEmail(), "Welcome to the " + platformName() + " valuation panel",
                    mail.action(valuer.getFirstName(),
                            "You have been added to the valuation panel. Sign in as <b>" + username
                                    + "</b> with the temporary password <b>" + temporaryPassword
                                    + "</b>; you will be asked to choose your own the first time.",
                            "Sign in", link,
                            "If you were not expecting this, ignore it and the account stays unused."),
                    valuer.fullName());
        } catch (Exception e) {
            log.warn("Could not send the panel welcome to user {}: {}", valuer.getId(), e.getMessage());
        }
    }

    // ── the platform ──────────────────────────────────────────────────────────

    public void platformRaised(ValuationRequest job) {
        Map<String, Object> m = model(job);
        m.put("details", (job.getCounty() == null ? "" : " in " + job.getCounty())
                + (job.getDueOn() == null ? "" : ", wanted by " + DAY.format(job.getDueOn())));
        toPlatform("VALUATIONS_ASSIGN", "VALUATION_RAISED", m, jobPath(job), about(job));
    }

    public void platformHandedBack(ValuationRequest job, String who, String reason) {
        Map<String, Object> m = model(job);
        m.put("valuer", who);
        m.put("reason", reasonSaid(reason));
        toPlatform("VALUATIONS_ASSIGN", "VALUATION_HANDED_BACK", m, jobPath(job), about(job));
    }

    public void platformReported(ValuationRequest job, String figure) {
        Map<String, Object> m = model(job);
        m.put("figure", figure);
        toPlatform("VALUATIONS_APPROVE", "VALUATION_REPORTED", m, jobPath(job), about(job));
    }

    /** The due date passed: the valuer on it, if any, and the assigners either way. */
    public void overdue(ValuationRequest job, ValuerProfile valuer) {
        Map<String, Object> m = model(job);
        m.put("due", DAY.format(job.getDueOn()));
        m.put("state", job.isUnassigned() ? "unassigned" : "open");
        if (valuer != null) toValuer(valuer, "VALUATION_OVERDUE_VALUER", m, jobPath(job), about(job), job);
        toPlatform("VALUATIONS_ASSIGN", "VALUATION_OVERDUE", m, jobPath(job), about(job));
    }

    // ── the requester ─────────────────────────────────────────────────────────

    public void requesterAccepted(ValuationRequest job) {
        Map<String, Object> m = model(job);
        m.put("due", job.getDueOn() == null ? "" : ", due by " + DAY.format(job.getDueOn()));
        toRequester(job, "VALUATION_ACCEPTED", m);
    }

    public void requesterInspection(ValuationRequest job) {
        Map<String, Object> m = model(job);
        m.put("when", when(job.getInspectionAt()));
        toRequester(job, "VALUATION_INSPECTION", m);
    }

    public void requesterApproved(ValuationRequest job, String figure) {
        Map<String, Object> m = model(job);
        m.put("figure", figure);
        toRequester(job, "VALUATION_APPROVED", m);
    }

    public void requesterCancelled(ValuationRequest job, String reason) {
        Map<String, Object> m = model(job);
        m.put("reason", reasonSaid(reason));
        toRequester(job, "VALUATION_CANCELLED", m);
    }

    // ── fan-out ───────────────────────────────────────────────────────────────

    private void toValuer(ValuerProfile valuer, String code, Map<String, ?> model, String path, About about, ValuationRequest job) {
        if (valuer == null || valuer.getUserId() == null) return;
        notifications.event(valuer.getUserId(), Event.of(code, model, path, about)
                .forOrganisation(job == null ? null : job.getTenantId(), job == null ? null : job.getInstitutionId()));
    }

    /** Everybody at the organisation that commissioned it — the seller's staff, or the bank's. */
    private void toRequester(ValuationRequest job, String code, Map<String, ?> model) {
        List<Long> staff = job.getTenantId() != null
                ? profiles.findLiveUserIdsByTenant(job.getTenantId())
                : job.getInstitutionId() != null
                        ? profiles.findLiveUserIdsByInstitution(job.getInstitutionId())
                        : List.of();
        if (staff.isEmpty()) {
            log.warn("Valuation {} has no requester staff to notify about {}", job.getReference(), code);
            return;
        }
        notifications.event(staff, Event.of(code, model, jobPath(job), about(job)).forOrganisation(job.getTenantId(), job.getInstitutionId()));
    }

    /** The platform's people who hold the permission the notice is for. */
    private void toPlatform(String permission, String code, Map<String, ?> model, String path, About about) {
        List<Long> holders = profiles.findLivePlatformUserIdsHolding(permission);
        if (holders.isEmpty()) {
            log.warn("Nobody on the platform holds {} to be told about {}", permission, code);
            return;
        }
        notifications.event(holders, Event.of(code, model, path, about));
    }

    // ── words ─────────────────────────────────────────────────────────────────

    private static Map<String, Object> model(ValuationRequest job) {
        Map<String, Object> m = new HashMap<>();
        m.put("property", job.getPropertyTitle());
        m.put("reference", job.getReference());
        m.put("valuer", job.getValuerName() == null ? "The valuer" : job.getValuerName());
        m.put("requester", job.getTenantName() != null ? job.getTenantName()
                : job.getInstitutionName() != null ? job.getInstitutionName() : "A requester");
        return m;
    }

    private static About about(ValuationRequest job) {
        return new About("VALUATION", job.getId(), job.getReference());
    }

    private static String jobPath(ValuationRequest job) {
        return "/app/valuations/" + job.getReference();
    }

    /** "" or ": “the reason”" — the template ends the sentence itself. */
    private static String reasonSaid(String reason) {
        return reason == null || reason.isBlank() ? "" : ": \u201c" + reason.trim() + "\u201d";
    }

    private static String when(OffsetDateTime at) {
        return at == null ? "a date to be confirmed"
                : WHEN.format(at.atZoneSameInstant(ZoneId.of("Africa/Nairobi")));
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
