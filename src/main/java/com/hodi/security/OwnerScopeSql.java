package com.hodi.security;

import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which rows an aggregate may add up, as SQL, where the rows may be owned by a lender rather than a seller.
 *
 * <p>{@link TenantScope#sqlPredicate} answers for tables that belong to a tenant. Developments, bookings,
 * payments and the cost ledger do not: a lending institution may own them outright, a seller may market units
 * it does not own, and a collaborator may have been granted rights on a project. {@code DevelopmentVisibility}
 * says all of that in the Criteria API; this says it in SQL, once, for the reporting paths that cannot use a
 * Specification.
 *
 * <p><strong>The bug this replaces.</strong> The chart engine spliced the caller's <em>tenant</em> ids into an
 * {@code institution_id IN (…)} predicate, because the only predicate builder it had knew about tenants. For a
 * lender that matched nothing, or matched an institution whose id happened to equal a partnered seller's.
 *
 * <p>Every id here comes from the principal — never from a request — and is a long, so there is nothing to
 * escape. Any column argument may be null when the table has no such column, and the predicate simply does
 * not use that route in.
 */
public final class OwnerScopeSql {

    private OwnerScopeSql() {}

    /**
     * The predicate for the current caller.
     *
     * <ul>
     *   <li>Platform staff: {@code TRUE}.</li>
     *   <li>Lender staff: their own institution's rows, plus the rows of the sellers they are partnered
     *       with.</li>
     *   <li>Seller staff (and agents): their own organisation's rows, the developments they market, and the
     *       developments they have been granted rights on.</li>
     *   <li>Anybody else: {@code FALSE} — nothing rather than everything.</li>
     * </ul>
     *
     * @param tenantColumn      the column holding the owning tenant, or null
     * @param institutionColumn the column holding the owning institution, or null
     * @param developmentColumn the column holding the development, or null — what opens the marketing and
     *                          collaborator routes, which are properties of the development rather than of
     *                          the row
     */
    public static String predicate(String tenantColumn, String institutionColumn, String developmentColumn) {
        UserPrincipal caller = AuthContext.require();
        if (caller.isPlatformStaff()) return "TRUE";

        List<String> ors = new ArrayList<>(4);
        Long institutionId = caller.getInstitutionId();
        Long tenantId = caller.getTenantId();

        if (institutionId != null && institutionColumn != null) {
            ors.add(institutionColumn + " = " + institutionId);
        }
        Set<Long> visible = TenantScope.visibleIds();
        if (tenantColumn != null && visible != null && !visible.isEmpty()) {
            List<String> ids = visible.stream().map(String::valueOf).toList();
            ors.add(tenantColumn + " IN (" + String.join(",", ids) + ")");
        }
        if (tenantId != null && developmentColumn != null) {
            ors.add(developmentColumn + " IN (SELECT id FROM developments WHERE selling_tenant_id = "
                    + tenantId + " AND status <> 5)");
            ors.add(developmentColumn + " IN (SELECT development_id FROM development_collaborators WHERE "
                    + "tenant_id = " + tenantId + " AND revoked_at IS NULL AND status <> 5)");
        }
        return ors.isEmpty() ? "FALSE" : "(" + String.join(" OR ", ors) + ")";
    }
}
