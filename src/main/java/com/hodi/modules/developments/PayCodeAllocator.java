package com.hodi.modules.developments;

import com.hodi.common.exception.HodiException;
import com.hodi.common.util.RrnGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hands out the short code a buyer quotes when paying for a unit.
 *
 * <h2>Why this is a class and not a call to {@link RrnGenerator#payCode()}</h2>
 *
 * <p>Four characters of the 32-letter alphabet is 1,048,576 codes, and the birthday problem arrives far
 * earlier than the size of that number suggests: generating 170 codes carries roughly a 1.4% chance of a
 * duplicate, a thousand codes about 39%, and past twelve hundred a collision is more likely than not. A
 * two-hundred-unit development is therefore not a safe single pass, and this was not a calculation — the
 * integration test hit it on its first run while generating one project's inventory.
 *
 * <p>So a clash is an ordinary event to be retried, not an error to be reported. The unique index is the
 * guarantee; this class is what stops the guarantee from being the user's problem.
 *
 * <h2>Two layers, because a pre-check alone races</h2>
 *
 * <p>{@link #next()} asks the database whether a candidate is taken, which handles the common case cheaply.
 * That is a check-then-insert and two concurrent generators can still pass it together — so the caller must
 * also survive the index rejecting an insert. Both layers are needed and neither is sufficient: without the
 * pre-check a bulk generator would fail constantly, and without the index two sales offices could hand the
 * same code to two buyers.
 *
 * <p>{@link #nextBatch(int)} exists for the generator that creates two hundred units at once, and keeps the
 * codes it has already handed out in memory as well — the database cannot see rows that have not been
 * inserted yet, which is the collision the pre-check would otherwise miss inside a single batch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayCodeAllocator {

    /**
     * Attempts before giving up on one code.
     *
     * <p>Generous, because the cost of trying again is a random draw and an indexed lookup. Hitting this
     * ceiling does not mean bad luck — at 25 consecutive collisions the code space is effectively full, and
     * the honest response is to stop and say so rather than to loop forever handing out nothing.
     */
    private static final int ATTEMPTS = 25;

    private final DevelopmentUnitRepository units;

    /** One code, not yet taken by any unit. */
    public String next() {
        return next(Set.of());
    }

    /**
     * A batch of distinct codes, for the generator that creates a whole block of units at once.
     *
     * <p>Allocated up front rather than one per insert so the caller can build every row before touching the
     * database, and so the in-memory set below is the same set the whole batch is checked against.
     */
    public List<String> nextBatch(int count) {
        Set<String> taken = new HashSet<>(count * 2);
        for (int i = 0; i < count; i++) taken.add(next(taken));
        return List.copyOf(taken);
    }

    private String next(Set<String> alsoTaken) {
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            String candidate = RrnGenerator.payCode();
            if (alsoTaken.contains(candidate)) continue;
            if (!units.existsByPayReference(candidate)) {
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
                "Could not allocate a payment code for this unit. The short-code space is exhausted.",
                HttpStatus.CONFLICT);
    }
}
