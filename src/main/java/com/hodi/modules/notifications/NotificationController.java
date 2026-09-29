package com.hodi.modules.notifications;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.logging.RequestAction;
import com.hodi.modules.notifications.NotificationService.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * A person's inbox, and the platform's record of everything sent.
 *
 * <p>The inbox needs no permission beyond being signed in: it is the person's own. The log is read by
 * whoever reads the audit trail, because that is what it is — the audit of what the platform said — and
 * a retry by whoever changes settings.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notifications;
    private final NotificationCatalogue catalogue;
    private final ReminderRuleService reminders;

    // ── the reminders ─────────────────────────────────────────────────────────

    @GetMapping("/notifications/reminders")
    @PreAuthorize("hasAuthority('APP_SETTINGS_VIEW')")
    public ApiResponse<List<ReminderRuleService.RuleRow>> reminderRules() {
        return ApiResponse.success(reminders.all());
    }

    @PostMapping("/notifications/reminders/{code}")
    @PreAuthorize("hasAuthority('APP_SETTINGS_UPDATE')")
    @RequestAction("EDIT REMINDER RULE")
    public ApiResponse<ReminderRuleService.RuleRow> saveRule(@PathVariable String code,
                                                             @RequestBody ReminderRuleService.SaveRuleRequest request) {
        return ApiResponse.success("Saved", reminders.save(code, request));
    }

    @GetMapping("/notifications/reminders/mine")
    @PreAuthorize("hasAuthority('APP_SETTINGS_OVERRIDE')")
    public ApiResponse<List<ReminderRuleService.RuleRow>> myReminderRules() {
        return ApiResponse.success(reminders.mine());
    }

    @PostMapping("/notifications/reminders/mine/{code}")
    @PreAuthorize("hasAuthority('APP_SETTINGS_OVERRIDE')")
    @RequestAction("OVERRIDE REMINDER RULE")
    public ApiResponse<ReminderRuleService.RuleRow> saveMyRule(@PathVariable String code,
                                                               @RequestBody ReminderRuleService.SaveOverrideRequest request) {
        return ApiResponse.success("Saved for your organisation", reminders.saveMine(code, request));
    }

    // ── the catalogue ─────────────────────────────────────────────────────────

    public record CatalogueView(List<NotificationCatalogue.EventRow> events, boolean organisationsMayReword) {}

    @GetMapping("/notifications/catalogue")
    @PreAuthorize("hasAuthority('APP_SETTINGS_VIEW')")
    public ApiResponse<CatalogueView> catalogue() {
        return ApiResponse.success(new CatalogueView(catalogue.all(), catalogue.organisationsMayReword()));
    }

    @PostMapping("/notifications/catalogue/{code}")
    @PreAuthorize("hasAuthority('APP_SETTINGS_UPDATE')")
    @RequestAction("EDIT NOTIFICATION")
    public ApiResponse<NotificationCatalogue.EventRow> saveEvent(@PathVariable String code,
                                                                 @RequestBody NotificationCatalogue.SaveEventRequest request) {
        return ApiResponse.success("Saved — from now on, this is what is said", catalogue.save(code, request));
    }

    /** The catalogue as the caller's organisation sees it, with its own answers. */
    @GetMapping("/notifications/catalogue/mine")
    @PreAuthorize("hasAuthority('APP_SETTINGS_OVERRIDE')")
    public ApiResponse<CatalogueView> mine() {
        return ApiResponse.success(new CatalogueView(catalogue.mine(), catalogue.organisationsMayReword()));
    }

    @PostMapping("/notifications/catalogue/mine/{code}")
    @PreAuthorize("hasAuthority('APP_SETTINGS_OVERRIDE')")
    @RequestAction("OVERRIDE NOTIFICATION")
    public ApiResponse<NotificationCatalogue.EventRow> saveMine(@PathVariable String code,
                                                                @RequestBody NotificationCatalogue.SaveOverrideRequest request) {
        return ApiResponse.success("Saved for your organisation", catalogue.saveMine(code, request));
    }

    // ── mine ──────────────────────────────────────────────────────────────────

    /** The bell: how many unread, and the latest few. */
    @GetMapping("/me/notifications/summary")
    public ApiResponse<InboxSummary> summary() {
        return ApiResponse.success(notifications.summary());
    }

    @GetMapping("/me/notifications")
    public ApiResponse<PagedResponse<InboxItem>> mine(@ModelAttribute PagedDataRequest request) {
        return ApiResponse.success(notifications.mine(request));
    }

    @PostMapping("/me/notifications/{hashId}/read")
    public ApiResponse<InboxItem> read(@PathVariable String hashId) {
        return ApiResponse.success(notifications.markRead(hashId));
    }

    @PostMapping("/me/notifications/read-all")
    public ApiResponse<Map<String, Integer>> readAll() {
        return ApiResponse.success(Map.of("marked", notifications.markAllRead()));
    }

    // ── the log ───────────────────────────────────────────────────────────────

    @GetMapping("/notifications/log/list")
    @PreAuthorize("hasAuthority('AUDIT_VIEW')")
    public ApiResponse<PagedResponse<LogRow>> log(@ModelAttribute LogListRequest request) {
        return ApiResponse.success(notifications.list(request));
    }

    @PostMapping("/notifications/log/{hashId}/retry")
    @PreAuthorize("hasAuthority('APP_SETTINGS_UPDATE')")
    @RequestAction("RETRY NOTIFICATION")
    public ApiResponse<LogRow> retry(@PathVariable String hashId) {
        LogRow row = notifications.retryNow(hashId);
        return ApiResponse.success(NotificationLog.SENT.equals(row.status()) ? "Sent" : "Still " + row.status().toLowerCase(), row);
    }
}
