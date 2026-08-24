package com.hodi.common;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Time-based, monotonic, 12-character reference generator.
 *
 * <p>Layout: {@code <PREFIX><base34(time)>} where the base-34 encoding uses an
 * unambiguous alphabet (no I, no O) and is left-padded to fit the remaining
 * {@code 12 - prefix.length()} positions. The seed value is
 * {@code System.currentTimeMillis()} but advanced via an {@link AtomicLong}
 * counter so concurrent callers always see a strictly-increasing value — even
 * within the same millisecond — guaranteeing uniqueness without a DB round-trip.
 *
 * <p>Originals can be reversed back to their creation time via
 * {@link #reverse(String, int)} for forensic decoding.
 */
public final class RefGenerator {

    private static final char[] DIGITS = {
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K',
            'L', 'M', 'N', 'P', 'Q', 'R', 'S', 'T', 'U', 'V',
            'W', 'X', 'Y', 'Z',
            '1', '2', '3', '4', '5', '6', '7', '8', '9', '0'
    };
    private static final int RADIX = DIGITS.length;
    private static final int RRN_LENGTH = 12;

    private final AtomicLong counter = new AtomicLong(System.currentTimeMillis());

    private RefGenerator() {}

    private static class Holder {
        private static final RefGenerator INSTANCE = new RefGenerator();
    }

    public static RefGenerator getInstance() {
        return Holder.INSTANCE;
    }

    public String generate(String prefix) {
        validatePrefix(prefix);

        long now = System.currentTimeMillis();
        long value = counter.updateAndGet(current -> Math.max(current + 1, now));

        int suffixLen = RRN_LENGTH - prefix.length();
        String encoded = toBase34(value);
        if (encoded.length() < suffixLen) {
            encoded = "A".repeat(suffixLen - encoded.length()) + encoded;
        } else if (encoded.length() > suffixLen) {
            encoded = encoded.substring(encoded.length() - suffixLen);
        }
        return prefix.toUpperCase() + encoded;
    }

    public static long reverse(String ref, int prefixLength) {
        if (ref == null || ref.length() != RRN_LENGTH) {
            throw new IllegalArgumentException("ref must be a 12-character generated reference");
        }
        if (prefixLength < 2 || prefixLength > 3) {
            throw new IllegalArgumentException("prefixLength must be 2 or 3");
        }
        return decodeBase34(ref.substring(prefixLength));
    }

    private static void validatePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be null or empty");
        }
        if (prefix.length() < 2 || prefix.length() > 3) {
            throw new IllegalArgumentException("prefix must be 2 or 3 characters: " + prefix);
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (!Character.isLetterOrDigit(prefix.charAt(i))) {
                throw new IllegalArgumentException("prefix must be alphanumeric: " + prefix);
            }
        }
    }

    private static String toBase34(long value) {
        if (value == 0) return "A";
        StringBuilder sb = new StringBuilder();
        long v = value;
        while (v > 0) {
            sb.append(DIGITS[(int) (v % RADIX)]);
            v /= RADIX;
        }
        return sb.reverse().toString();
    }

    private static long decodeBase34(String encoded) {
        long result = 0;
        int position = encoded.length();
        for (char ch : encoded.toCharArray()) {
            int idx = -1;
            for (int i = 0; i < DIGITS.length; i++) {
                if (DIGITS[i] == ch) { idx = i; break; }
            }
            if (idx < 0) throw new IllegalArgumentException("invalid base-34 character: " + ch);
            result += idx * BigDecimal.valueOf(RADIX).pow(--position).longValue();
        }
        return result;
    }
}
