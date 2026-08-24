package com.hodi.modules.partnerships;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PartnershipRepository
        extends JpaRepository<Partnership, Long>, JpaSpecificationExecutor<Partnership> {

    /**
     * The sellers a lending institution's staff may currently see.
     *
     * <p><strong>The single most security-critical query in this application.</strong> Its result becomes
     * {@code UserPrincipal.visibleTenantIds}, which is what {@code TenantScope} filters every read by — so
     * a predicate missing from here is a cross-organisation leak, and a predicate wrongly added here locks
     * a lender out of work they are entitled to.
     *
     * <p>The three conditions are exactly {@link Partnership#isActive()}, and they must stay exactly that:
     * approved, not revoked, row in force. There is a test asserting the two agree, because they are the
     * same rule expressed twice and the pair is the sort of thing that drifts silently.
     *
     * <p>Backed by {@code idx_partnership_active_by_institution}, a partial index over the same three
     * conditions.
     */
    @Query("select p.tenantId from Partnership p "
            + "where p.institutionId = :institutionId "
            + "and p.approvedAt is not null and p.revokedAt is null and p.status <> 5 and p.status <> 4")
    List<Long> findActiveTenantIdsForInstitution(@Param("institutionId") Long institutionId);

    /** The institutions currently able to see one seller's portfolio — the seller's own view of this. */
    @Query("select p.institutionId from Partnership p "
            + "where p.tenantId = :tenantId "
            + "and p.approvedAt is not null and p.revokedAt is null and p.status <> 5 and p.status <> 4")
    List<Long> findActiveInstitutionIdsForTenant(@Param("tenantId") Long tenantId);

    Optional<Partnership> findByTenantIdAndInstitutionId(Long tenantId, Long institutionId);

    /**
     * Pending proposals awaiting one seller's decision, for their dashboard's action card.
     *
     * <p>Pending means approved_at is null and revoked_at is null — a proposal somebody has to answer,
     * rather than one already settled either way.
     */
    @Query("select count(p) from Partnership p where p.tenantId = :tenantId "
            + "and p.approvedAt is null and p.revokedAt is null and p.status <> 5")
    long countPendingForTenant(@Param("tenantId") Long tenantId);

    @Query("select count(p) from Partnership p where p.institutionId = :institutionId "
            + "and p.approvedAt is null and p.revokedAt is null and p.status <> 5")
    long countPendingForInstitution(@Param("institutionId") Long institutionId);

    @Query("select count(p) from Partnership p "
            + "where p.approvedAt is not null and p.revokedAt is null and p.status <> 5")
    long countActive();

    @Query("select count(p) from Partnership p "
            + "where p.approvedAt is null and p.revokedAt is null and p.status <> 5")
    long countPending();
}
