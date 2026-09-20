package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.modules.bookings.UnitBooking;
import com.hodi.modules.bookings.UnitBookingRepository;
import com.hodi.modules.developments.DevelopmentUnitRepository;
import com.hodi.modules.properties.Property;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Whose money this is, from what the payer quoted.
 *
 * <h2>One resolver, four callers</h2>
 *
 * <p>A notification, a biller's validation, a biller's advice and the unused queue's search all answer the
 * same question — "which booking did the payer mean?" — and if each answered it with its own code they would
 * drift, and the same reference would mean different things depending on how the money arrived. So the rule
 * lives here once.
 *
 * <h2>The three things a payer may quote</h2>
 *
 * <ol>
 *   <li><b>The booking's reference</b> — what the sales office puts on the letter and the bank statement a
 *       clerk uploads. Unique, and it names the booking directly, so a match places the money on its own.
 *       A booking that is no longer live is named in the reason rather than credited.</li>
 *   <li><b>The listing's reference</b> — twelve characters as generated, unique across every listing ever.
 *       Long enough that a mistyped letter produces nothing rather than somebody else's listing, so a match
 *       places the money on its own.</li>
 *   <li><b>The unit's four-character pay code</b> — what goes on the letter and gets read over the phone.
 *       Four characters from a 32-letter alphabet carry no redundancy: one wrong letter is another
 *       well-formed code, and at five thousand units the chance it is a live one is about one in two
 *       hundred. A match by code therefore {@linkplain Resolution#needsCorroboration() needs something else
 *       to agree} before a balance moves.</li>
 * </ol>
 *
 * <p>The listing reference is tried first, because taking the last four characters of a twelve-character
 * reference would be a code match against the wrong thing. People add spaces, prefixes and their own name to
 * whatever they type, so both are matched on the alphanumerics alone, uppercased.
 *
 * <p>A payment we started ourselves — an intent — is not resolved here: its reference already names the
 * booking, and the caller that knows about intents checks them before asking this.
 */
@Component
@RequiredArgsConstructor
public class PayeeResolver {

    private final DevelopmentUnitRepository units;
    private final UnitBookingRepository bookings;

    /** How the reference was understood. */
    public enum Via { BOOKING_REFERENCE, LISTING_REFERENCE, PAY_CODE }

    /**
     * The answer, with the booking when there is one and the reason in words when there is not.
     *
     * @param booking the live booking the money belongs to, or null
     * @param home    the listing the reference named, or null when it named nothing
     * @param via     which of the two references matched, or null when neither did
     * @param reason  why there is no booking, written for the person who works the queue; null when found
     */
    public record Resolution(UnitBooking booking, Property home, Via via, String reason) {

        public boolean found() {
            return booking != null;
        }

        /** A four-character code has no redundancy; a listing reference does. */
        public boolean needsCorroboration() {
            return via == Via.PAY_CODE;
        }

        static Resolution none(String reason) {
            return new Resolution(null, null, null, reason);
        }
    }

    /** Resolves what the payer quoted to the live booking it names, or says why it cannot. */
    public Resolution resolve(String quoted) {
        String cleaned = clean(quoted);
        if (cleaned.isEmpty()) {
            return Resolution.none("The payer quoted no reference, so there is nothing to match on.");
        }

        Optional<UnitBooking> byBooking = bookingNamedIn(quoted, cleaned);
        if (byBooking.isPresent()) {
            UnitBooking booking = byBooking.get();
            Property home = units.findById(booking.getPropertyId()).orElse(null);
            if (AppConstant.BOOKING_RESERVED.equals(booking.getState())
                    || AppConstant.BOOKING_AGREED.equals(booking.getState())) {
                return new Resolution(booking, home, Via.BOOKING_REFERENCE, null);
            }
            return new Resolution(null, home, Via.BOOKING_REFERENCE, "Booking " + booking.getReference()
                    + " is " + booking.getState().toLowerCase(Locale.ROOT) + ", so there is nothing to credit.");
        }

        Optional<Property> byReference = listingNamedIn(quoted, cleaned);
        if (byReference.isPresent()) {
            return onHome(byReference.get(), Via.LISTING_REFERENCE);
        }

        String code = payCode(cleaned);
        Optional<Property> byCode = units.findByPayReference(code)
                .filter(p -> p.getStatus() == null || p.getStatus() != AppConstant.STATUS_DELETED);
        if (byCode.isEmpty()) {
            return Resolution.none(cleaned.length() <= 4
                    ? "No unit has the code \"" + code + "\". The payer may have mistyped it."
                    : "No listing has the reference \"" + cleaned + "\" and no unit has the code \"" + code
                            + "\". The payer may have mistyped it.");
        }
        return onHome(byCode.get(), Via.PAY_CODE);
    }

    /**
     * The listing whose reference appears in what the payer typed.
     *
     * <p>The whole thing first, then each word of it: "ref UN260920HSBD" and "UN260920HSBD for Asha" both
     * name a listing, and gluing the payer's words onto the reference would find nothing. A word shorter
     * than a reference is not asked about — every reference is at least eight characters, and querying for
     * "REF" and "FOR" is wasted round trips on every notification.
     */
    private Optional<Property> listingNamedIn(String quoted, String cleaned) {
        Optional<Property> whole = listing(cleaned);
        if (whole.isPresent()) return whole;
        for (String word : quoted.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+")) {
            if (word.length() < 8 || word.equals(cleaned)) continue;
            Optional<Property> named = listing(word);
            if (named.isPresent()) return named;
        }
        return Optional.empty();
    }

    /** The booking whose reference appears in what the payer typed. Same search as for a listing. */
    private Optional<UnitBooking> bookingNamedIn(String quoted, String cleaned) {
        Optional<UnitBooking> whole = booking(cleaned);
        if (whole.isPresent()) return whole;
        for (String word : quoted.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+")) {
            if (word.length() < 8 || word.equals(cleaned)) continue;
            Optional<UnitBooking> named = booking(word);
            if (named.isPresent()) return named;
        }
        return Optional.empty();
    }

    private Optional<UnitBooking> booking(String reference) {
        return bookings.findByReference(reference)
                .filter(b -> b.getStatus() == null || b.getStatus() != AppConstant.STATUS_DELETED);
    }

    private Optional<Property> listing(String reference) {
        return units.findByReference(reference)
                .filter(p -> p.getStatus() == null || p.getStatus() != AppConstant.STATUS_DELETED);
    }

    private Resolution onHome(Property home, Via via) {
        Optional<UnitBooking> live = bookings.findLiveForUnit(home.getId());
        if (live.isEmpty()) {
            return new Resolution(null, home, via,
                    label(home) + " has no live booking, so there is nothing to credit.");
        }
        return new Resolution(live.get(), home, via, null);
    }

    /** What a person calls this listing: the unit's label on a development, the title on a house. */
    public static String label(Property home) {
        if (home.getUnitLabel() != null && !home.getUnitLabel().isBlank()) return "Unit " + home.getUnitLabel();
        return home.getTitle() == null ? "Listing " + home.getReference() : home.getTitle();
    }

    /**
     * The four-character code out of whatever the payer typed.
     *
     * <p>The last four alphanumerics, uppercased — which is the pattern the payment guide suggests, and it
     * survives "UNIT A7K2" and "a7k2" alike.
     */
    public static String payCode(String cleaned) {
        return cleaned.length() <= 4 ? cleaned : cleaned.substring(cleaned.length() - 4);
    }

    /** Alphanumerics only, uppercased. Spaces, dashes and a payer's own words fall away. */
    public static String clean(String quoted) {
        if (quoted == null) return "";
        return quoted.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }
}
