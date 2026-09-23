package com.hodi.modules.payments;

/**
 * Money landed on a booking.
 *
 * <p>An event rather than a call from the payment writer into the booking service, because the booking
 * service already reads payments and a call back the other way would be a cycle. Published inside the
 * transaction that wrote the payment and handled in it, so whatever follows from the money — a reserved
 * booking becoming agreed — commits with the money or not at all.
 */
public record PaymentReceived(Long bookingId, Long paymentId, String by) {}
