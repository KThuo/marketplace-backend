package com.hodi.modules.payments;

import com.hodi.common.ApiResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.payments.StatementDtos.SlipResult;
import com.hodi.modules.payments.StatementDtos.StatementResponse;
import com.hodi.modules.payments.StatementDtos.TakeSlipRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Slip validation: the reference off a bank slip, looked up and applied to a booking.
 *
 * <h2>No {@code @PreAuthorize}, deliberately</h2>
 *
 * <p>Two kinds of caller may use this and they hold nothing in common: platform staff with a money
 * permission, and the buyer whose booking it is, when the institution has switched that on. A single
 * authority expression cannot say that, so the rule lives in {@link SlipValidationService#maySlip} where
 * both the lookup and the take apply it identically. Everything else is refused there with the reason.
 */
@RestController
@RequestMapping("/api/v1/payments/slip")
@RequiredArgsConstructor
public class SlipController {

    private final SlipValidationService slips;

    /** What this reference is, for this booking. Never writes anything. */
    @GetMapping
    public ApiResponse<SlipResult> validate(@RequestParam String reference, @RequestParam String bookingId) {
        SlipResult result = slips.validate(reference, bookingId);
        return ApiResponse.success(result.message(), result);
    }

    /** Confirms the reference and applies the credit. The amount is the bank's. */
    @PostMapping
    @RequestAction("TAKE_SLIP")
    public ApiResponse<StatementResponse> take(@Valid @RequestBody TakeSlipRequest request) {
        StatementResponse applied = slips.take(request);
        return ApiResponse.success(applied.currency() + " " + applied.amount().toPlainString()
                + " applied to booking " + applied.bookingReference() + " as receipt "
                + applied.paymentReference() + ".", applied);
    }
}
