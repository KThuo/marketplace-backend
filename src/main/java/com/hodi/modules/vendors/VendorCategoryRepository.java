package com.hodi.modules.vendors;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface VendorCategoryRepository extends JpaRepository<VendorCategory, Long> {

    Optional<VendorCategory> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    @Query("select c from VendorCategory c where c.status <> 5 order by c.sortOrder asc, c.name asc")
    List<VendorCategory> findAllLive();

    @Query("select c from VendorCategory c where c.status not in (4, 5) order by c.sortOrder asc, c.name asc")
    List<VendorCategory> findAllActive();
}
