package com.hodi.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Marks every request to the public marketplace as {@link PublicMarketplace} work, for its whole length.
 *
 * <h2>Why the whole request and not each service</h2>
 *
 * <p>The controller decodes ids out of the query string <em>before</em> any service is entered, and encodes
 * some on the way out. Anything decoded outside the marker would be decoded with the caller's own salt —
 * which is precisely the bug the marker exists to close, so covering only the service bodies would leave the
 * edges of the request open.
 *
 * <p>Scoped by path rather than by "is anyone signed in", because a signed-in buyer browsing listings is
 * still a member of the public. That is the case that breaks under the other reading.
 */
@Component
public class PublicMarketplaceFilter extends OncePerRequestFilter {

    private static final String PUBLIC_PREFIX = "/api/v1/public/";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith(PUBLIC_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // Rethrown rather than swallowed: the checked signatures are the only reason this is not a lambda.
        try {
            PublicMarketplace.run(() -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException e) {
                    throw new FilterFailure(e);
                }
            });
        } catch (FilterFailure e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw (ServletException) e.getCause();
        }
    }

    /** Carries a checked filter failure back out through the marker's functional interface. */
    private static final class FilterFailure extends RuntimeException {
        FilterFailure(Exception cause) {
            super(cause);
        }
    }
}
