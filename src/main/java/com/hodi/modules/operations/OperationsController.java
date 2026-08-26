package com.hodi.modules.operations;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.operations.AssignmentService.*;
import com.hodi.modules.operations.CalendarService.EntryListRequest;
import com.hodi.modules.operations.CalendarService.EntryResponse;
import com.hodi.modules.operations.CalendarService.SaveEntryRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Routing and the diary (M12).
 *
 * <p>One controller because they are one job seen twice: deciding whose work something is, and seeing when
 * it happens. Both are scoped the ordinary way — a seller's rules and a seller's week are their own.
 */
@RestController
@RequiredArgsConstructor
public class OperationsController {

    private final AssignmentService assignment;
    private final CalendarService calendar;

    // ── routing ───────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/assignment-rules/list")
    @PreAuthorize("hasAuthority('ASSIGNMENT_VIEW')")
    public ApiResponse<PagedResponse<RuleResponse>> rules(@ModelAttribute RuleListRequest request) {
        return ApiResponse.success(assignment.list(request));
    }

    @PostMapping("/api/v1/assignment-rules/create")
    @PreAuthorize("hasAuthority('ASSIGNMENT_MANAGE')")
    @RequestAction("CREATE ROUTING RULE")
    public ApiResponse<RuleResponse> createRule(@Valid @RequestBody SaveRuleRequest request) {
        return ApiResponse.success("Rule added", assignment.create(request));
    }

    @PostMapping("/api/v1/assignment-rules/{reference}/update")
    @PreAuthorize("hasAuthority('ASSIGNMENT_MANAGE')")
    @RequestAction("UPDATE ROUTING RULE")
    public ApiResponse<RuleResponse> updateRule(@PathVariable String reference,
                                                @Valid @RequestBody SaveRuleRequest request) {
        return ApiResponse.success("Saved", assignment.update(reference, request));
    }

    @PostMapping("/api/v1/assignment-rules/{reference}/set-active")
    @PreAuthorize("hasAuthority('ASSIGNMENT_MANAGE')")
    @RequestAction("SET ROUTING RULE ACTIVE")
    public ApiResponse<RuleResponse> setRuleActive(@PathVariable String reference,
                                                   @RequestBody Map<String, Boolean> body) {
        return ApiResponse.success("Saved",
                assignment.setActive(reference, Boolean.TRUE.equals(body.get("active"))));
    }

    @PostMapping("/api/v1/assignment-rules/{reference}/delete")
    @PreAuthorize("hasAuthority('ASSIGNMENT_MANAGE')")
    @RequestAction("ARCHIVE ROUTING RULE")
    public ApiResponse<Void> archiveRule(@PathVariable String reference) {
        assignment.archive(reference);
        return ApiResponse.success("Archived", null);
    }

    // ── the diary ─────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/calendar/list")
    @PreAuthorize("hasAuthority('CALENDAR_VIEW')")
    public ApiResponse<PagedResponse<EntryResponse>> entries(@ModelAttribute EntryListRequest request) {
        return ApiResponse.success(calendar.list(request));
    }

    @PostMapping("/api/v1/calendar/create")
    @PreAuthorize("hasAuthority('CALENDAR_MANAGE')")
    @RequestAction("ADD A DIARY ENTRY")
    public ApiResponse<EntryResponse> createEntry(@Valid @RequestBody SaveEntryRequest request) {
        return ApiResponse.success("Added", calendar.create(request));
    }

    @PostMapping("/api/v1/calendar/{reference}/update")
    @PreAuthorize("hasAuthority('CALENDAR_MANAGE')")
    @RequestAction("UPDATE A DIARY ENTRY")
    public ApiResponse<EntryResponse> updateEntry(@PathVariable String reference,
                                                  @Valid @RequestBody SaveEntryRequest request) {
        return ApiResponse.success("Saved", calendar.update(reference, request));
    }

    @PostMapping("/api/v1/calendar/{reference}/delete")
    @PreAuthorize("hasAuthority('CALENDAR_MANAGE')")
    @RequestAction("REMOVE A DIARY ENTRY")
    public ApiResponse<Void> archiveEntry(@PathVariable String reference) {
        calendar.archive(reference);
        return ApiResponse.success("Removed", null);
    }
}
