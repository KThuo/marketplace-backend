package com.hodi.modules.vendors;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CatalogueItemRepository
        extends JpaRepository<CatalogueItem, Long>, JpaSpecificationExecutor<CatalogueItem> {

    boolean existsByReference(String reference);

    Optional<CatalogueItem> findByReference(String reference);

    /**
     * By reference, and only if it is live.
     *
     * <p>Scoped in the query rather than checked afterwards, for the reason the property one gives: "load it,
     * then decide whether to show it" is the shape that leaks a draft the day somebody forgets the second
     * half.
     */
    @Query("select i from CatalogueItem i where i.reference = :reference "
            + "and i.state = 'LIVE' and i.status <> 5")
    Optional<CatalogueItem> findLiveByReference(@Param("reference") String reference);

    @Query("select i from CatalogueItem i where i.vendorId = :vendorId "
            + "and i.state = 'LIVE' and i.status <> 5 order by i.publishedAt desc")
    List<CatalogueItem> findLiveForVendor(@Param("vendorId") Long vendorId);

    long countByVendorIdAndStateAndStatusNot(Long vendorId, String state, Integer status);
}
