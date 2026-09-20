package com.hodi.modules.payments;

import com.hodi.modules.developments.Development;
import com.hodi.modules.developments.DevelopmentCollaboratorRepository;
import com.hodi.security.principal.UserPrincipal;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Who may see a payment, and who may see an account.
 *
 * <p>Neither can use {@code TenantScope}, for the reason developments cannot: a lending institution may own
 * them outright, and the bank has no visible-tenant set describing that. So the rule lives here, in one
 * place, and the services have no other way to build a list.
 *
 * <h2>Payments follow the development</h2>
 *
 * <p>The same four routes {@code DevelopmentVisibility.mine} opens, expressed over the payment's own owner
 * columns and a development subquery: the owning institution, the owning tenant, the tenant marketing the
 * units, and a collaborator granted rights. A caller who is none of those gets {@code cb.disjunction()} —
 * nothing rather than everything.
 *
 * <h2>Accounts follow the owner</h2>
 *
 * <p>An account is where an organisation's money goes, and only that organisation and the platform have any
 * business reading it. The platform's own accounts — no owner at all — are the platform's alone.
 */
@Component
@RequiredArgsConstructor
public class PaymentScope {

    private final DevelopmentCollaboratorRepository collaborators;

    /**
     * Rows on a development the caller may see, for any entity carrying {@code tenantId},
     * {@code institutionId} and {@code developmentId}. {@code null} means unrestricted — platform staff only.
     */
    public <T> Specification<T> byDevelopment(UserPrincipal caller) {
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
                // The organisation marketing the units sees the money against them.
                Subquery<Long> selling = query.subquery(Long.class);
                Root<Development> d = selling.from(Development.class);
                selling.select(d.get("id")).where(cb.equal(d.get("sellingTenantId"), tenantId));
                ors.add(root.get("developmentId").in(selling));
            }
            if (!granted.isEmpty()) {
                ors.add(root.get("developmentId").in(granted));
            }
            if (ors.isEmpty()) return cb.disjunction();
            return cb.or(ors.toArray(new Predicate[0]));
        };
    }

    /** Accounts the caller may see: their own organisation's, or everything for the platform. */
    public Specification<PaymentAccount> accounts(UserPrincipal caller) {
        if (caller.isPlatformStaff()) return null;
        Long tenantId = caller.getTenantId();
        Long institutionId = caller.getInstitutionId();
        return (root, query, cb) -> {
            if (institutionId != null) return cb.equal(root.get("institutionId"), institutionId);
            if (tenantId != null) return cb.equal(root.get("tenantId"), tenantId);
            return cb.disjunction();
        };
    }

    /** Whether this caller may read or change one account. The single-row form of {@link #accounts}. */
    /**
     * The bank's notifications this caller may see: those that landed in their own organisation's accounts.
     *
     * <p>A statement has no development — it knows the account it landed in, and the account knows its
     * owner — so the scope is the account's owner, as it is for accounts themselves. Money in an account
     * nobody has registered belongs to nobody yet and is the platform's to see.
     */
    public Specification<com.hodi.infra.coop.CoopStatement> statements(UserPrincipal caller) {
        if (caller.isPlatformStaff()) return null;
        Long tenantId = caller.getTenantId();
        Long institutionId = caller.getInstitutionId();
        return (root, query, cb) -> {
            if (institutionId != null) return cb.equal(root.get("institutionId"), institutionId);
            if (tenantId != null) return cb.equal(root.get("tenantId"), tenantId);
            return cb.disjunction();
        };
    }

    public boolean readsStatement(com.hodi.infra.coop.CoopStatement statement, UserPrincipal caller) {
        if (caller.isPlatformStaff()) return true;
        return (caller.getInstitutionId() != null
                        && Objects.equals(caller.getInstitutionId(), statement.getInstitutionId()))
                || (caller.getTenantId() != null
                        && Objects.equals(caller.getTenantId(), statement.getTenantId()));
    }

    public boolean ownsAccount(PaymentAccount account, UserPrincipal caller) {
        if (caller.isPlatformStaff()) return true;
        if (account.isPlatformOwned()) return false;
        return (caller.getInstitutionId() != null
                        && Objects.equals(caller.getInstitutionId(), account.getInstitutionId()))
                || (caller.getTenantId() != null
                        && Objects.equals(caller.getTenantId(), account.getTenantId()));
    }
}
