package com.hodi.modules.dashboard;

import com.hodi.common.ApiResponse;
import com.hodi.modules.dashboard.DashboardService.DashboardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * One endpoint for every audience. The service decides which cards the caller may see, so there is no
 * per-audience route to keep in step and no way for a client to ask for somebody else's dashboard.
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
}
