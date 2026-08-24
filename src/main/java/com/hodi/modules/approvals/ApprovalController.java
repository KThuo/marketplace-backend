package com.hodi.modules.approvals;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.approvals.ApprovalService.ApprovalListRequest;
import com.hodi.modules.approvals.ApprovalService.ApprovalResponse;
import com.hodi.modules.approvals.ApprovalService.DecisionRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * The approvals queue.
 *
 * <p>{@code APPROVALS_VIEW} gets you the list. Deciding is gated on the <em>domain's</em> permission, checked
 * in the service against the handler for that entity type — so this controller has no "may approve anything"
 * authority to grant, and the queue cannot become a way around a module's own gate.
 */
@RestController
@RequestMapping("/api/v1/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService service;

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('APPROVALS_VIEW')")
    public ApiResponse<PagedResponse<ApprovalResponse>> list(
            @ModelAttribute ApprovalListRequest request) {
        return ApiResponse.success(service.list(request));
    }

    /** How many decisions this caller's organisation owes. Cheap enough for the shell to poll on load. */
    @GetMapping("/pending-count")
    @PreAuthorize("hasAuthority('APPROVALS_VIEW')")
    public ApiResponse<Map<String, Long>> pendingCount() {
        return ApiResponse.success(Map.of("pending", service.pendingForCaller()));
    }

    @PostMapping("/decide/{hashId}")
    @PreAuthorize("hasAuthority('APPROVALS_VIEW')")
    @RequestAction("DECIDE_APPROVAL")
    public ApiResponse<ApprovalResponse> decide(@PathVariable String hashId,
                                                @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.success("Decision recorded", service.decide(hashId, request));
    }
}
