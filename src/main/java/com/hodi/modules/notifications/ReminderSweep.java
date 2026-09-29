package com.hodi.modules.notifications;

import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.approvals.ApprovalWorkflow;
import com.hodi.modules.approvals.ApprovalWorkflowRepository;
import com.hodi.modules.bookings.BookingNotifier;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.kyc.VaultDocument;
import com.hodi.modules.kyc.VaultDocumentRepository;
import com.hodi.modules.leads.EnquiryTicket;
import com.hodi.modules.leads.EnquiryTicketRepository;
import com.hodi.modules.leads.SiteVisit;
import com.hodi.modules.leads.SiteVisitRepository;
import com.hodi.modules.notifications.NotificationService.About;
import com.hodi.modules.notifications.NotificationService.Event;
import com.hodi.modules.notifications.ReminderRuleService.Effective;
import com.hodi.modules.profiles.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Says the things only the calendar knows (notifications plan §3.5).
 *
 * <p>Once a day, early, under an advisory lock: an instalment about to fall due and not yet covered; money
 * overdue; a viewing tomorrow; an enquiry the seller has not answered; a decision that has sat with a
 * checker; a document about to run out. Each is a rule with days the platform sets and an organisation
 * may tune, and each is said once per subject unless the rule repeats — {@link ReminderRuleService#claim}
 * keeps that record. The hold-expiry reminder stays hourly in the booking sweeper and the valuer's lapse in
 * the valuation sweep; both read their days from the same rules.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReminderSweep {

    private static final long LOCK_KEY = 7_260_929_004L;
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM yyyy");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE d MMMM 'at' HH:mm");

    private final ReminderRuleService rules;
    private final NotificationService notifications;
    private final BookingNotifier bookingNotifier;
    private final UnitBookingRepository bookings;
    private final SiteVisitRepository visits;
    private final EnquiryTicketRepository enquiries;
    private final ApprovalWorkflowRepository approvals;
    private final ApprovalService approvalService;
    private final VaultDocumentRepository documents;
    private final UserProfileRepository profiles;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate newTransaction;

    @Scheduled(cron = "${hodi.reminders.cron:0 5 7 * * *}")
    public void sweep() {
        Boolean acquired = newTransaction.execute(status ->
                jdbc.queryForObject("select pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY));
        if (!Boolean.TRUE.equals(acquired)) return;
        pass(LocalDate.now(NAIROBI));
    }

    /** The pass, for a day. Package-private so a test can run it without the scheduler or the lock. */
    Map<String, Integer> pass(LocalDate today) {
        Map<String, Integer> said = new LinkedHashMap<>();
        said.put("INSTALMENT_DUE", guard("INSTALMENT_DUE", () -> instalmentsDue(today)));
        said.put("INSTALMENT_OVERDUE", guard("INSTALMENT_OVERDUE", () -> instalmentsOverdue(today)));
        said.put("VIEWING_TOMORROW", guard("VIEWING_TOMORROW", () -> viewings(today)));
        said.put("ENQUIRY_UNANSWERED", guard("ENQUIRY_UNANSWERED", () -> enquiriesUnanswered(today)));
        said.put("APPROVAL_WAITING", guard("APPROVAL_WAITING", () -> approvalsWaiting(today)));
        said.put("DOCUMENT_EXPIRING", guard("DOCUMENT_EXPIRING", () -> documentsExpiring(today)));
        if (said.values().stream().anyMatch(n -> n > 0)) log.info("Reminders: {}", said);
        return said;
    }

    private int guard(String rule, java.util.function.IntSupplier body) {
        try {
            return body.getAsInt();
        } catch (Exception e) {
            log.error("Reminder rule {} failed: {}", rule, e.getMessage());
            return 0;
        }
    }

    // ── instalments ───────────────────────────────────────────────────────────

    private record DueRow(long instalmentId, long bookingId, String label, BigDecimal amount, String currency,
                          LocalDate dueOn, BigDecimal cumulative, BigDecimal paid) {}

    private static final String DUE_SQL = """
            select i.id, i.booking_id, i.label, i.amount, i.currency, i.due_on,
                   (select coalesce(sum(i2.amount), 0) from booking_instalments i2
                     where i2.booking_id = i.booking_id and i2.plan_no = i.plan_no and i2.status <> 5 and i2.due_on <= i.due_on) as cumulative,
                   b.paid
              from booking_instalments i
              join v_booking_balances b on b.booking_id = i.booking_id
             where i.status <> 5 and b.state in ('RESERVED', 'AGREED')
               and i.plan_no = (select max(i3.plan_no) from booking_instalments i3 where i3.booking_id = i.booking_id and i3.status <> 5)
               and i.due_on %s
             order by i.due_on
            """;

    /** An instalment falling due in the rule's days, not yet covered by what has been paid. */
    private int instalmentsDue(LocalDate today) {
        int said = 0;
        for (DueRow row : jdbc.query(String.format(DUE_SQL, "between ? and ?"), this::dueRow, today, today.plusDays(60))) {
            UnitBooking booking = bookings.findById(row.bookingId()).orElse(null);
            if (booking == null) continue;
            Effective rule = rules.resolve("INSTALMENT_DUE", booking.getTenantId(), booking.getInstitutionId());
            if (!rule.enabled() || !row.dueOn().equals(today.plusDays(rule.days()))) continue;
            if (row.paid().compareTo(row.cumulative()) >= 0) continue;
            if (!rules.claim(rule, "INSTALMENT", row.instalmentId(), "", asOf(today))) continue;
            bookingNotifier.toBuyer(booking, "INSTALMENT_DUE", Map.of(
                    "home", homeOf(booking), "reference", booking.getReference(),
                    "amount", money(row.amount(), row.currency()), "when", DAY.format(row.dueOn()),
                    "label", row.label() == null ? "instalment" : row.label(), "payCode", booking.getPayReference()));
            said++;
        }
        return said;
    }

    /** Money overdue: said the rule's days after the earliest uncovered due date, and again as the rule repeats. */
    private int instalmentsOverdue(LocalDate today) {
        Map<Long, LocalDate> earliestOverdue = new LinkedHashMap<>();
        for (DueRow row : jdbc.query(String.format(DUE_SQL, "< ?"), this::dueRow, today)) {
            if (row.paid().compareTo(row.cumulative()) < 0) earliestOverdue.putIfAbsent(row.bookingId(), row.dueOn());
        }
        int said = 0;
        for (Map.Entry<Long, LocalDate> e : earliestOverdue.entrySet()) {
            UnitBooking booking = bookings.findById(e.getKey()).orElse(null);
            if (booking == null) continue;
            Effective rule = rules.resolve("INSTALMENT_OVERDUE", booking.getTenantId(), booking.getInstitutionId());
            if (!rule.enabled() || ChronoUnit.DAYS.between(e.getValue(), today) < rule.days()) continue;
            BigDecimal overdue = jdbc.queryForObject("select overdue from v_booking_balances where booking_id = ?", BigDecimal.class, booking.getId());
            if (overdue == null || overdue.signum() <= 0) continue;
            if (!rules.claim(rule, "BOOKING", booking.getId(), "overdue", asOf(today))) continue;
            bookingNotifier.toBuyer(booking, "INSTALMENT_OVERDUE", Map.of(
                    "home", homeOf(booking), "reference", booking.getReference(),
                    "overdue", money(overdue, booking.getCurrency()), "payCode", booking.getPayReference()));
            said++;
        }
        return said;
    }

    private DueRow dueRow(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new DueRow(rs.getLong("id"), rs.getLong("booking_id"), rs.getString("label"), rs.getBigDecimal("amount"),
                rs.getString("currency"), rs.getDate("due_on").toLocalDate(), rs.getBigDecimal("cumulative"),
                rs.getBigDecimal("paid") == null ? BigDecimal.ZERO : rs.getBigDecimal("paid"));
    }

    // ── viewings ──────────────────────────────────────────────────────────────

    private int viewings(LocalDate today) {
        int said = 0;
        OffsetDateTime from = today.atStartOfDay(NAIROBI).toOffsetDateTime();
        for (SiteVisit visit : visits.findConfirmedBetween(from, from.plusDays(31))) {
            Effective rule = rules.resolve("VIEWING_TOMORROW", visit.getTenantId(), null);
            if (!rule.enabled()) continue;
            LocalDate day = visit.getSlotAt().atZoneSameInstant(NAIROBI).toLocalDate();
            if (!day.equals(today.plusDays(rule.days()))) continue;
            if (!rules.claim(rule, "VIEWING", visit.getId(), "", asOf(today))) continue;
            String when = WHEN.format(visit.getSlotAt().atZoneSameInstant(NAIROBI));
            About about = new About("VIEWING", visit.getId(), visit.getReference());
            notifications.event(visit.getUserId(), Event.of("VIEWING_TOMORROW",
                    Map.of("property", visit.getPropertyTitle(), "when", when, "reference", visit.getReference()),
                    "/account/conversations?tab=viewings&ref=" + visit.getReference(), about).forOrganisation(visit.getTenantId(), null));
            notifications.event(profiles.findLiveUserIdsByTenant(visit.getTenantId()), Event.of("VIEWING_TOMORROW_STAFF",
                    Map.of("property", visit.getPropertyTitle(), "buyer", visit.getBuyerName() == null ? "The buyer" : visit.getBuyerName(),
                            "when", when, "reference", visit.getReference()),
                    "/app/viewings?ref=" + visit.getReference(), about).forOrganisation(visit.getTenantId(), null));
            said++;
        }
        return said;
    }

    // ── enquiries ─────────────────────────────────────────────────────────────

    private int enquiriesUnanswered(LocalDate today) {
        int said = 0;
        OffsetDateTime now = today.atStartOfDay(NAIROBI).toOffsetDateTime().plusDays(1);
        for (EnquiryTicket ticket : enquiries.findUnansweredSince(now)) {
            Effective rule = rules.resolve("ENQUIRY_UNANSWERED", ticket.getTenantId(), null);
            if (!rule.enabled()) continue;
            long days = ChronoUnit.DAYS.between(ticket.getLastMessageAt().atZoneSameInstant(NAIROBI).toLocalDate(), today);
            if (days < rule.days()) continue;
            // Keyed on the message time: a new message from the buyer starts the count again.
            if (!rules.claim(rule, "ENQUIRY", ticket.getId(), String.valueOf(ticket.getLastMessageAt().toEpochSecond()), asOf(today))) continue;
            notifications.event(profiles.findLiveUserIdsByTenant(ticket.getTenantId()), Event.of("ENQUIRY_UNANSWERED",
                    Map.of("property", ticket.getPropertyTitle(), "buyer", ticket.getBuyerName() == null ? "A buyer" : ticket.getBuyerName(),
                            "reference", ticket.getReference(), "days", String.valueOf(days)),
                    "/app/enquiries?ref=" + ticket.getReference(), new About("ENQUIRY", ticket.getId(), ticket.getReference()))
                    .forOrganisation(ticket.getTenantId(), null));
            said++;
        }
        return said;
    }

    // ── approvals ─────────────────────────────────────────────────────────────

    private int approvalsWaiting(LocalDate today) {
        int said = 0;
        OffsetDateTime now = today.atStartOfDay(NAIROBI).toOffsetDateTime().plusDays(1);
        for (ApprovalWorkflow w : approvals.findPendingSubmittedBefore(now)) {
            boolean platform = w.getTenantId() == null && w.getInstitutionId() == null;
            Effective rule = rules.resolve("APPROVAL_WAITING", w.getTenantId(), w.getInstitutionId());
            if (!rule.enabled()) continue;
            long days = ChronoUnit.DAYS.between(w.getSubmittedAt().atZoneSameInstant(NAIROBI).toLocalDate(), today);
            if (days < rule.days()) continue;
            String permission = approvalService.decidePermissionFor(w.getEntityType()).orElse(null);
            if (permission == null) continue;
            List<Long> checkers = platform ? profiles.findLivePlatformUserIdsHolding(permission)
                    : profiles.findLiveOrganisationUserIdsHolding(w.getTenantId(), w.getInstitutionId(), permission);
            if (checkers.isEmpty()) continue;
            if (!rules.claim(rule, "APPROVAL", w.getId(), "", asOf(today))) continue;
            Map<String, String> model = Map.of("subject", w.getSubjectLabel() == null ? w.getEntityType() : w.getSubjectLabel(),
                    "kind", w.getEntityType().toLowerCase().replace('_', ' '), "days", String.valueOf(days));
            notifications.event(checkers, Event.of(platform ? "APPROVAL_WAITING_PLATFORM" : "APPROVAL_WAITING", model,
                    "/app/approvals", new About("APPROVAL", w.getId(), w.getEntityType())).forOrganisation(w.getTenantId(), w.getInstitutionId()));
            said++;
        }
        return said;
    }

    // ── documents ─────────────────────────────────────────────────────────────

    private int documentsExpiring(LocalDate today) {
        int said = 0;
        for (VaultDocument d : documents.findExpiringBefore(today.plusDays(365))) {
            if (d.getExpiresOn().isBefore(today)) continue;
            Effective rule = rules.resolve("DOCUMENT_EXPIRING", d.getTenantId(), null);
            if (!rule.enabled() || d.getExpiresOn().isAfter(today.plusDays(rule.days()))) continue;
            if (!rules.claim(rule, "DOCUMENT", d.getId(), d.getExpiresOn().toString(), asOf(today))) continue;
            Map<String, String> model = Map.of("document", d.getDocumentName() == null ? d.getDocumentCode() : d.getDocumentName(),
                    "when", DAY.format(d.getExpiresOn()));
            About about = new About("DOCUMENT", d.getId(), d.getReference());
            if (d.getTenantId() != null) {
                notifications.event(profiles.findLiveUserIdsByTenant(d.getTenantId()),
                        Event.of("DOCUMENT_EXPIRING", model, "/app/compliance", about).forOrganisation(d.getTenantId(), null));
            } else if (d.getUserId() != null) {
                notifications.event(d.getUserId(), Event.of("DOCUMENT_EXPIRING_PERSON", model, "/account/profile", about));
            } else {
                continue;
            }
            said++;
        }
        return said;
    }

    /** The pass's day as an instant, so "said once" and "again every N days" count in days, not in seconds. */
    private static OffsetDateTime asOf(LocalDate today) {
        return today.atStartOfDay(NAIROBI).toOffsetDateTime();
    }

    // ── words ─────────────────────────────────────────────────────────────────

    private String homeOf(UnitBooking booking) {
        return jdbc.query("select coalesce(unit_label, title) as home from properties where id = ?",
                (rs, i) -> rs.getString("home"), booking.getPropertyId()).stream().findFirst().orElse("your home");
    }

    private static String money(BigDecimal amount, String currency) {
        return (currency == null ? "KES" : currency) + " " + String.format("%,.0f", amount);
    }
}
