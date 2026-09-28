package com.hodi.modules.settlements;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.logging.RequestAction;
import com.hodi.modules.settlements.SettlementDtos.DevelopmentSettlements;
import com.hodi.modules.settlements.SettlementDtos.QueueRow;
import com.hodi.modules.settlements.SettlementDtos.SettleRequest;
import com.hodi.modules.settlements.SettlementDtos.SettlementResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** Settling the sales the bank collected. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SettlementController {

    private final SettlementService settlements;

    /** The queue: {@code ?settled=false} (the default) for what awaits or is on its way, {@code true} for what is done. */
    @GetMapping("/settlements")
    @PreAuthorize("hasAuthority('SETTLEMENTS_VIEW')")
    public ApiResponse<PagedResponse<QueueRow>> queue(@RequestParam(defaultValue = "false") boolean settled,
                                                      @ModelAttribute PagedDataRequest request) {
        return ApiResponse.success(settlements.queue(settled, request));
    }

    @GetMapping("/bookings/{bookingId}/settlement")
    @PreAuthorize("hasAuthority('SETTLEMENTS_VIEW')")
    public ApiResponse<SettlementResponse> forBooking(@PathVariable String bookingId) {
        return ApiResponse.success(settlements.forBooking(bookingId));
    }

    @PostMapping("/bookings/{bookingId}/settle")
    @PreAuthorize("hasAuthority('SETTLEMENTS_MAKE')")
    @RequestAction("SETTLE A SALE")
    public ApiResponse<SettlementResponse> settle(@PathVariable String bookingId,
                                                  @Valid @RequestBody(required = false) SettleRequest request) {
        SettlementResponse settled = settlements.settle(bookingId, request);
        return ApiResponse.success("Proposed: " + settled.legs().size() + " transfer"
                + (settled.legs().size() == 1 ? "" : "s") + " waiting for a second person.", settled);
    }

    @GetMapping("/developments/{hashId}/finance/settlements")
    @PreAuthorize("hasAnyAuthority('SETTLEMENTS_VIEW', 'DEVELOPMENTS_FINANCE_VIEW')")
    public ApiResponse<DevelopmentSettlements> forDevelopment(@PathVariable String hashId) {
        return ApiResponse.success(settlements.forDevelopment(hashId));
    }
}
