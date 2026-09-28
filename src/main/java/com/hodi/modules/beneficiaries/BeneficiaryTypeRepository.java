package com.hodi.modules.beneficiaries;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BeneficiaryTypeRepository extends JpaRepository<BeneficiaryType, Long> {

    /** Everything not archived, in the platform's own order — the admin screen. */
    @Query("select t from BeneficiaryType t where t.status <> 5 order by t.sortOrder, t.name")
    List<BeneficiaryType> findAllLive();

    /** What the form offers: switched on, in order. */
    @Query("select t from BeneficiaryType t where t.status in (1, 2) order by t.sortOrder, t.name")
    List<BeneficiaryType> findAvailable();

    @Query("select count(t) from BeneficiaryType t where t.code = :code and t.status <> 5 and t.id <> :exceptId")
    long countByCodeExcept(@Param("code") String code, @Param("exceptId") Long exceptId);
}
