package com.hodi.security.principal;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.UnauthorizedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

/**
 * Convenience reads of the current principal, so services do not each reach into
 * {@link SecurityContextHolder} and reimplement the null handling.
 */
public final class AuthContext {

    private AuthContext() {}

    public static Optional<UserPrincipal> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            return Optional.empty();
        }
        return Optional.of(principal);
    }

    public static UserPrincipal require() {
        return current().orElseThrow(() -> new UnauthorizedException("Not authenticated"));
    }

    public static Long userId() {
        return current().map(UserPrincipal::getUserId).orElse(null);
    }

    /**
     * The signed-in user's own id, required.
     *
     * <p>The entry point for every identity-scoped read. A buyer's own enquiries, saved properties and
     * applications are found by filtering on this — never by an id in the request, which is the shape that
     * turns "show me mine" into "show me anyone's" the first time somebody increments a number.
     */
    public static Long requireUserId() {
        return require().getUserId();
    }

    /** Username for audit columns; falls back to "system" for scheduled work. */
    public static String username() {
        return current().map(UserPrincipal::getUsername).orElse(AppConstant.USERNAME_SYSTEM);
    }

    public static Long tenantId() {
        return current().map(UserPrincipal::getTenantId).orElse(null);
    }

    public static Long institutionId() {
        return current().map(UserPrincipal::getInstitutionId).orElse(null);
    }

    public static String actorClass() {
        return current().map(UserPrincipal::getActorClass).orElse(null);
    }

    /**
     * The organisations the caller may read. Empty for an unrestricted caller — callers must check
     * {@code TenantScope.unrestricted()} first, which is why this is not the thing services call directly.
     */
    public static List<Long> visibleTenantIds() {
        return current().map(UserPrincipal::getVisibleTenantIds).orElse(List.of());
    }

    public static boolean hasAuthority(String code) {
        return current()
                .map(p -> p.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(code)))
                .orElse(false);
    }
}
