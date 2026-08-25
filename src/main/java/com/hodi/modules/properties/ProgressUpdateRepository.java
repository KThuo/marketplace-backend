package com.hodi.modules.properties;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProgressUpdateRepository extends JpaRepository<ProgressUpdate, Long> {

    /** The seller's own view: everything, drafts included, newest work first. */
    @Query("select u from ProgressUpdate u where u.propertyId = :propertyId and u.status <> 5 "
            + "order by u.reportedOn desc, u.id desc")
    List<ProgressUpdate> findForProperty(@Param("propertyId") Long propertyId);

    /** What a buyer sees. Published only — the same construction the marketplace uses for listings. */
    @Query("select u from ProgressUpdate u where u.propertyId = :propertyId "
            + "and u.published = true and u.status <> 5 order by u.reportedOn desc, u.id desc")
    List<ProgressUpdate> findPublishedForProperty(@Param("propertyId") Long propertyId);

    @Query("select count(u) from ProgressUpdate u where u.propertyId = :propertyId "
            + "and u.published = true and u.status <> 5")
    long countPublished(@Param("propertyId") Long propertyId);
}
