package com.hodi.modules.bookings;

import com.hodi.common.ApiResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.bookings.BookingDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Bookings, schedules and payments, under the development that owns them.
 *
 * <p>Nested under {@code /developments/{hashId}} rather than a flat {@code /bookings/{id}}, because every
 * check here starts from the development: whether the caller may read it, and whether they may write its
 * units. A flat path would have to re-derive that from the booking on every call, which is one more place to
 * get it wrong.
 *
 * <p>Recording money is gated on {@code BOOKINGS_PAYMENTS} rather than {@code BOOKINGS_MANAGE}. A sales agent
 * books units and should not be able to write down that money arrived; the person reconciling the bank
 * statement does exactly that and books nothing.
 */
@RestController
@RequestMapping("/api/v1/developments")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService service;

    // ── reading ───────────────────────────────────────────────────────────────

    @GetMapping("/{hashId}/units/{unitId}/bookings")
    @PreAuthorize("hasAuthority('BOOKINGS_VIEW')")
    public ApiResponse<List<BookingResponse>> forUnit(@PathVariable String hashId,
                                                      @PathVariable String unitId) {
        return ApiResponse.success(service.forUnit(hashId, unitId));
    }

    @GetMapping("/{hashId}/bookings/{bookingId}")
    @PreAuthorize("hasAuthority('BOOKINGS_VIEW')")
    public ApiResponse<BookingResponse> find(@PathVariable String hashId,
                                              @PathVariable String bookingId) {
        return ApiResponse.success(service.find(hashId, bookingId));
    }

    @GetMapping("/{hashId}/bookings/{bookingId}/schedule")
    @PreAuthorize("hasAuthority('BOOKINGS_VIEW')")
    public ApiResponse<List<InstalmentResponse>> schedule(@PathVariable String hashId,
                                                          @PathVariable String bookingId) {
        return ApiResponse.success(service.schedule(hashId, bookingId));
    }

    @GetMapping("/{hashId}/bookings/{bookingId}/payments")
    @PreAuthorize("hasAuthority('BOOKINGS_VIEW')")
    public ApiResponse<List<PaymentResponse>> payments(@PathVariable String hashId,
                                                        @PathVariable String bookingId) {
        return ApiResponse.success(service.paymentsFor(hashId, bookingId));
    }

    // ── the booking's life ────────────────────────────────────────────────────

    @PostMapping("/{hashId}/bookings")
    @PreAuthorize("hasAuthority('BOOKINGS_MANAGE')")
    @RequestAction("CREATE_BOOKING")
    public ApiResponse<BookingResponse> create(@PathVariable String hashId,
                                                @Valid @RequestBody CreateBookingRequest request) {
        return ApiResponse.success("Unit booked", service.create(hashId, request));
    }

    @PostMapping("/{hashId}/bookings/{bookingId}/agree")
    @PreAuthorize("hasAuthority('BOOKINGS_MANAGE')")
    @RequestAction("AGREE_BOOKING")
    public ApiResponse<BookingResponse> agree(@PathVariable String hashId,
                                               @PathVariable String bookingId) {
        return ApiResponse.success("Booking agreed", service.agree(hashId, bookingId));
    }

    @PostMapping("/{hashId}/bookings/{bookingId}/complete")
    @PreAuthorize("hasAuthority('BOOKINGS_MANAGE')")
    @RequestAction("COMPLETE_BOOKING")
    public ApiResponse<BookingResponse> complete(@PathVariable String hashId,
                                                  @PathVariable String bookingId) {
        return ApiResponse.success("Booking completed", service.complete(hashId, bookingId));
    }

    @PostMapping("/{hashId}/bookings/{bookingId}/cancel")
    @PreAuthorize("hasAuthority('BOOKINGS_MANAGE')")
    @RequestAction("CANCEL_BOOKING")
    public ApiResponse<BookingResponse> cancel(@PathVariable String hashId,
                                                @PathVariable String bookingId,
                                                @Valid @RequestBody CloseBookingRequest request) {
        return ApiResponse.success("Booking cancelled", service.cancel(hashId, bookingId, request));
    }

    @PostMapping("/{hashId}/bookings/{bookingId}/reschedule")
    @PreAuthorize("hasAuthority('BOOKINGS_MANAGE')")
    @RequestAction("RESCHEDULE_BOOKING")
    public ApiResponse<List<InstalmentResponse>> reschedule(
            @PathVariable String hashId,
            @PathVariable String bookingId,
            @Valid @RequestBody RescheduleRequest request) {
        return ApiResponse.success("Schedule revised", service.reschedule(hashId, bookingId, request));
    }

    // ── money ─────────────────────────────────────────────────────────────────

    @PostMapping("/{hashId}/bookings/{bookingId}/payments")
    @PreAuthorize("hasAuthority('BOOKINGS_PAYMENTS')")
    @RequestAction("RECORD_BOOKING_PAYMENT")
    public ApiResponse<PaymentResponse> recordPayment(
            @PathVariable String hashId,
            @PathVariable String bookingId,
            @Valid @RequestBody RecordPaymentRequest request) {
        return ApiResponse.success("Payment recorded", service.recordPayment(hashId, bookingId, request));
    }

    /** A reversal, never an edit: the balance changes only by an entry that says why. */
    @PostMapping("/{hashId}/bookings/{bookingId}/payments/{paymentId}/reverse")
    @PreAuthorize("hasAuthority('BOOKINGS_PAYMENTS')")
    @RequestAction("REVERSE_BOOKING_PAYMENT")
    public ApiResponse<PaymentResponse> reversePayment(
            @PathVariable String hashId,
            @PathVariable String bookingId,
            @PathVariable String paymentId,
            @Valid @RequestBody ReversePaymentRequest request) {
        return ApiResponse.success("Payment reversed",
                service.reversePayment(hashId, bookingId, paymentId, request));
    }
}
