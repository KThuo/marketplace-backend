package com.hodi.logging;

import com.hodi.common.AppConstant;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Explicit, call-site activity logger. Services invoke {@link #start}, {@link #step},
 * {@link #rule}, {@link #complete}, {@link #failure} around significant business events.
 * Lines are tagged with the request's MDC actionId so they correlate with
 * {@link RequestLoggingAspect}'s entry/exit lines and with {@code audit_log} rows.
 *
 * <p>Unlike {@link RequestLoggingAspect}, which auto-wraps every controller call, this bean
 * is opted-in by service code that wants finer-grained narration of a multi-step flow.
 */
@Component
@RequiredArgsConstructor
public class ActivityLogger {

    private final PayloadSanitizer sanitizer;

    /** Capture-and-cache the principal username into MDC once per request. Lets the
     *  Logback pattern emit {@code username} without re-reading the SecurityContext on every
     *  log line. */
    public void captureUsername() {
        if (MDC.get(AppConstant.MDC_USERNAME) != null) return;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = (auth != null && auth.getName() != null)
                ? auth.getName()
                : AppConstant.USERNAME_SYSTEM;
        MDC.put(AppConstant.MDC_USERNAME, username);
    }

    public String currentActionId() {
        return MDC.get(AppConstant.MDC_ACTION_ID);
    }

    public void start(String operation, Object request) {
        captureUsername();
        HodiLogger.info("BEGIN " + operation + " request=" + sanitizer.sanitize(request),
                operation, currentActionId());
    }

    public void complete(String operation, Object response) {
        HodiLogger.info("END " + operation + " response=" + sanitizer.sanitize(response),
                operation, currentActionId());
    }

    public void complete(String operation) {
        HodiLogger.info("END " + operation, operation, currentActionId());
    }

    public void step(String operation, String stepName, Object payload) {
        HodiLogger.debug("STEP " + operation + " :: " + stepName + " payload=" + sanitizer.sanitize(payload));
    }

    public void rule(String operation, String ruleName, boolean passed, String detail) {
        String body = "RULE " + operation + " :: " + ruleName + " -> " + (passed ? "PASS" : "FAIL")
                + (detail == null || detail.isBlank() ? "" : " " + detail);
        if (passed) {
            HodiLogger.info(body, operation, currentActionId());
        } else {
            HodiLogger.warn(body, operation, currentActionId());
        }
    }

    public void failure(String operation, String message, Throwable cause) {
        HodiLogger.error(
                "FAILED " + operation + (message == null || message.isBlank() ? "" : " — " + message),
                operation,
                currentActionId());
        if (cause != null) HodiLogger.log("[" + operation + "]", cause);
    }
}
