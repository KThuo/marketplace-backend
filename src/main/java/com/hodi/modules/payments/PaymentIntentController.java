package com.hodi.modules.payments;

import com.hodi.common.ApiResponse;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.infra.coop.CoopStkService;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.hashid.HashIdUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Asking a buyer's phone for money, and asking the bank what became of it.
 *
 * <p>{@code PAYMENTS_RECEIVE}, the same permission as writing down that money arrived, because that is what
 * this amounts to: it puts a prompt in front of a customer and credits their booking when they approve it.
 *
 * <p>There is deliberately no endpoint that fails a payment or marks one paid by hand here. Both exist as
 * decisions a person makes with evidence in front of them, not as a button beside a spinner.
 */
@RestController
@RequestMapping("/api/v1/payments/intents")
@RequiredArgsConstructor
public class PaymentIntentController {

    private final CoopStkService stk;
    private final PaymentIntentRepository intents;

    public record PromptRequest(
            @NotNull(message = "Choose the booking") String bookingId,
            @NotNull(message = "Enter how much to ask for")
            @Positive(message = "The amount must be more than zero") BigDecimal amount,
            /** Blank prompts the buyer's own number, which is the ordinary case. */
            String phone,
            String narration) {}

    public record IntentResponse(
            String id,
            String reference,
            String state,
            BigDecimal amount,
            String currency,
            String phoneNo,
            String bankReference,
            String receipt,
            /** What was tried and what came back, in words a person can act on. */
            String processingReason,
            Integer statusQueryAttempts,
            OffsetDateTime processedAt,
            OffsetDateTime createdAt) {}

    /**
     * Prompts the buyer's phone.
     *
     * <p><b>It waits.</b> The answer is the payment, not a receipt for having asked — the request is held
     * while the customer walks to their handset and types a PIN. A wait that runs out answers with the
     * payment still in flight and says so, and the sweep settles it; it is never an error.
     */
    @PostMapping("/stk")
    @PreAuthorize("hasAuthority('PAYMENTS_RECEIVE')")
    public ApiResponse<IntentResponse> prompt(@Valid @RequestBody PromptRequest request) {
        PaymentIntent intent = stk.pushAndWait(HashIdUtil.decodeId(request.bookingId()),
                request.amount(), request.phone(), request.narration(), AuthContext.username());
        return ApiResponse.success(
                intent.getProcessingReason() == null
                        ? "Prompt sent to " + intent.getPhoneNo() + "."
                        : intent.getProcessingReason(),
                toResponse(intent));
    }

    /**
     * Asks Co-op what became of a prompt, now, because somebody is looking at it.
     *
     * <p>Not counted against the automatic cap. That cap exists to stop the sweep looping on a payment the
     * bank will never answer about; it has no business stopping a person who is trying to resolve one.
     */
    @PostMapping("/{hashId}/query")
    @PreAuthorize("hasAuthority('PAYMENTS_RECEIVE')")
    public ApiResponse<IntentResponse> query(@PathVariable String hashId) {
        Long id = HashIdUtil.decodeId(hashId);
        PaymentIntent before = intents.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", hashId));
        stk.query(before.getId(), false, AuthContext.username());
        PaymentIntent after = intents.findById(id).orElseThrow();
        return ApiResponse.success(after.getProcessingReason(), toResponse(after));
    }

    /** Everything asked for against one booking, newest first — what a person reads while waiting. */
    @GetMapping("/booking/{hashId}")
    @PreAuthorize("hasAuthority('PAYMENTS_VIEW')")
    public ApiResponse<List<IntentResponse>> forBooking(@PathVariable String hashId) {
        return ApiResponse.success(
                intents.findByBookingIdOrderByCreatedAtDesc(HashIdUtil.decodeId(hashId)).stream()
                        .map(PaymentIntentController::toResponse)
                        .toList());
    }

    private static IntentResponse toResponse(PaymentIntent intent) {
        return new IntentResponse(
                HashIdUtil.encodeId(intent.getId()),
                intent.getReference(),
                intent.getState(),
                intent.getAmount(),
                intent.getCurrency(),
                intent.getPhoneNo(),
                intent.getBankReference(),
                intent.getReceipt(),
                intent.getProcessingReason(),
                intent.getStatusQueryAttempts(),
                intent.getProcessedAt(),
                intent.getCreatedAt());
    }
}
