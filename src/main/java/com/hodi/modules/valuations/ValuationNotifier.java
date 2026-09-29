package com.hodi.modules.valuations;

import com.hodi.common.AppConstant;
import com.hodi.enums.ConfigKey;
import com.hodi.infra.notify.MailTemplate;
import com.hodi.infra.notify.NotifyClient;
import com.hodi.modules.configurations.ConfigurationService;
import com.hodi.modules.notifications.NotificationService;
import com.hodi.modules.notifications.NotificationService.About;
import com.hodi.modules.notifications.NotificationService.Notice;
import com.hodi.modules.profiles.UserProfileRepository;
import com.hodi.modules.users.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Tells the three parties to a valuation what the other two did (M5, plan §3.4).
 *
 * <h2>Who hears what</h2>
 *
 * <p>The valuer, when they are put on a job, when the report comes back for another look, when the job is
 * cancelled under them, and when their own cover or registration is about to lapse. The platform's people
 * who hold the relevant permission, when a job is raised or handed back (the assigners), when a report is
 * submitted (the reviewers), and when a valuer's cover lapses (the panel's managers). The requester — every
 * live person at the seller or the bank that commissioned it — when the valuer takes it on, books the
 * inspection, when the figure is approved, and when it is cancelled.
 *
 * <h2>The same two rules as the leads notifier</h2>
 *
 * <p>Transactional consent, asked every time. And best effort: nothing here throws, because a gateway that
 * is down must not stop a report being submitted — the row is the record and the message is a courtesy on
 * top of it. Failures are logged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ValuationNotifier {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'at' HH:mm");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy");

    public static final String EVENT = "VALUATIONS";

    private final UserProfileRepository profiles;
    private final NotificationService notifications;
    private final NotifyClient notify;
    private final MailTemplate mail;
    private final ConfigurationService configs;

    // ── the valuer ────────────────────────────────────────────────────────────

    public void valuerAssigned(ValuationRequest job, ValuerProfile valuer) {
        toValuer(valuer, job, "A valuation for you: " + job.getPropertyTitle(),
                "You have been assigned to value " + job.getPropertyTitle() + " (" + job.getReference() + ")"
                        + (job.getCounty() == null ? "" : " in " + job.getCounty())
                        + (job.getDueOn() == null ? "" : ", due by " + DAY.format(job.getDueOn()))
                        + ". Take it on, or hand it back with a reason.");
    }

    public void valuerSentBack(ValuationRequest job, ValuerProfile valuer, String reason) {
        toValuer(valuer, "Your report was sent back: " + job.getPropertyTitle(),
                "The reviewer sent back your report on " + job.getPropertyTitle() + " (" + job.getReference() + ")"
                        + (reason == null ? "." : ": “" + reason + "”.") + " Submit a corrected one.",
                jobPath(job));
    }

    public void valuerCancelled(ValuationRequest job, ValuerProfile valuer, String reason) {
        toValuer(valuer, "Valuation cancelled: " + job.getPropertyTitle(),
                "The valuation of " + job.getPropertyTitle() + " (" + job.getReference() + ") was cancelled"
                        + (reason == null ? "." : ": “" + reason + "”.") + " Nothing more is needed from you.",
                jobPath(job));
    }

    /** Thirty days out, or whatever the setting says: to the valuer, and to whoever manages the panel. */
    public void lapseWarning(ValuerProfile valuer, LocalDate on, String what) {
        String line = valuer.getFullName() + "'s " + what + " runs out on " + DAY.format(on)
                + ". After that no work can be assigned to them until it is renewed on the panel.";
        toValuer(valuer, "Your " + what + " runs out on " + DAY.format(on),
                "Your " + what + " on the valuation panel runs out on " + DAY.format(on)
                        + ". After that no work can be assigned to you until the panel's record is renewed.",
                "/app/valuers");
        toPlatform("VALUER_PANEL_MANAGE", valuer.getFullName() + "'s " + what + " runs out on " + DAY.format(on),
                line, "/app/valuers");
    }

    /** A new panel member's credential, which they must change on first sign-in. */
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
        toPlatform("VALUATIONS_ASSIGN", "Valuation to assign: " + job.getPropertyTitle(),
                requester(job) + " asked for a valuation of " + job.getPropertyTitle() + " (" + job.getReference()
                        + ")" + (job.getCounty() == null ? "" : " in " + job.getCounty())
                        + (job.getDueOn() == null ? "" : ", wanted by " + DAY.format(job.getDueOn()))
                        + ". It needs a valuer.", jobPath(job), about(job));
    }

    public void platformHandedBack(ValuationRequest job, String who, String reason) {
        toPlatform("VALUATIONS_ASSIGN", "Valuation handed back: " + job.getPropertyTitle(),
                who + " handed back " + job.getPropertyTitle() + " (" + job.getReference() + ")"
                        + (reason == null ? "." : ": “" + reason + "”.") + " It needs another valuer.",
                jobPath(job));
    }

    public void platformReported(ValuationRequest job, String figure) {
        toPlatform("VALUATIONS_APPROVE", "Valuation report to review: " + job.getPropertyTitle(),
                job.getValuerName() + " valued " + job.getPropertyTitle() + " (" + job.getReference() + ") at "
                        + figure + ". The figure counts once it is reviewed and approved.", jobPath(job), about(job));
    }

    /** The due date passed: the valuer on it, if any, and the assigners either way. */
    public void overdue(ValuationRequest job, ValuerProfile valuer) {
        String line = job.getPropertyTitle() + " (" + job.getReference() + ") was due by "
                + DAY.format(job.getDueOn()) + " and is still " + (job.isUnassigned() ? "unassigned." : "open.");
        if (valuer != null) {
            toValuer(valuer, job, "Overdue: " + job.getPropertyTitle(), "Your valuation of " + line);
        }
        toPlatform("VALUATIONS_ASSIGN", "Overdue valuation: " + job.getPropertyTitle(), line, jobPath(job), about(job));
    }

    // ── the requester ─────────────────────────────────────────────────────────

    public void requesterAccepted(ValuationRequest job) {
        toRequester(job, "Your valuation is under way: " + job.getPropertyTitle(),
                job.getValuerName() + " has taken on the valuation of " + job.getPropertyTitle() + " ("
                        + job.getReference() + ")" + (job.getDueOn() == null ? "." : ", due by "
                        + DAY.format(job.getDueOn()) + "."));
    }

    public void requesterInspection(ValuationRequest job) {
        toRequester(job, "Inspection booked: " + job.getPropertyTitle(),
                job.getValuerName() + " will inspect " + job.getPropertyTitle() + " (" + job.getReference()
                        + ") on " + when(job.getInspectionAt()) + ".");
    }

    public void requesterApproved(ValuationRequest job, String figure) {
        toRequester(job, "Valuation approved: " + job.getPropertyTitle(),
                "The valuation of " + job.getPropertyTitle() + " (" + job.getReference() + ") has been reviewed "
                        + "and approved at " + figure + ". The report is on the job.");
    }

    public void requesterCancelled(ValuationRequest job, String reason) {
        toRequester(job, "Valuation cancelled: " + job.getPropertyTitle(),
                "The valuation of " + job.getPropertyTitle() + " (" + job.getReference() + ") was cancelled"
                        + (reason == null ? "." : ": “" + reason + "”."));
    }

    // ── fan-out ───────────────────────────────────────────────────────────────

    private void toValuer(ValuerProfile valuer, String subject, String line, String path) {
        if (valuer == null || valuer.getUserId() == null) return;
        notifications.toUser(valuer.getUserId(), Notice.transactional(EVENT, subject, line, path, null));
    }

    private void toValuer(ValuerProfile valuer, ValuationRequest job, String subject, String line) {
        if (valuer == null || valuer.getUserId() == null) return;
        notifications.toUser(valuer.getUserId(), Notice.transactional(EVENT, subject, line, jobPath(job), about(job)));
    }

    /** Everybody at the organisation that commissioned it — the seller's staff, or the bank's. */
    private void toRequester(ValuationRequest job, String subject, String line) {
        List<Long> staff = job.getTenantId() != null
                ? profiles.findLiveUserIdsByTenant(job.getTenantId())
                : job.getInstitutionId() != null
                        ? profiles.findLiveUserIdsByInstitution(job.getInstitutionId())
                        : List.of();
        if (staff.isEmpty()) {
            log.warn("Valuation {} has no requester staff to notify about: {}", job.getReference(), subject);
            return;
        }
        notifications.toUsers(staff, Notice.transactional(EVENT, subject, line, jobPath(job), about(job)));
    }

    /** The platform's people who hold the permission the notice is for. */
    private void toPlatform(String permission, String subject, String line, String path) {
        toPlatform(permission, subject, line, path, null);
    }

    private void toPlatform(String permission, String subject, String line, String path, About about) {
        List<Long> holders = profiles.findLivePlatformUserIdsHolding(permission);
        if (holders.isEmpty()) {
            log.warn("Nobody on the platform holds {} to be told: {}", permission, subject);
            return;
        }
        notifications.toUsers(holders, Notice.transactional(EVENT, subject, line, path, about));
    }

    private static About about(ValuationRequest job) {
        return new About("VALUATION", job.getId(), job.getReference());
    }

    // ── words ─────────────────────────────────────────────────────────────────

    private static String jobPath(ValuationRequest job) {
        return "/app/valuations/" + job.getReference();
    }

    private static String requester(ValuationRequest job) {
        return job.getTenantName() != null ? job.getTenantName()
                : job.getInstitutionName() != null ? job.getInstitutionName() : "A requester";
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
