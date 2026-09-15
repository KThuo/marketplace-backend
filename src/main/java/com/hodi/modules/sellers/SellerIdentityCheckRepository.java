package com.hodi.modules.sellers;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SellerIdentityCheckRepository extends JpaRepository<SellerIdentityCheck, Long> {

    List<SellerIdentityCheck> findByApplicationIdOrderByRanAtAsc(Long applicationId);
}
