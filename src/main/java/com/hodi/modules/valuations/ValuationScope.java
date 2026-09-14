package com.hodi.modules.valuations;

import com.hodi.common.exception.HodiException;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Who may see which valuation (plan §3.5).
 *
 * <h2>Four rules on one table</h2>
 *
 * <ul>
 *   <li><strong>Platform staff</strong> — everything. They run the panel.</li>
 *   <li><strong>A seller</strong> — the jobs their organisation raised.</li>
 *   <li><strong>The bank</strong> — the jobs their institution raised. Not the partnered seller's: a
 *       partnership opens a portfolio, not the valuations somebody else commissioned on it.</li>
 *   <li><strong>A valuer</strong> — the jobs assigned to <em>them</em>. Not their firm's, not the requesting
 *       seller's portfolio, and nothing about the property beyond what the job carries.</li>
 * </ul>
 *
 * <h2>Why this is not in TenantScope</h2>
 *
 * <p>Plan §3.5 sketched this as "{@code TenantScope.visibleIds()} gains an assignment source". Building it
 * that way turned out to be the wrong shape, and it is worth saying why rather than quietly diverging.
 *
 * <p>{@code TenantScope} answers one question — <em>which organisations may this caller see</em> — and every
 * module composes that answer against its own {@code tenantId} column. A valuer's rule is not about
 * organisations at all: it is about a column that exists only on this table. Teaching {@code TenantScope}
 * about {@code valuer_profile_id} would make the platform's central visibility primitive know the name of
 * one module's column, and the next actor with a row-level rule would add a second. So the rule lives beside
 * the rows it governs, and {@code TenantScope} keeps meaning exactly one thing.
 *
 * <p>What §3.5 was protecting is preserved and then some: a valuer gets <em>no</em> organisation-wide
 * visibility anywhere, because they hold no organisation at all — {@code TenantScope.visibleIds()} is empty
 * for them, so every other module's lists are already closed to them by construction.
 */
public final class ValuationScope {

    private ValuationScope() {}

    /**
     * Restricts a valuation query to what this caller may see.
     *
     * @param valuerProfileId the caller's own valuer profile id, or null when they are not a valuer
     * @return {@code null} for platform staff, so it composes with {@code SearchSpecs.allOf}
     */
    public static Specification<ValuationRequest> restrict(Long valuerProfileId) {
        UserPrincipal caller = AuthContext.require();
        if (caller.isPlatformStaff()) return null;

        return (root, query, cb) -> {
            List<Predicate> ors = new ArrayList<>(3);
            if (caller.getTenantId() != null) {
                ors.add(cb.equal(root.get("tenantId"), caller.getTenantId()));
            }
            if (caller.getInstitutionId() != null) {
                ors.add(cb.equal(root.get("institutionId"), caller.getInstitutionId()));
            }
            if (valuerProfileId != null) {
                ors.add(cb.equal(root.get("valuerProfileId"), valuerProfileId));
            }
            // None of the three: a buyer, or a valuer whose panel profile has not been set up. Matches
            // nothing, deliberately — the alternative is a caller with no rule seeing every valuation on the
            // platform, which is the failure this class exists to prevent.
            if (ors.isEmpty()) return cb.disjunction();
            return cb.or(ors.toArray(new Predicate[0]));
        };
    }

    /**
     * Guards a single row against a caller who may not see it.
     *
     * <p>The same four rules, applied to a row already loaded. Used by every read-by-reference path, because
     * "load it, then decide" is only safe when the deciding actually happens.
     */
    public static void assertVisible(ValuationRequest request, Long valuerProfileId) {
        UserPrincipal caller = AuthContext.require();
        if (caller.isPlatformStaff()) return;

        boolean mine =
                (caller.getTenantId() != null && caller.getTenantId().equals(request.getTenantId()))
                || (caller.getInstitutionId() != null
                        && caller.getInstitutionId().equals(request.getInstitutionId()))
                || (valuerProfileId != null && valuerProfileId.equals(request.getValuerProfileId()));

        if (!mine) {
            // Deliberately not "valuation 42 belongs to somebody else", which would confirm it exists.
            throw new HodiException("That valuation is not yours to see.", HttpStatus.FORBIDDEN);
        }
    }
}
