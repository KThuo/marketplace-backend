package com.hodi.modules.sellerops;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PromotionPackageRepository extends JpaRepository<PromotionPackage, Long> {

    Optional<PromotionPackage> findByReference(String reference);

    boolean existsByCodeIgnoreCase(String code);

    @Query("select p from PromotionPackage p where p.status <> 5 order by p.price asc")
    List<PromotionPackage> findAllLive();

    @Query("select p from PromotionPackage p where p.status not in (4, 5) order by p.price asc")
    List<PromotionPackage> findAllActive();
}
