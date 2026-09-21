package com.hodi.modules.analytics;

import com.hodi.common.ApiResponse;
import com.hodi.modules.analytics.AnalyticsViews.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The analytics page: six reads over one window.
 *
 * <p>Every endpoint takes the same four window parameters and the same optional development, so the page can
 * send one query object to all of them and be sure they describe the same months. Omitted, the window is the
 * last twelve months ending this one.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService service;

    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ApiResponse<SummaryView> summary(
            @RequestParam(required = false) Integer fromYear, @RequestParam(required = false) Integer fromMonth,
            @RequestParam(required = false) Integer toYear, @RequestParam(required = false) Integer toMonth,
            @RequestParam(required = false) String developmentId) {
        return ApiResponse.success(service.summary(
                AnalyticsWindow.of(fromYear, fromMonth, toYear, toMonth), developmentId));
    }

    @GetMapping("/trend")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ApiResponse<TrendView> trend(
            @RequestParam(required = false) Integer fromYear, @RequestParam(required = false) Integer fromMonth,
            @RequestParam(required = false) Integer toYear, @RequestParam(required = false) Integer toMonth,
            @RequestParam(required = false) String developmentId) {
        return ApiResponse.success(service.trend(
                AnalyticsWindow.of(fromYear, fromMonth, toYear, toMonth), developmentId));
    }

    @GetMapping("/composition")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ApiResponse<CompositionView> composition(
            @RequestParam(required = false) Integer fromYear, @RequestParam(required = false) Integer fromMonth,
            @RequestParam(required = false) Integer toYear, @RequestParam(required = false) Integer toMonth,
            @RequestParam(required = false) String developmentId) {
        return ApiResponse.success(service.composition(
                AnalyticsWindow.of(fromYear, fromMonth, toYear, toMonth), developmentId));
    }

    /** Today's position: no window. */
    @GetMapping("/receivables")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ApiResponse<ReceivablesView> receivables(@RequestParam(required = false) String developmentId) {
        return ApiResponse.success(service.receivables(developmentId));
    }

    @GetMapping("/developments")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ApiResponse<DevelopmentsView> developments(
            @RequestParam(required = false) Integer fromYear, @RequestParam(required = false) Integer fromMonth,
            @RequestParam(required = false) Integer toYear, @RequestParam(required = false) Integer toMonth,
            @RequestParam(required = false) String developmentId) {
        return ApiResponse.success(service.developments(
                AnalyticsWindow.of(fromYear, fromMonth, toYear, toMonth), developmentId));
    }

    @GetMapping("/collections")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ApiResponse<CollectionsView> collections(
            @RequestParam(required = false) Integer fromYear, @RequestParam(required = false) Integer fromMonth,
            @RequestParam(required = false) Integer toYear, @RequestParam(required = false) Integer toMonth,
            @RequestParam(required = false) String developmentId) {
        return ApiResponse.success(service.collections(
                AnalyticsWindow.of(fromYear, fromMonth, toYear, toMonth), developmentId));
    }

    /** Leads sit on listings, which have no development, so this takes the window alone. */
    @GetMapping("/funnel")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ApiResponse<FunnelView> funnel(
            @RequestParam(required = false) Integer fromYear, @RequestParam(required = false) Integer fromMonth,
            @RequestParam(required = false) Integer toYear, @RequestParam(required = false) Integer toMonth) {
        return ApiResponse.success(service.funnel(AnalyticsWindow.of(fromYear, fromMonth, toYear, toMonth)));
    }

    @GetMapping("/inventory")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ApiResponse<InventoryView> inventory(
            @RequestParam(required = false) Integer fromYear, @RequestParam(required = false) Integer fromMonth,
            @RequestParam(required = false) Integer toYear, @RequestParam(required = false) Integer toMonth,
            @RequestParam(required = false) String developmentId) {
        return ApiResponse.success(service.inventory(
                AnalyticsWindow.of(fromYear, fromMonth, toYear, toMonth), developmentId));
    }

    /** The bank's own figures. Refused to anyone but the platform inside the service. */
    @GetMapping("/bank")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ApiResponse<BankView> bank(
            @RequestParam(required = false) Integer fromYear, @RequestParam(required = false) Integer fromMonth,
            @RequestParam(required = false) Integer toYear, @RequestParam(required = false) Integer toMonth) {
        return ApiResponse.success(service.bank(AnalyticsWindow.of(fromYear, fromMonth, toYear, toMonth)));
    }

    @GetMapping("/pipeline")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ApiResponse<PipelineView> pipeline(
            @RequestParam(required = false) Integer fromYear, @RequestParam(required = false) Integer fromMonth,
            @RequestParam(required = false) Integer toYear, @RequestParam(required = false) Integer toMonth) {
        return ApiResponse.success(service.pipeline(AnalyticsWindow.of(fromYear, fromMonth, toYear, toMonth)));
    }
}
