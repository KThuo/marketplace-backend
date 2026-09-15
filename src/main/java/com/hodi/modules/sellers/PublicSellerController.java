package com.hodi.modules.sellers;

import com.hodi.common.ApiResponse;
import com.hodi.modules.sellers.SellerDtos.ApplyOutcome;
import com.hodi.modules.sellers.SellerDtos.ApplyRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where a seller starts, with no session.
 *
 * <p>Permitted by name in {@code SecurityConfig}, alongside the agent and vendor applications it is
 * modelled on. What it creates grants nothing — no organisation, and a profile whose KYC status fails the
 * listing gate — so an open endpoint here costs an account row and nothing else.
 */
@RestController
@RequestMapping("/api/v1/public/sellers")
@RequiredArgsConstructor
public class PublicSellerController {

    private final SellerApplicationService service;

    @PostMapping("/apply")
    public ApiResponse<ApplyOutcome> apply(@Valid @RequestBody ApplyRequest request) {
        return ApiResponse.success("Application started", service.apply(request));
    }
}
