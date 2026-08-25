package com.hodi.modules.kyc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface KycRequirementRepository extends JpaRepository<KycRequirement, Long> {

    @Query("select r from KycRequirement r where r.entityType = :entityType and r.version = :version "
            + "and r.status <> 5 order by r.sortOrder asc")
    List<KycRequirement> findFor(@Param("entityType") String entityType, @Param("version") Integer version);

    /** The version Compliance is currently judging against, for this kind of seller. */
    @Query("select coalesce(max(r.version), 1) from KycRequirement r where r.entityType = :entityType "
            + "and r.status <> 5")
    Integer currentVersion(@Param("entityType") String entityType);

    @Query("select distinct r.entityType from KycRequirement r where r.status <> 5 order by r.entityType")
    List<String> entityTypes();
}
