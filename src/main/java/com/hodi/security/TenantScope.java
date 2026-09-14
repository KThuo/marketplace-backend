package com.hodi.security;

import com.hodi.common.exception.HodiException;
import com.hodi.security.principal.AuthContext;
import jakarta.persistence.criteria.Path;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Set;

/**
 * Access axis (3) of plan section 4: permissions decide which <em>actions</em> a caller may take, this
 * decides which <em>rows</em> they may see.
 *
 * <p><strong>This is the only thing standing between two organisations' data.</strong> Axis could afford a
 * softer version of this class because a tenant's rows lived in their own schema and the connection could
 * not reach another one; here every organisation shares one schema, so a query that forgets to come through
 * here reads everybody. That is why it is one static choke point used at the repository/specification layer
 * rather than a filter each service composes for itself — enforcing it per controller is how it ends up
 * half-applied, and one forgotten endpoint is a cross-organisation leak rather than an inconvenience.
 *
 * <h2>The three answers</h2>
 *
 * <p>{@link #visibleIds()} returns {@code null} for a caller under no restriction, which callers must read
 * as "no constraint" rather than "no organisations". An <em>empty</em> set means the opposite — a caller who
 * may see nothing at all, which is the correct and common state for the bank with no approved partnership
 * yet.
 *
 * <table>
 *   <caption>Who resolves to what</caption>
 *   <tr><th>Caller</th><th>{@code visibleIds()}</th></tr>
 *   <tr><td>Platform staff</td><td>{@code null} — unrestricted</td></tr>
 *   <tr><td>Seller staff</td><td>their own tenant, and only ever that</td></tr>
 *   <tr><td>The bank's staff</td><td>the sellers their institution has an <em>active</em> partnership with</td></tr>
 *   <tr><td>Buyer</td><td>empty — a buyer reads their own rows by identity, never by organisation</td></tr>
 * </table>
 *
 * <p>The set is resolved once per session into {@link com.hodi.security.principal.UserPrincipal}, and the
 * principal is rebuilt from the database on every request — so a partnership revoked mid-session stops
 * granting access on the next call rather than at token expiry.
 */
public final class TenantScope {

    private TenantScope() {}

    /** True when the caller may see every organisation's rows. Platform staff, and nobody else. */
    public static boolean unrestricted() {
        return AuthContext.current().map(p -> p.isUnrestrictedTenants()).orElse(false);
    }

    /**
     * @return {@code null} when unrestricted, otherwise the exact set of organisation ids in view
     */
    public static Set<Long> visibleIds() {
        if (unrestricted()) return null;
        return Set.copyOf(AuthContext.visibleTenantIds());
    }

    /**
     * True when a restricted caller has no organisations in view at all.
     *
     * <p>Worth a distinct name because the UI must say so: the bank admin whose last partnership was revoked
     * should be told that, not shown empty tables that read as missing data.
     */
    public static boolean isStranded() {
        return !unrestricted() && AuthContext.visibleTenantIds().isEmpty();
    }

    /**
     * Restricts a query to the caller's organisations.
     *
     * @param field the entity attribute holding the tenant id — {@code "id"} on Tenant itself,
     *              {@code "tenantId"} on anything that references one
     * @return {@code null} when unrestricted, so it composes with {@code SearchSpecs.allOf}
     */
    public static <T> Specification<T> restrict(String field) {
        Set<Long> allowed = visibleIds();
        if (allowed == null) return null;
        if (allowed.isEmpty()) {
            // Matches nothing, deliberately. The alternative — returning no predicate — would silently
            // show a caller with no partnerships every organisation on the platform.
            return (root, query, cb) -> cb.disjunction();
        }
        return (root, query, cb) -> {
            Path<Object> path = root.get(field);
            return path.in(allowed);
        };
    }

    /**
     * Guards a write against an organisation the caller may not touch.
     *
     * @throws HodiException when the organisation is outside the caller's view
     */
    public static void assertAllowed(Long tenantId) {
        Set<Long> allowed = visibleIds();
        if (allowed == null) return;
        if (tenantId == null || !allowed.contains(tenantId)) {
            // Deliberately not "organisation 42 is not yours", which would confirm that 42 exists.
            throw new HodiException("You do not have access to that organisation", HttpStatus.FORBIDDEN);
        }
    }

    /**
     * The organisation a write should be attributed to, for a caller who has exactly one.
     *
     * <p>Seller staff never choose their own tenant on a create — it is derived, because a form field that
     * accepts it is a form field somebody can change. Platform staff must pass one explicitly, and get
     * {@code null} here rather than a guess.
     */
    public static Long ownTenantId() {
        return AuthContext.current().map(p -> p.getTenantId()).orElse(null);
    }

    /**
     * SQL fragment for the raw-JDBC paths that cannot use a Specification — reporting and dashboard
     * aggregates. Returns an always-true or always-false literal rather than an empty string, so a caller
     * can splice it into a WHERE clause without branching.
     *
     * <p>The ids are the caller's own resolved scope, never request input, and they are longs, so there is
     * nothing to escape.
     */
    public static String sqlPredicate(String column) {
        Set<Long> allowed = visibleIds();
        if (allowed == null) return "TRUE";
        if (allowed.isEmpty()) return "FALSE";
        List<String> ids = allowed.stream().map(String::valueOf).toList();
        return column + " IN (" + String.join(",", ids) + ")";
    }
}
