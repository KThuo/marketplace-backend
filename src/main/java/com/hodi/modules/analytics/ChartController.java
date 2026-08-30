package com.hodi.modules.analytics;

import com.hodi.common.ApiResponse;
import com.hodi.modules.analytics.ChartService.ChartData;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The charts, one at a time.
 *
 * <p>A caller names a key and nothing else. The key selects a row in {@link ChartCatalogue} or it is a 404 —
 * there is no path by which a request names a view, a column or a grouping, which is the same rule the
 * reporting endpoints follow and for the same reason: those cannot be bind parameters.
 *
 * <p>Guarded loosely here and precisely in the service. The annotation keeps out anybody with no reporting
 * access at all; the service then checks the chart's own permission, because a single endpoint serves charts
 * behind different ones and the tighter check has to travel with the chart rather than with the path.
 */
@RestController
@RequestMapping("/api/v1/charts")
@RequiredArgsConstructor
public class ChartController {

    private final ChartService service;

    /** What this caller may draw. Filtered server-side, so a chart they may not see is not in the JSON. */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<Map<String, String>>> available() {
        return ApiResponse.success(service.available());
    }

    @GetMapping("/{key}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<ChartData> chart(@PathVariable String key) {
        return ApiResponse.success(service.draw(key));
    }
}
