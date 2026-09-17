package com.hodi.modules.properties;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PropertyMediaRepository extends JpaRepository<PropertyMedia, Long> {

    @Query("select m from PropertyMedia m where m.propertyId = :propertyId and m.status <> 5 "
            + "order by m.primary desc, m.sortOrder asc, m.id asc")
    List<PropertyMedia> findForProperty(@Param("propertyId") Long propertyId);

    @Query("select m from PropertyMedia m where m.propertyId = :propertyId and m.primary = true "
            + "and m.status <> 5")
    Optional<PropertyMedia> findPrimary(@Param("propertyId") Long propertyId);

    @Query("select count(m) from PropertyMedia m where m.propertyId = :propertyId and m.status <> 5")
    long countForProperty(@Param("propertyId") Long propertyId);

    /**
     * How many of one kind this listing holds.
     *
     * <p>Used to decide whether an upload is the cover: the cover is the first <em>photograph</em>, and
     * before kinds existed every row was one so "the first row" happened to mean the same thing.
     */
    @Query("select count(m) from PropertyMedia m where m.propertyId = :propertyId "
            + "and m.mediaKind = :kind and m.status <> 5")
    long countOfKind(@Param("propertyId") Long propertyId, @Param("kind") String kind);

    @Query("select m from PropertyMedia m where m.propertyId = :propertyId and m.mediaKind = :kind "
            + "and m.status <> 5 order by m.primary desc, m.sortOrder asc, m.id asc")
    List<PropertyMedia> findOfKind(@Param("propertyId") Long propertyId, @Param("kind") String kind);

    /**
     * Clears the primary flag across a property's photographs.
     *
     * <p>Run before setting a new one, because the partial unique index refuses two — and refusing is right:
     * the card reads whichever row claims to be primary, and two of them would make its contents a matter of
     * query order.
     */
    @Modifying
    @Query("update PropertyMedia m set m.primary = false where m.propertyId = :propertyId")
    int clearPrimary(@Param("propertyId") Long propertyId);
}
