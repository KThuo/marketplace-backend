package com.hodi.modules.properties;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PropertyRepository
        extends JpaRepository<Property, Long>, JpaSpecificationExecutor<Property> {

    boolean existsByReference(String reference);

    /** How many listings are filed under one property type (M13). Archived ones excluded. */
    @Query("select count(p) from Property p where p.propertyType = :code and p.status <> 5")
    long countByPropertyType(@Param("code") String code);

    /** How many listings an agent has, archived ones excluded. Counted on read — see AgentService. */
    @Query("select count(p) from Property p where p.agentProfileId = :agentProfileId and p.status <> 5")
    long countByAgentProfileId(@Param("agentProfileId") Long agentProfileId);

    /**
     * By reference, and only if it is live.
     *
     * <p>The public detail page's lookup. Scoped in the query rather than checked afterwards, because "load
     * it, then decide whether to show it" is the shape that leaks a draft the day somebody forgets the second
     * half.
     */
    @Query("select p from Property p where p.reference = :reference "
            + "and p.listingState = 'LIVE' and p.status <> 5")
    Optional<Property> findLiveByReference(@Param("reference") String reference);

    @Query("select p from Property p where p.id = :id and p.listingState = 'LIVE' and p.status <> 5")
    Optional<Property> findLiveById(@Param("id") Long id);

    /**
     * By reference, in whatever state it is in.
     *
     * <p>Only for callers holding an existing relationship to the listing — a shortlist entry saved while it
     * was live and now asking what became of it. Never for discovery: {@link #findLiveByReference} is the
     * lookup for anyone who has not already been shown the listing.
     */
    Optional<Property> findByReference(String reference);

    /** How many live listings a seller has — the dashboard's figure, and cheap enough to ask per card. */
    @Query("select count(p) from Property p where p.tenantId = :tenantId "
            + "and p.listingState = 'LIVE' and p.status <> 5")
    long countLiveForTenant(@Param("tenantId") Long tenantId);

    @Query("select count(p) from Property p where p.tenantId = :tenantId "
            + "and p.listingState = :state and p.status <> 5")
    long countForTenantInState(@Param("tenantId") Long tenantId, @Param("state") String state);

    @Query("select count(p) from Property p where p.listingState = 'LIVE' and p.listingKind <> 'UNIT' "
            + "and p.status <> 5")
    long countLive();

    /** The distinct towns with live listings, for the marketplace's location filter. */
    @Query("select distinct p.town from Property p where p.listingState = 'LIVE' and p.status <> 5 "
            + "and p.listingKind <> 'UNIT' and p.town is not null order by p.town")
    List<String> liveTowns();

    /*
     * The marketplace-only variants are gone.
     *
     * They excluded typology listings, because a development used to appear in search as one card rather than
     * as its kinds. It appears as its kinds now — one row per typology, each with what is left — so the list
     * and the counts are over the same rows again and there is nothing to keep apart.
     */

    /**
     * The listing standing for one typology, if it has one.
     *
     * <p>At most one ever, enforced by a partial unique index rather than by this method hoping: "sixty of
     * seventy available" has to have exactly one place a buyer can read it.
     */
    /** The typology's card. Its units share the unit type id, so the kind is what makes this one row. */
    @Query("select p from Property p where p.unitTypeId = :unitTypeId and p.listingKind = 'TYPOLOGY' "
            + "and p.status <> 5")
    Optional<Property> findByUnitTypeId(@Param("unitTypeId") Long unitTypeId);

    /** Every listing belonging to one development, archived ones excluded. */
    @Query("select p from Property p where p.developmentId = :developmentId and p.status <> 5")
    List<Property> findForDevelopment(@Param("developmentId") Long developmentId);

    /** Re-stamps the denormalised seller name after a rename. One writer, in TenantService. */
    @Modifying
    @Query("update Property p set p.tenantName = :name where p.tenantId = :tenantId")
    int renameTenantLabel(@Param("tenantId") Long tenantId, @Param("name") String name);
}
