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
