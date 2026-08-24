package com.hodi.logging;

import com.hodi.common.AppConstant;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.MDC;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Auto-wraps every {@code @RestController} endpoint with one log surface:
 * <ul>
 *   <li><b>CUD methods</b> ({@code @PostMapping}, {@code @PutMapping}, {@code @PatchMapping},
 *       {@code @DeleteMapping}) get three lines per call — request payload at entry, response
 *       at exit (with duration), error on throw.</li>
 *   <li><b>Read methods</b> ({@code @GetMapping}) stay silent on success; emit an error-only
 *       line when an exception bubbles. {@link AccessDeniedException} / {@link AuthenticationException}
 *       are prefixed with {@code "ACCESS DENIED : "} so breaches stand out in a grep.</li>
 * </ul>
 *
 * <p>Action label resolution: {@link RequestAction} on the method, falling back to
 * {@code <UPPER_METHOD> <UPPER_CONTROLLER>} derived from the names. Trace id comes from MDC
 * (populated by {@link ActionIdFilter}); the aspect synthesizes a one-off via
 * {@link TraceIdGenerator#next()} if the filter didn't run (defensive — shouldn't happen for
 * real HTTP traffic).
 *
 * <p>Payload sanitization runs through {@link PayloadSanitizer}; entry / exit lines are
 * independently truncated at {@link #MAX_PAYLOAD_BYTES} so one chatty endpoint can't flood
 * the log file. Exceptions are rethrown unchanged after logging so the
 * {@code @ControllerAdvice} pipeline still produces the HTTP response.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class RequestLoggingAspect {

    private static final int MAX_PAYLOAD_BYTES = 4096;

    private final PayloadSanitizer sanitizer;

    @Pointcut("within(@org.springframework.web.bind.annotation.RestController *)")
    public void inRestController() {}

    @Pointcut("@annotation(org.springframework.web.bind.annotation.PostMapping) "
            + "|| @annotation(org.springframework.web.bind.annotation.PutMapping) "
            + "|| @annotation(org.springframework.web.bind.annotation.PatchMapping) "
            + "|| @annotation(org.springframework.web.bind.annotation.DeleteMapping)")
    public void cudMapping() {}

    @Pointcut("@annotation(org.springframework.web.bind.annotation.GetMapping)")
    public void readMapping() {}

    @Pointcut("@annotation(com.hodi.logging.SkipRequestLog)")
    public void skip() {}

    @Around("inRestController() && cudMapping() && !skip()")
    public Object aroundCud(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        String base = resolveActionBase(pjp, method);
        String traceId = currentTraceId();
        long t0 = System.currentTimeMillis();

        HodiLogger.info(serializeArgs(pjp.getArgs()), base + " REQUEST", traceId);

        try {
            Object result = pjp.proceed();
            long dur = System.currentTimeMillis() - t0;
            HodiLogger.info(serializeResult(result) + " (" + dur + "ms)",
                    base + " RESPONSE", traceId);
            return result;
        } catch (Throwable ex) {
            long dur = System.currentTimeMillis() - t0;
            String label = base + " FAILED";
            if (isAccessBreach(ex)) label = "ACCESS DENIED : " + label;
            HodiLogger.error(
                    ex.getClass().getSimpleName() + " — " + safeMessage(ex) + " (" + dur + "ms)",
                    label, traceId);
            throw ex;
        }
    }

    @Around("inRestController() && readMapping() && !skip()")
    public Object aroundRead(ProceedingJoinPoint pjp) throws Throwable {
        try {
            return pjp.proceed();
        } catch (Throwable ex) {
            Method method = ((MethodSignature) pjp.getSignature()).getMethod();
            String base = resolveActionBase(pjp, method);
            String traceId = currentTraceId();
            String label = base + " FAILED";
            if (isAccessBreach(ex)) label = "ACCESS DENIED : " + label;
            HodiLogger.error(
                    ex.getClass().getSimpleName() + " — " + safeMessage(ex),
                    label, traceId);
            throw ex;
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private String resolveActionBase(ProceedingJoinPoint pjp, Method method) {
        RequestAction override = method.getAnnotation(RequestAction.class);
        if (override != null && !override.value().isBlank()) return override.value().trim();
        String verb = upperSpaced(method.getName());
        String entity = entityFromControllerName(pjp.getSignature().getDeclaringType().getSimpleName());
        return entity.isEmpty() ? verb : verb + " " + entity;
    }

    private String entityFromControllerName(String simpleName) {
        String trimmed = simpleName.endsWith("Controller")
                ? simpleName.substring(0, simpleName.length() - "Controller".length())
                : simpleName;
        return upperSpaced(trimmed);
    }

    /** camelCase / PascalCase → UPPER-SPACED. */
    private String upperSpaced(String name) {
        if (name == null || name.isBlank()) return "";
        return name.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toUpperCase();
    }

    private String currentTraceId() {
        String id = MDC.get(AppConstant.MDC_ACTION_ID);
        return (id == null || id.isBlank()) ? TraceIdGenerator.next() : id;
    }

    private String serializeArgs(Object[] args) {
        if (args == null || args.length == 0) return "[]";
        List<Object> kept = new ArrayList<>(args.length);
        for (Object a : args) {
            if (a == null) continue;
            if (a instanceof HttpServletRequest) continue;
            if (a instanceof HttpServletResponse) continue;
            if (a instanceof Authentication) continue;
            if (a instanceof Pageable) continue;
            kept.add(a);
        }
        if (kept.isEmpty()) return "[]";
        if (kept.size() == 1) return truncate(safeSanitize(kept.get(0)));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < kept.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(safeSanitize(kept.get(i)));
        }
        sb.append(']');
        return truncate(sb.toString());
    }

    private String serializeResult(Object result) {
        Object body = result;
        if (result instanceof ResponseEntity<?> re) body = re.getBody();
        if (body == null) return "null";
        return truncate(safeSanitize(body));
    }

    private String safeSanitize(Object payload) {
        try {
            return sanitizer.sanitize(payload);
        } catch (Throwable ignore) {
            return "<unserializable>";
        }
    }

    private String truncate(String s) {
        if (s == null) return "null";
        if (s.length() <= MAX_PAYLOAD_BYTES) return s;
        return s.substring(0, MAX_PAYLOAD_BYTES) + "…";
    }

    private boolean isAccessBreach(Throwable ex) {
        return ex instanceof AccessDeniedException || ex instanceof AuthenticationException;
    }

    private String safeMessage(Throwable ex) {
        String msg = ex.getMessage();
        if (msg == null) return "(no message)";
        if (msg.length() > 500) return msg.substring(0, 500) + "…";
        return msg;
    }
}
