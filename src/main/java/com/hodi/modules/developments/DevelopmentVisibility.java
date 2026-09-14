package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.security.principal.UserPrincipal;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Who may see and change a development.
 *
 * <h2>Why this is its own class</h2>
 *
 * <p>Every other module in this codebase gets row scoping from {@code TenantScope}, which is a single choke
 * point: one method, used everywhere, impossible to forget in a way that compiles. A development cannot use
 * it, because its owner may be a lending institution and the bank has no visible-tenant set describing it.
 * {@code AuctionService} met the same wall and wrote a private {@code mine()} specification.
 *
 * <p>That is the right answer and it has a cost worth naming: the protection is now a rule somebody has to
 * remember to apply, and a repository query written without it is a cross-organisation read. So the rule lives
 * in one place with a test on it rather than as a private method in a service, and the service has no other
 * way to build a list.
 *
 * <h2>Four ways in, and only one of them is ownership</h2>
 *
 * <ol>
 *   <li><b>Platform staff</b> — everything, because somebody has to administer the rows.</li>
 *   <li><b>The owning institution</b> — a bank sees the projects it financed.</li>
 *   <li><b>The owning tenant</b>, or the tenant marketing it — a developer sees their own, and a seller
 *       marketing somebody else's units sees the development those units belong to.</li>
 *   <li><b>A collaborator</b> — the developer a bank granted progress rights to. This is the only route that
 *       is not a property of the row itself, which is why it is a subquery rather than a column comparison,
 *       and why revoking a grant takes effect on the next query rather than needing anything cleaned up.</li>
 * </ol>
 *
 * <p>A caller who is none of those gets {@code cb.disjunction()} — an empty result rather than an unscoped
 * one. A buyer browsing the workspace is that caller, and the honest answer to "which developments are yours"
 * is none.
 */
@Component
@RequiredArgsConstructor
public class DevelopmentVisibility {

    private final DevelopmentCollaboratorRepository collaborators;

    /**
     * The list a caller is entitled to. {@code null} means unrestricted, which is what a Specification
     * conjunction expects for "add no predicate" — and only platform staff get it.
     */
    public Specification<Development> mine(UserPrincipal caller) {
        if (caller.isPlatformStaff()) return null;

        Long tenantId = caller.getTenantId();
        Long institutionId = caller.getInstitutionId();
        List<Long> granted = tenantId == null ? List.of() : collaborators.developmentIdsFor(tenantId);

        return (root, query, cb) -> {
            List<Predicate> ors = new ArrayList<>(4);
            if (institutionId != null) {
                ors.add(cb.equal(root.get("institutionId"), institutionId));
            }
            if (tenantId != null) {
                ors.add(cb.equal(root.get("tenantId"), tenantId));
                ors.add(cb.equal(root.get("sellingTenantId"), tenantId));
            }
            if (!granted.isEmpty()) {
                ors.add(root.get("id").in(granted));
            }
            // Nothing rather than everything. An empty predicate list here would widen the query to the whole
            // table, which is the failure mode this class exists to make impossible.
            if (ors.isEmpty()) return cb.disjunction();
            return cb.or(ors.toArray(new Predicate[0]));
        };
    }

    /** Whether this caller may read one development. The single-row form of {@link #mine}. */
    public boolean mayRead(Development development, UserPrincipal caller) {
        if (caller.isPlatformStaff()) return true;
        if (caller.getInstitutionId() != null
                && caller.getInstitutionId().equals(development.getInstitutionId())) {
            return true;
        }
        Long tenantId = caller.getTenantId();
        if (tenantId == null) return false;
        return tenantId.equals(development.getTenantId())
                || tenantId.equals(development.getSellingTenantId())
                || grant(development, caller).isPresent();
    }

    /**
     * Refuses unless this caller owns the development.
     *
     * <p>Ownership, not visibility: a collaborator may post progress and a marketing seller may list units,
     * and neither may rename the project or change its budget. Those are separate questions and this method
     * answers only the first, which is why {@link #assertMayWriteProgress} exists beside it rather than a
     * single "may write" that would have to mean the loosest of the two.
     */
    public void assertMayManage(Development development, UserPrincipal caller) {
        if (caller.isPlatformStaff()) return;
        boolean owns = (caller.getInstitutionId() != null
                        && caller.getInstitutionId().equals(development.getInstitutionId()))
                || (caller.getTenantId() != null
                        && caller.getTenantId().equals(development.getTenantId()));
        if (!owns) {
            throw new HodiException("That development belongs to another organisation.",
                    HttpStatus.FORBIDDEN);
        }
    }

    /**
     * Refuses unless this caller may post progress: the owner, the platform, or a collaborator granted it.
     *
     * <p>This is the whole point of the collaborator table. A bank owns the record because the exposure is
     * theirs; the developer is the one on site with the photographs.
     */
    public void assertMayWriteProgress(Development development, UserPrincipal caller) {
        if (caller.isPlatformStaff()) return;
        boolean owns = (caller.getInstitutionId() != null
                        && caller.getInstitutionId().equals(development.getInstitutionId()))
                || (caller.getTenantId() != null
                        && caller.getTenantId().equals(development.getTenantId()));
        if (owns) return;
        if (grant(development, caller).filter(DevelopmentCollaborator::mayWriteProgress).isPresent()) {
            return;
        }
        throw new HodiException("You have not been given progress rights on that development.",
                HttpStatus.FORBIDDEN);
    }

    /** Refuses unless this caller may change the unit inventory: the owner, the platform, or a UNITS grant. */
    public void assertMayWriteUnits(Development development, UserPrincipal caller) {
        if (caller.isPlatformStaff()) return;
        boolean owns = (caller.getInstitutionId() != null
                        && caller.getInstitutionId().equals(development.getInstitutionId()))
                || (caller.getTenantId() != null
                        && caller.getTenantId().equals(development.getTenantId()))
                // The organisation marketing the units is the one selling them, so it manages them.
                || (caller.getTenantId() != null
                        && caller.getTenantId().equals(development.getSellingTenantId()));
        if (owns) return;
        if (grant(development, caller).filter(DevelopmentCollaborator::mayWriteUnits).isPresent()) {
            return;
        }
        throw new HodiException("You may not change the units on that development.", HttpStatus.FORBIDDEN);
    }

    /**
     * Whether a development may show its progress publicly.
     *
     * <p>A tracked project is nobody's business but its owner's and the developer's. Enforced here rather
     * than by a column on the progress row: a project that goes private later would leave published rows
     * behind, and one predicate at the door is easier to be sure of than a sweep.
     */
    public boolean mayPublishProgress(Development development) {
        return !development.isPrivate() && !AppConstant.LISTING_DRAFT.equals(development.getListingState());
    }

    private Optional<DevelopmentCollaborator> grant(Development development, UserPrincipal caller) {
        if (caller.getTenantId() == null || development.getId() == null) return Optional.empty();
        return collaborators.findLiveGrant(development.getId(), caller.getTenantId());
    }
}
