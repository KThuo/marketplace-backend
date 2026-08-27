package com.hodi.common.util;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates short, human-readable RRNs that fit on a printed receipt. Format:
 * <pre>{prefix(2)}{yyMMdd(6)}{counter(2)}{random(2)} = 12 chars total</pre>
 * Example: {@code BP260518K3X9}.
 *
 * <p>The 2-char counter slot rolls every 1024 generations per VM and is mixed
 * with a 2-char random tail from an ambiguity-free alphabet so collisions are
 * statistically negligible at our volumes. Callers persisting RRNs to a UNIQUE
 * column should retry on the rare clash (which is what the existing
 * {@code nextRrn} helpers do).
 */
public final class RrnGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyMMdd");
    private static final String ALPHA = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 32 chars, no 0/O/1/I
    private static final SecureRandom RNG = new SecureRandom();
    private static final AtomicInteger COUNTER = new AtomicInteger(RNG.nextInt(1024));

    private RrnGenerator() {}

    /** Generate a 12-char RRN. Prefix is normalised to upper-case and trimmed/padded to 2 chars. */
    public static String generate(String prefix) {
        String p = normalisePrefix(prefix);
        String date = LocalDate.now().format(DATE_FMT);
        int seq = COUNTER.updateAndGet(prev -> (prev + 1) & 0x3FF); // 0..1023
        String counter = base32(seq, 2);
        String tail = randomTail(2);
        return p + date + counter + tail;
    }

    /** Convenience for payment RRNs ({@code BP…}). */
    public static String payment() {
        return generate("BP");
    }

    /** Convenience for invoice numbers ({@code BI…}). */
    public static String invoice() {
        return generate("BI");
    }

    /**
     * A short code a person reads off a letter and types into a phone — the reference a buyer quotes when
     * paying for a unit.
     *
     * <p>No prefix, no date, no counter: those exist above so an RRN is traceable and roughly ordered, and
     * both cost characters that a payer has to type correctly. Four characters of the ambiguity-free
     * alphabet is 32^4 = 1,048,576 codes, and the caller persists it to a UNIQUE column and retries on a
     * clash exactly as the RRN callers do.
     *
     * <p>Deliberately carries no checksum. Four random characters means a single mistyped letter produces
     * another well-formed code, which is why a payment is never matched on this alone without the amount or
     * the payer's number agreeing — see the column comment on {@code development_units.pay_reference}.
     */
    public static String payCode() {
        return randomTail(4);
    }

    private static String normalisePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) return "BX";
        String p = prefix.trim().toUpperCase();
        if (p.length() >= 2) return p.substring(0, 2);
        return p + "X";
    }

    private static String base32(int value, int width) {
        StringBuilder sb = new StringBuilder(width);
        int v = value & 0x7FFFFFFF;
        for (int i = 0; i < width; i++) {
            sb.insert(0, ALPHA.charAt(v % ALPHA.length()));
            v /= ALPHA.length();
        }
        return sb.toString();
    }

    private static String randomTail(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALPHA.charAt(RNG.nextInt(ALPHA.length())));
        return sb.toString();
    }
}
