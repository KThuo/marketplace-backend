package com.hodi.logging;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Skip the auto-applied {@link RequestLoggingAspect} for a controller method. Use sparingly —
 * for chatty health/ping endpoints or for endpoints whose payloads would dwarf the log file.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SkipRequestLog {
}
