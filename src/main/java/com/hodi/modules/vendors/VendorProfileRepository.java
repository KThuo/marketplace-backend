package com.hodi.modules.vendors;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface VendorProfileRepository
        extends JpaRepository<VendorProfile, Long>, JpaSpecificationExecutor<VendorProfile> {

    Optional<VendorProfile> findByReference(String reference);

    Optional<VendorProfile> findByProfileId(Long profileId);

    Optional<VendorProfile> findFirstByUserIdOrderByIdDesc(Long userId);

    long countByState(String state);

    long countByCategoryIdAndStatusNot(Long categoryId, Integer status);

    /** The public directory's lookup: approved, live, by reference. */
    @Query("select v from VendorProfile v where v.reference = :reference "
            + "and v.state = 'APPROVED' and v.status <> 5")
    Optional<VendorProfile> findPublicByReference(String reference);
}
