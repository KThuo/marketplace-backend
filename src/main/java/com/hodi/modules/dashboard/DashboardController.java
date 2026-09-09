package com.hodi.modules.dashboard;

import com.hodi.common.ApiResponse;
import com.hodi.modules.analytics.AnalyticsViews.CalendarView;
import com.hodi.modules.analytics.AnalyticsViews.MonthlyView;
import com.hodi.modules.analytics.AnalyticsViews.OverallView;
import com.hodi.modules.dashboard.DashboardService.DashboardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One endpoint for every audience's cards, and three for the figures.
 *
 * <p>The service decides which cards the caller may see, so there is no per-audience route to keep in step
 * and no way for a client to ask for somebody else's dashboard. The figures — overall, the month, the
 * calendar — filter independently, so each is its own call: stepping the calendar does not re-read the month,
 * and paging the collections does not re-read the year.
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService service;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('DASHBOARD_VIEW','BUYER_PORTAL_ACCESS')")
    public ApiResponse<DashboardResponse> dashboard() {
        return ApiResponse.success(service.build());
    }

    /** @param year omit for all time, which is what this card is for */
    @GetMapping("/overall")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ApiResponse<OverallView> overall(@RequestParam(required = false) Integer year,
                                            @RequestParam(required = false) String developmentId) {
        return ApiResponse.success(service.overall(year, developmentId));
    }

    /** @param month defaults to the month it is now — a dashboard opens on today */
    @GetMapping("/monthly")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ApiResponse<MonthlyView> monthly(@RequestParam(required = false) Integer year,
                                            @RequestParam(required = false) Integer month,
                                            @RequestParam(required = false) String developmentId,
                                            /* The collections table pages without disturbing the figures above it. */
                                            @RequestParam(defaultValue = "0") int collectionsPage,
                                            @RequestParam(defaultValue = "10") int collectionsPageSize) {
        return ApiResponse.success(service.monthly(year, month, developmentId, collectionsPage, collectionsPageSize));
    }

    @GetMapping("/calendar")
    @PreAuthorize("hasAuthority('DASHBOARD_VIEW')")
    public ApiResponse<CalendarView> calendar(@RequestParam(required = false) Integer year,
                                              @RequestParam(required = false) String developmentId) {
        return ApiResponse.success(service.calendar(year, developmentId));
    }
}
