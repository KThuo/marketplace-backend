package com.hodi.modules.kyc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface KycSubmissionRepository
        extends JpaRepository<KycSubmission, Long>, JpaSpecificationExecutor<KycSubmission> {

    boolean existsByReference(String reference);

    Optional<KycSubmission> findByReference(String reference);

    /** The pack this profile is currently working on or waiting on. At most one — a partial unique index. */
    @Query("select s from KycSubmission s where s.profileId = :profileId "
            + "and s.state in ('DRAFT', 'SUBMITTED', 'MORE_INFO') and s.status <> 5")
    Optional<KycSubmission> findLiveForProfile(@Param("profileId") Long profileId);

    @Query("select s from KycSubmission s where s.profileId = :profileId and s.status <> 5 "
            + "order by s.createdAt desc limit 1")
    Optional<KycSubmission> findLatestForProfile(@Param("profileId") Long profileId);

    @Query("select count(s) from KycSubmission s where s.state = 'SUBMITTED' and s.status <> 5")
    long countWaiting();
}
