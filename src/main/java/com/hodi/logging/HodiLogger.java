package com.hodi.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Project-standard static logging facade for Hodi. Every class reaches for the same
 * entry point so log output, formatting, and dispatch are managed in one place.
 *
 * <p>Underneath, calls route through SLF4J ({@code LoggerFactory.getLogger("com.hodi")})
 * so {@code logback-spring.xml} continues to govern levels and appenders.
 */
public final class HodiLogger {

    public static final int LOG_TO_FILE = 0;
    public static final int LOG_TO_CONSOLE = 1;
    public static final int LOG_TO_BOTH = 2;
    public static final int DEFAULT_OPTION = LOG_TO_FILE;

    private static final Logger DEFAULT = LoggerFactory.getLogger("com.hodi");

    private HodiLogger() {}

    // ─── INFO ────────────────────────────────────────────────────────────────

    public static void info(String message) {
        infoTo(DEFAULT, message, DEFAULT_OPTION);
    }

    public static void info(String message, Object... args) {
        DEFAULT.info(message, args);
    }

    public static void info(Class<?> cls, String message, Object... args) {
        LoggerFactory.getLogger(cls).info(message, args);
    }

    /** Routes to file/console/both depending on {@code dest} ∈ {0,1,2}. Renamed from
     *  {@code info(String, int)} which shadowed the standard SLF4J args overload — calling
     *  {@code info("Seeded {} rows", 4)} previously routed to the destination switch instead
     *  of formatting the placeholder. */
    public static void infoTo(String message, int dest) {
        infoTo(DEFAULT, message, dest);
    }

    public static void info(String message, String action, String traceId) {
        DEFAULT.info("{} [{}] ({}) >>> {}", action, traceId, currentUsername(), message);
    }

    private static void infoTo(Logger log, String message, int dest) {
        switch (dest) {
            case LOG_TO_FILE -> log.info("{}{}{}", System.lineSeparator(), message, System.lineSeparator());
            case LOG_TO_CONSOLE -> System.out.println(message);
            case LOG_TO_BOTH -> {
                log.info("{}{}{}", System.lineSeparator(), message, System.lineSeparator());
                System.out.println(message);
            }
            default -> throw new IllegalArgumentException("Unsupported log destination: " + dest);
        }
    }

    // ─── WARN ────────────────────────────────────────────────────────────────

    public static void warn(String message) {
        DEFAULT.warn(message);
    }

    public static void warn(String message, Object... args) {
        DEFAULT.warn(message, args);
    }

    public static void warn(Class<?> cls, String message, Object... args) {
        LoggerFactory.getLogger(cls).warn(message, args);
    }

    public static void warn(String message, String action, String traceId) {
        DEFAULT.warn("{} [{}] ({}) >>> {}", action, traceId, currentUsername(), message);
    }

    // ─── ERROR ───────────────────────────────────────────────────────────────

    public static void error(String message) {
        DEFAULT.error(message);
    }

    public static void error(String message, Object... args) {
        DEFAULT.error(message, args);
    }

    public static void error(Class<?> cls, String message, Object... args) {
        LoggerFactory.getLogger(cls).error(message, args);
    }

    public static void error(String message, String action, String traceId) {
        DEFAULT.error("{} [{}] ({}) >>> {}", action, traceId, currentUsername(), message);
    }

    // ─── DEBUG ───────────────────────────────────────────────────────────────

    public static void debug(String message) {
        DEFAULT.debug(message);
    }

    public static void debug(String message, Object... args) {
        DEFAULT.debug(message, args);
    }

    public static void debug(Class<?> cls, String message, Object... args) {
        LoggerFactory.getLogger(cls).debug(message, args);
    }

    // ─── EXCEPTION ───────────────────────────────────────────────────────────

    public static void log(Throwable ex) {
        log("[ EXCEPTION ] ", ex);
    }

    public static void log(String header, Throwable ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        DEFAULT.error("{}{}", header, sw);
    }

    public static void log(Class<?> cls, String header, Throwable ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        LoggerFactory.getLogger(cls).error("{}{}", header, sw);
    }

    // ─── INTERNAL ────────────────────────────────────────────────────────────

    /** Canonical username read for trace-id log lines. Reads from Spring's SecurityContext
     *  at call-time so the printed username always reflects the principal active when the
     *  line was emitted. Falls back to "anonymous" for unauthenticated calls and to "system"
     *  when the context is empty (scheduler threads). */
    private static String currentUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null) return "system";
            String name = auth.getName();
            return (name == null || name.isBlank()) ? "anonymous" : name;
        } catch (Throwable ignore) {
            return "anonymous";
        }
    }
}
