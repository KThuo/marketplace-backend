package com.hodi.modules.bookings;

import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Hands out the short code a buyer quotes when paying for a booking.
 *
 * <h2>Why this is a class and not a call to {@link RrnGenerator#payCode()}</h2>
 *
 * <p>Four characters of the 32-letter alphabet is 1,048,576 codes, and the birthday problem arrives far
 * earlier than the size of that number suggests: a thousand codes carries roughly a 39% chance that two of
 * them collide, and past twelve hundred a collision is more likely than not. So a clash is an ordinary event
 * to be retried, not an error to be reported. The unique index is the guarantee; this class is what stops
 * the guarantee from being the user's problem.
 *
 * <h2>Two layers, because a pre-check alone races</h2>
 *
 * <p>{@link #next()} asks the database whether a candidate is taken, which handles the common case cheaply.
 * That is a check-then-insert and two concurrent bookings can still pass it together — so the caller must
 * also survive the index rejecting the insert. Both layers are needed and neither is sufficient.
 *
 * <h2>Why it lives on the booking</h2>
 *
 * <p>The code used to be the unit's, allocated when the unit was created. Money is received against a
 * booking, and a unit is booked, cancelled and booked again; a code that named the unit landed the first
 * buyer's late payment on the second buyer's booking. A code per booking, unique across every booking ever,
 * lands it on the cancelled booking, where the matcher can say so.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayCodeAllocator {

    /**
     * Attempts before giving up on one code.
     *
     * <p>Generous, because the cost of trying again is a random draw and an indexed lookup. At 25 consecutive
     * collisions the code space is effectively full, and the honest response is to stop and say so rather than
     * to loop forever handing out nothing.
     */
    private static final int ATTEMPTS = 25;

    private final UnitBookingRepository bookings;

    /** One code, not yet held by any booking. */
    public String next() {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String candidate = RrnGenerator.payCode();
            if (!bookings.existsByPayReference(candidate)) {
                if (attempt > 3) {
                    // Worth a line: a rising number of attempts is the first sign the space is filling, and
                    // the decision it points at — a checksum character, or five characters — is a product
                    // decision somebody has to make before it becomes an outage.
                    log.info("Pay code allocated after {} attempts", attempt);
                }
                return candidate;
            }
        }
        throw new HodiException(
                "Could not allocate a payment code for this booking. The short-code space is exhausted.",
                HttpStatus.CONFLICT);
    }
}
