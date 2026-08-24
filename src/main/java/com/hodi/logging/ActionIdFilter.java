package com.hodi.logging;

import com.hodi.common.AppConstant;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * First filter in the chain. Generates a per-request trace id via {@link TraceIdGenerator},
 * places it in SLF4J MDC under {@link AppConstant#MDC_ACTION_ID}, exposes it as the
 * {@code X-Action-Id} response header for support correlation, and clears MDC in a
 * {@code finally} block so the thread is returned to the pool clean.
 *
 * <p>Every log line on the request thread inherits the actionId via the Logback pattern; audit
 * rows written by {@code AuditService} pull the same value into {@code audit_log.action_id}
 * so the trail and the log file are linked end-to-end.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ActionIdFilter extends OncePerRequestFilter {

    public static final String HEADER_ACTION_ID = "X-Action-Id";
    public static final String HEADER_FORWARDED_FOR = "X-Forwarded-For";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String actionId = TraceIdGenerator.next();
        try {
            MDC.put(AppConstant.MDC_ACTION_ID, actionId);
            MDC.put(AppConstant.MDC_REMOTE_IP, resolveRemoteIp(request));
            response.setHeader(HEADER_ACTION_ID, actionId);
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }

    private String resolveRemoteIp(HttpServletRequest request) {
        String forwarded = request.getHeader(HEADER_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return comma >= 0 ? forwarded.substring(0, comma).trim() : forwarded.trim();
        }
        return request.getRemoteAddr();
    }
}
