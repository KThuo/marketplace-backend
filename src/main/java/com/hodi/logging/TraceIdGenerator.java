package com.hodi.logging;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Static trace-id generator. Produces a per-request identifier in the format
 * {@code HDI + ddHHmm + 12-digit random}, e.g. {@code HDI051123987654321098}:
 *  - "HDI" prefix marks the source (Hodi Core);
 *  - {@code ddHHmm} is the wall-clock day-of-month / hour / minute (UTC of the JVM);
 *  - a 12-digit {@link SecureRandom} suffix gives ~10^12 keyspace per minute.
 *
 * <p>Used by {@link ActionIdFilter} to populate the {@code MDC_ACTION_ID} MDC slot and the
 * {@code X-Action-Id} response header on every inbound HTTP request. Mirrors the sibling services'
 * generators so trace IDs read the same way across the estate.
 */
public final class TraceIdGenerator {

    private static final DateTimeFormatter DDHHMM = DateTimeFormatter.ofPattern("ddHHmm");
    private static final SecureRandom RANDOM = new SecureRandom();

    private TraceIdGenerator() {}

    public static String next() {
        return "HDI" + LocalDateTime.now().format(DDHHMM) + random12();
    }

    private static String random12() {
        long a = RANDOM.nextInt(1_000_000);
        long b = RANDOM.nextInt(1_000_000);
        return String.format("%06d%06d", a, b);
    }
}
