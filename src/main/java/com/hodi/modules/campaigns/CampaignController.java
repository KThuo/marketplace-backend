package com.hodi.modules.campaigns;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.campaigns.CampaignService.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Campaigns: written, submitted, approved by a second person, sent by the sweep. */
@RestController
@RequestMapping("/api/v1/campaigns")
@RequiredArgsConstructor
public class CampaignController {

    private final CampaignService campaigns;

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('CAMPAIGNS_VIEW')")
    public ApiResponse<PagedResponse<CampaignResponse>> list(@ModelAttribute CampaignListRequest request) {
        return ApiResponse.success(campaigns.list(request));
    }

    @GetMapping("/{reference}")
    @PreAuthorize("hasAuthority('CAMPAIGNS_VIEW')")
    public ApiResponse<CampaignResponse> find(@PathVariable String reference) {
        return ApiResponse.success(campaigns.find(reference));
    }

    @GetMapping("/{reference}/sends")
    @PreAuthorize("hasAuthority('CAMPAIGNS_VIEW')")
    public ApiResponse<List<SendRow>> sends(@PathVariable String reference) {
        return ApiResponse.success(campaigns.sendsOf(reference));
    }

    @GetMapping("/{reference}/audience")
    @PreAuthorize("hasAuthority('CAMPAIGNS_VIEW')")
    public ApiResponse<Map<String, Integer>> audience(@PathVariable String reference) {
        return ApiResponse.success(Map.of("count", campaigns.audienceCount(reference)));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('CAMPAIGNS_MANAGE')")
    @RequestAction("CREATE CAMPAIGN")
    public ApiResponse<CampaignResponse> create(@Valid @RequestBody SaveRequest request) {
        return ApiResponse.success("Draft saved", campaigns.create(request));
    }

    @PostMapping("/{reference}")
    @PreAuthorize("hasAuthority('CAMPAIGNS_MANAGE')")
    @RequestAction("UPDATE CAMPAIGN")
    public ApiResponse<CampaignResponse> update(@PathVariable String reference, @Valid @RequestBody SaveRequest request) {
        return ApiResponse.success("Saved", campaigns.update(reference, request));
    }

    @PostMapping("/{reference}/submit")
    @PreAuthorize("hasAuthority('CAMPAIGNS_MANAGE')")
    @RequestAction("SUBMIT CAMPAIGN")
    public ApiResponse<CampaignResponse> submit(@PathVariable String reference) {
        return ApiResponse.success("Submitted for approval", campaigns.submit(reference));
    }

    @PostMapping("/{reference}/decide")
    @PreAuthorize("hasAuthority('CAMPAIGNS_APPROVE')")
    @RequestAction("DECIDE CAMPAIGN")
    public ApiResponse<CampaignResponse> decide(@PathVariable String reference, @RequestBody ApprovalService.DecisionRequest request) {
        CampaignResponse decided = campaigns.decide(reference, request);
        return ApiResponse.success("APPROVED".equalsIgnoreCase(request.decision()) ? "Approved — it goes out inside the sending window"
                : "Sent back to the author", decided);
    }

    @PostMapping("/{reference}/cancel")
    @PreAuthorize("hasAuthority('CAMPAIGNS_MANAGE')")
    @RequestAction("CANCEL CAMPAIGN")
    public ApiResponse<CampaignResponse> cancel(@PathVariable String reference, @RequestBody(required = false) Map<String, String> body) {
        return ApiResponse.success("Cancelled", campaigns.cancel(reference, body == null ? null : body.get("reason")));
    }

    /** To the author alone, now. */
    @PostMapping("/{reference}/test")
    @PreAuthorize("hasAuthority('CAMPAIGNS_MANAGE')")
    @RequestAction("TEST CAMPAIGN")
    public ApiResponse<Void> test(@PathVariable String reference) {
        campaigns.sendTest(reference);
        return ApiResponse.success("Sent to you", null);
    }
}
