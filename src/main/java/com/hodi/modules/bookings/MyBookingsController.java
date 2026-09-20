package com.hodi.modules.bookings;

import com.hodi.common.ApiResponse;
import com.hodi.modules.bookings.BookingDtos.BookingResponse;
import com.hodi.modules.bookings.BookingDtos.InstalmentResponse;
import com.hodi.modules.payments.PaymentDtos.PaymentResponse;
import com.hodi.modules.payments.PaymentIntentService;
import com.hodi.modules.payments.PaymentIntentService.IntentResponse;
import com.hodi.modules.payments.PaymentQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * A buyer's own bookings: what they owe, what they have paid, and what they have asked to pay.
 *
 * <p>Under {@code /api/v1/me} with the rest of a person's own things, and scoped the same way — by the
 * signed-in identity inside the service, with no endpoint here that takes anybody else's id. A booking is
 * theirs when it carries their user id, which is what the sales office links when the buyer has an account.
 *
 * <p>Paying happens through {@code /api/v1/payments/intents/stk} and {@code /api/v1/payments/slip}, which
 * already know a buyer from staff; what is offered comes from {@code /api/v1/payment-types/offered}.
 */
@RestController
@RequestMapping("/api/v1/me/bookings")
@RequiredArgsConstructor
public class MyBookingsController {

    private final BookingService bookings;
    private final PaymentQueryService payments;
    private final PaymentIntentService intents;

    @GetMapping
    public ApiResponse<List<BookingResponse>> mine() {
        return ApiResponse.success(bookings.mine());
    }

    @GetMapping("/{hashId}")
    public ApiResponse<BookingResponse> one(@PathVariable String hashId) {
        return ApiResponse.success(bookings.mine(hashId));
    }

    @GetMapping("/{hashId}/schedule")
    public ApiResponse<List<InstalmentResponse>> schedule(@PathVariable String hashId) {
        return ApiResponse.success(bookings.mySchedule(hashId));
    }

    /** Receipts: the payments that succeeded, voided ones marked. */
    @GetMapping("/{hashId}/payments")
    public ApiResponse<List<PaymentResponse>> payments(@PathVariable String hashId) {
        UnitBooking booking = bookings.requireMine(hashId);
        return ApiResponse.success(payments.forBooking(booking.getId()));
    }

    /** What they have asked to pay and where each ask stands, so a prompt that outlived the screen is not lost. */
    @GetMapping("/{hashId}/intents")
    public ApiResponse<List<IntentResponse>> intents(@PathVariable String hashId) {
        return ApiResponse.success(intents.forBooking(hashId));
    }
}
