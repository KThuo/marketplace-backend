package com.hodi.tenant;

import com.hodi.common.AppConstant;
import com.hodi.security.principal.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Binds {@link TenantContext} from the authenticated principal, for the length of one request.
 *
 * <p>Registered <strong>after</strong> the JWT filter, because it reads the principal that filter
 * establishes. That ordering is also why there is no host lookup here and no {@code Host}-header parsing
 * anywhere in this application: Hodi is one marketplace on one host, so a request's organisation is a
 * property of who is asking, not of where they asked (plan section 1).
 *
 * <p>Only seller staff bind an organisation. Platform staff, the bank's staff and buyers all leave the context
 * empty, each for its own reason:
 *
 * <ul>
 *   <li>platform staff have no organisation — their configuration is the global layer by definition;
 *   <li>the bank's staff belong to an institution rather than a tenant, and read <em>several</em> sellers' rows
 *       through {@code TenantScope}. Binding one of them here would be arbitrary, and worse, would make a
 *       bank's session silently adopt that seller's configuration overrides;
 *   <li>buyers are identity-scoped and belong to nobody.
 * </ul>
 *
 * <p>The {@code finally} block is load-bearing. Servlet threads are pooled, so a context left dirty is one
 * organisation's settings applied to the next request that happens to land on the same thread.
 */
@Component
public class TenantBindingFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        UserPrincipal principal = currentPrincipal();
        try {
            if (principal != null && principal.getTenantId() != null) {
                TenantContext.set(principal.getTenantId(), principal.getTenantName());
                MDC.put(AppConstant.MDC_TENANT, String.valueOf(principal.getTenantId()));
            } else if (principal != null && principal.getInstitutionId() != null) {
                // Not a tenant, but worth having in the log line: "which bank was this" is the first
                // question asked about a cross-organisation read.
                MDC.put(AppConstant.MDC_TENANT, "inst" + principal.getInstitutionId());
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
            MDC.remove(AppConstant.MDC_TENANT);
        }
    }

    private UserPrincipal currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) return null;
        return principal;
    }
}
