package com.hodi.modules.payments;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.payments.PaymentDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Payments.
 *
 * <p>No update endpoint. A receipt records what a buyer was told had been received; editing one means their
 * copy and the copy on file disagree about how much they paid. Correcting a payment is a void with a reason,
 * and the row stays.
 *
 * <p>Recording money is {@code PAYMENTS_RECEIVE} and reversing it is {@code PAYMENTS_VOID}, apart from each
 * other and apart from booking a unit: a sales agent books units all day and should not be able to write down
 * that money arrived, and the person who reconciles the statement books nothing.
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService service;
    private final PaymentQueryService queries;

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('PAYMENTS_VIEW')")
    public ApiResponse<PagedResponse<PaymentResponse>> list(@ModelAttribute PaymentListRequest request) {
        return ApiResponse.success(queries.list(request));
    }

    /** The methods on offer, so the form does not carry its own copy of the list. */
    @GetMapping("/methods")
    @PreAuthorize("hasAuthority('PAYMENTS_VIEW')")
    public ApiResponse<List<MethodOption>> methods() {
        return ApiResponse.success(queries.methods());
    }

    /** The live bookings the caller may record against — the receive form's picker. */
    @GetMapping("/bookings")
    @PreAuthorize("hasAuthority('PAYMENTS_RECEIVE')")
    public ApiResponse<List<BookingOption>> bookings(@RequestParam(required = false) String search) {
        return ApiResponse.success(queries.bookingOptions(search));
    }

    /**
     * What a booking owes, for the receive form.
     *
     * <p>Under {@code PAYMENTS_VIEW} rather than {@code BOOKINGS_VIEW}: somebody taking money at a counter
     * needs to see what is owed, and requiring a separate permission for that would make the receive form
     * unusable by exactly the person who uses it.
     */
    @GetMapping("/balance/{bookingId}")
    @PreAuthorize("hasAuthority('PAYMENTS_VIEW')")
    public ApiResponse<BookingBalance> balance(@PathVariable String bookingId) {
        return ApiResponse.success(queries.balanceOf(bookingId));
    }

    @GetMapping("/find/{hashId}")
    @PreAuthorize("hasAuthority('PAYMENTS_VIEW')")
    public ApiResponse<PaymentDetail> find(@PathVariable String hashId) {
        return ApiResponse.success(queries.detail(hashId));
    }

    /** By receipt number, which is how somebody arrives holding a printout. */
    @GetMapping("/receipt/{reference}")
    @PreAuthorize("hasAuthority('PAYMENTS_VIEW')")
    public ApiResponse<PaymentDetail> byReceipt(@PathVariable String reference) {
        return ApiResponse.success(queries.byReference(reference));
    }

    @PostMapping("/receive")
    @PreAuthorize("hasAuthority('PAYMENTS_RECEIVE')")
    @RequestAction("RECEIVE_PAYMENT")
    public ApiResponse<PaymentResponse> receive(@Valid @RequestBody ReceiveRequest request) {
        PaymentResponse saved = service.receive(request);
        return ApiResponse.success("Payment " + saved.reference() + " recorded", saved);
    }

    /** A void, never an edit: the balance changes only by an entry that says why. */
    @PostMapping("/void/{hashId}")
    @PreAuthorize("hasAuthority('PAYMENTS_VOID')")
    @RequestAction("VOID_PAYMENT")
    public ApiResponse<PaymentResponse> voidPayment(@PathVariable String hashId,
                                                    @Valid @RequestBody VoidRequest request) {
        PaymentResponse voided = service.voidPayment(hashId, request);
        return ApiResponse.success("Payment " + voided.reference() + " voided", voided);
    }
}
