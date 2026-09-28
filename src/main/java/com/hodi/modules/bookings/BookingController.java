package com.hodi.modules.bookings;

import com.hodi.common.ApiResponse;
import com.hodi.common.PagedResponse;
import com.hodi.logging.RequestAction;
import com.hodi.modules.bookings.BookingDtos.*;
import com.hodi.modules.payments.PaymentDtos.PaymentResponse;
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
 * <p>Money is not recorded here. A booking's payments are read from this path because the drawer shows them,
 * but receiving and voiding are the payments module's ({@code /api/v1/payments}), under their own
 * permissions: a sales agent books units and should not be able to write down that money arrived.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService service;

    // ── a home's bookings, whichever kind of home ─────────────────────────────

    @GetMapping("/properties/{hashId}/bookings")
    @PreAuthorize("hasAuthority('BOOKINGS_VIEW')")
    public ApiResponse<List<BookingResponse>> forProperty(@PathVariable String hashId) {
        return ApiResponse.success(service.forProperty(hashId));
    }

    @PostMapping("/properties/{hashId}/bookings")
    @PreAuthorize("hasAuthority('BOOKINGS_MANAGE')")
    @RequestAction("CREATE_BOOKING")
    public ApiResponse<BookingResponse> createForProperty(@PathVariable String hashId,
                                                          @Valid @RequestBody CreateBookingRequest request) {
        return ApiResponse.success("Booked", service.createForProperty(hashId, request));
    }

    // ── a booking by its own id ───────────────────────────────────────────────

    /**
     * Every booking this caller may see — the page that tracks what customers still owe.
     *
     * <p>{@code BOOKINGS_VIEW} rather than a development's own permission: the list crosses
     * developments by definition, and what narrows it is who the caller is, which the service applies.
     */
    @GetMapping("/bookings/list")
    @PreAuthorize("hasAuthority('BOOKINGS_VIEW')")
    public ApiResponse<PagedResponse<BookingResponse>> list(
            @ModelAttribute BookingDtos.BookingListRequest request) {
        return ApiResponse.success(service.list(request));
    }

    @GetMapping("/bookings/{bookingId}")
    @PreAuthorize("hasAuthority('BOOKINGS_VIEW')")
    public ApiResponse<BookingResponse> get(@PathVariable String bookingId) {
        return ApiResponse.success(service.find(bookingId));
    }

    @GetMapping("/bookings/{bookingId}/schedule")
    @PreAuthorize("hasAuthority('BOOKINGS_VIEW')")
    public ApiResponse<List<InstalmentResponse>> scheduleOf(@PathVariable String bookingId) {
        return ApiResponse.success(service.schedule(bookingId));
    }

    @GetMapping("/bookings/{bookingId}/payments")
    @PreAuthorize("hasAuthority('BOOKINGS_VIEW')")
    public ApiResponse<List<PaymentResponse>> paymentsOf(@PathVariable String bookingId) {
        return ApiResponse.success(service.paymentsFor(bookingId));
    }

    @PostMapping("/bookings/{bookingId}/agree")
    @PreAuthorize("hasAuthority('BOOKINGS_MANAGE')")
    @RequestAction("AGREE_BOOKING")
    public ApiResponse<BookingResponse> agreeById(@PathVariable String bookingId) {
        return ApiResponse.success("Booking agreed", service.agree(bookingId));
    }

    @PostMapping("/bookings/{bookingId}/introducer")
    @PreAuthorize("hasAuthority('BOOKINGS_MANAGE')")
    @RequestAction("NAME WHO BROUGHT THE BUYER")
    public ApiResponse<BookingResponse> setIntroducer(@PathVariable String bookingId,
                                                      @Valid @RequestBody IntroducerRequest request) {
        return ApiResponse.success("Saved", service.setIntroducer(bookingId, request));
    }

    @PostMapping("/bookings/{bookingId}/complete")
    @PreAuthorize("hasAuthority('BOOKINGS_MANAGE')")
    @RequestAction("COMPLETE_BOOKING")
    public ApiResponse<BookingResponse> completeById(@PathVariable String bookingId) {
        return ApiResponse.success("Booking completed", service.complete(bookingId));
    }

    @PostMapping("/bookings/{bookingId}/cancel")
    @PreAuthorize("hasAuthority('BOOKINGS_MANAGE')")
    @RequestAction("CANCEL_BOOKING")
    public ApiResponse<BookingResponse> cancelById(@PathVariable String bookingId,
                                                   @Valid @RequestBody CloseBookingRequest request) {
        return ApiResponse.success("Booking cancelled", service.cancel(bookingId, request));
    }

    @PostMapping("/bookings/{bookingId}/reschedule")
    @PreAuthorize("hasAuthority('BOOKINGS_MANAGE')")
    @RequestAction("RESCHEDULE_BOOKING")
    public ApiResponse<List<InstalmentResponse>> rescheduleById(@PathVariable String bookingId,
                                                                @Valid @RequestBody RescheduleRequest request) {
        return ApiResponse.success("Schedule revised", service.reschedule(bookingId, request));
    }

    // ── the inventory screen's routes, under the development ──────────────────

    // ── reading ───────────────────────────────────────────────────────────────

    @GetMapping("/developments/{hashId}/units/{unitId}/bookings")
    @PreAuthorize("hasAuthority('BOOKINGS_VIEW')")
    public ApiResponse<List<BookingResponse>> forUnit(@PathVariable String hashId,
                                                      @PathVariable String unitId) {
        return ApiResponse.success(service.forUnit(hashId, unitId));
    }

    @GetMapping("/developments/{hashId}/bookings/{bookingId}")
    @PreAuthorize("hasAuthority('BOOKINGS_VIEW')")
    public ApiResponse<BookingResponse> find(@PathVariable String hashId,
                                              @PathVariable String bookingId) {
        return ApiResponse.success(service.find(hashId, bookingId));
    }

    @GetMapping("/developments/{hashId}/bookings/{bookingId}/schedule")
    @PreAuthorize("hasAuthority('BOOKINGS_VIEW')")
    public ApiResponse<List<InstalmentResponse>> schedule(@PathVariable String hashId,
                                                          @PathVariable String bookingId) {
        return ApiResponse.success(service.schedule(hashId, bookingId));
    }

    @GetMapping("/developments/{hashId}/bookings/{bookingId}/payments")
    @PreAuthorize("hasAuthority('BOOKINGS_VIEW')")
    public ApiResponse<List<PaymentResponse>> payments(@PathVariable String hashId,
                                                        @PathVariable String bookingId) {
        return ApiResponse.success(service.paymentsFor(hashId, bookingId));
    }

    // ── the booking's life ────────────────────────────────────────────────────

    @PostMapping("/developments/{hashId}/bookings")
    @PreAuthorize("hasAuthority('BOOKINGS_MANAGE')")
    @RequestAction("CREATE_BOOKING")
    public ApiResponse<BookingResponse> create(@PathVariable String hashId,
                                                @Valid @RequestBody CreateBookingRequest request) {
        return ApiResponse.success("Unit booked", service.create(hashId, request));
    }

    @PostMapping("/developments/{hashId}/bookings/{bookingId}/agree")
    @PreAuthorize("hasAuthority('BOOKINGS_MANAGE')")
    @RequestAction("AGREE_BOOKING")
    public ApiResponse<BookingResponse> agree(@PathVariable String hashId,
                                               @PathVariable String bookingId) {
        return ApiResponse.success("Booking agreed", service.agree(hashId, bookingId));
    }

    @PostMapping("/developments/{hashId}/bookings/{bookingId}/complete")
    @PreAuthorize("hasAuthority('BOOKINGS_MANAGE')")
    @RequestAction("COMPLETE_BOOKING")
    public ApiResponse<BookingResponse> complete(@PathVariable String hashId,
                                                  @PathVariable String bookingId) {
        return ApiResponse.success("Booking completed", service.complete(hashId, bookingId));
    }

    @PostMapping("/developments/{hashId}/bookings/{bookingId}/cancel")
    @PreAuthorize("hasAuthority('BOOKINGS_MANAGE')")
    @RequestAction("CANCEL_BOOKING")
    public ApiResponse<BookingResponse> cancel(@PathVariable String hashId,
                                                @PathVariable String bookingId,
                                                @Valid @RequestBody CloseBookingRequest request) {
        return ApiResponse.success("Booking cancelled", service.cancel(hashId, bookingId, request));
    }

    @PostMapping("/developments/{hashId}/bookings/{bookingId}/reschedule")
    @PreAuthorize("hasAuthority('BOOKINGS_MANAGE')")
    @RequestAction("RESCHEDULE_BOOKING")
    public ApiResponse<List<InstalmentResponse>> reschedule(
            @PathVariable String hashId,
            @PathVariable String bookingId,
            @Valid @RequestBody RescheduleRequest request) {
        return ApiResponse.success("Schedule revised", service.reschedule(hashId, bookingId, request));
    }
}
