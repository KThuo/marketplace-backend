package com.hodi.modules.payments;

import com.hodi.common.ApiResponse;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.infra.coop.CoopStkService;
import com.hodi.logging.RequestAction;
import com.hodi.modules.payments.PaymentIntentService.IntentResponse;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

/**
 * Asking a phone for money, and finding out what became of the ask.
 *
 * <h2>No {@code @PreAuthorize} on the ask or the reads, deliberately</h2>
 *
 * <p>Two kinds of caller and nothing in common between them: platform staff holding the money permission,
 * and the buyer whose booking it is. The rule lives in {@link PaymentIntentService#mayAsk} where both
 * paths apply it identically. The manual status enquiry stays staff-only — it is an operator's tool.
 */
@RestController
@RequestMapping("/api/v1/payments/intents")
@RequiredArgsConstructor
public class PaymentIntentController {

    private final PaymentIntentService service;
    private final CoopStkService stk;
    private final PaymentIntentRepository intents;

    public record PromptRequest(
            @NotNull(message = "Choose the booking") String bookingId,
            /** How much to ask for. Blank means what is owed now. */
            BigDecimal amount,
            /** Who to prompt. Staff may name somebody paying on the buyer's behalf; a buyer is prompted on their own. */
            String phone,
            String narration) {}

    /**
     * Sends the prompt and returns the acknowledgement.
     *
     * <p>The state comes back {@code PROCESSING} while the customer decides. Poll {@link #find} until
     * {@code settled} is true; the answer arrives by callback, by notification or by the status enquiry.
     */
    @PostMapping("/stk")
    @RequestAction("PROMPT_PAYMENT")
    public ApiResponse<IntentResponse> prompt(@Valid @RequestBody PromptRequest request) {
        IntentResponse intent = service.prompt(new PaymentIntentService.PromptRequest(
                request.bookingId(), request.amount(), request.phone(), request.narration()));
        return ApiResponse.success(
                intent.processingReason() == null
                        ? "Prompt sent to " + intent.phoneNo() + "."
                        : intent.processingReason(),
                intent);
    }

    /** One ask, as it stands. The poll. */
    @GetMapping("/{hashId}")
    public ApiResponse<IntentResponse> find(@PathVariable String hashId) {
        return ApiResponse.success(service.find(hashId));
    }

    /** An operator asking Co-op now, rather than waiting for the sweep. Neither counted nor capped. */
    @PostMapping("/{hashId}/query")
    @PreAuthorize("hasAuthority('PAYMENTS_RECEIVE')")
    public ApiResponse<IntentResponse> query(@PathVariable String hashId) {
        Long id = HashIdUtil.decodeId(hashId);
        PaymentIntent before = intents.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", hashId));
        stk.query(before.getId(), false, AuthContext.username());
        return ApiResponse.success(intents.findById(id).orElseThrow().getProcessingReason(), service.find(hashId));
    }

    @GetMapping("/booking/{hashId}")
    public ApiResponse<List<IntentResponse>> forBooking(@PathVariable String hashId) {
        return ApiResponse.success(service.forBooking(hashId));
    }
}
