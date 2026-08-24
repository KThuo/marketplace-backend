package com.hodi.modules.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * The audit trail.
 *
 * <p><strong>Ids here are platform-wide.</strong> Worth stating because the axis original this is ported
 * from carried the opposite warning: under schema-per-tenant, every tenant had a user 5, so an audit row's
 * {@code entity_id} meant nothing without its {@code tenant_id} and every finder had to take a tenant to
 * avoid showing one merchant another's history. One schema means one sequence, so an id identifies a row
 * outright and that whole class of finder-level care does not apply. The care that <em>does</em> apply moves
 * to reads: {@code tenant_id} is no longer a hint, it is the filter, and it goes through
 * {@code TenantScope} like every other tenant-bearing list.
 *
 * <p>{@code tenant_id} is nullable and deliberately a soft reference — <em>audit survives the organisation
 * it describes</em>. Terminating a seller must not erase the record of what their staff did, which is also
 * why there is no foreign key here and why {@code actor_username} is denormalised rather than joined: the
 * user row may be gone.
 *
 * <p>Platform staff read across organisations, which is the one place that is legitimate. It is defensible
 * because audit rows are metadata — an actor, an action, an entity name, an outcome — rather than an
 * organisation's business data, and because it is permission-gated on {@code AUDIT_VIEW}. It should stay the
 * only exception.
 *
 * <p>Lists are built with Specifications so {@code TenantScope.restrict("tenantId")} composes into them the
 * same way it does everywhere else — a hand-written finder per filter combination is how one of them ends up
 * without the scope predicate.
 */
public interface AuditLogRepository
        extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
}
