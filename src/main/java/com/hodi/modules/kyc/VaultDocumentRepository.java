package com.hodi.modules.kyc;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VaultDocumentRepository extends JpaRepository<VaultDocument, Long> {

    boolean existsByReference(String reference);

    Optional<VaultDocument> findByReference(String reference);

    @Query("select d from VaultDocument d where d.tenantId = :tenantId and d.status <> 5 "
            + "order by d.createdAt desc")
    List<VaultDocument> findForTenant(@Param("tenantId") Long tenantId);

    /** Compliance's list: what lapses inside the window. */
    @Query("select d from VaultDocument d where d.expiresOn is not null and d.expiresOn <= :before "
            + "and d.status <> 5 order by d.expiresOn asc")
    List<VaultDocument> findExpiringBefore(@Param("before") java.time.LocalDate before);
}
