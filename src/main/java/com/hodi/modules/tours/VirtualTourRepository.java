package com.hodi.modules.tours;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface VirtualTourRepository extends JpaRepository<VirtualTour, Long> {

    @Query("select t from VirtualTour t where t.ownerType = :ownerType and t.ownerId = :ownerId "
            + "and t.status <> 5 order by t.sortOrder asc, t.id asc")
    List<VirtualTour> findForOwner(@Param("ownerType") String ownerType, @Param("ownerId") Long ownerId);

    @Query("select count(t) from VirtualTour t where t.ownerType = :ownerType and t.ownerId = :ownerId "
            + "and t.status <> 5")
    long countForOwner(@Param("ownerType") String ownerType, @Param("ownerId") Long ownerId);

    @Query("select count(t) > 0 from VirtualTour t where t.ownerType = :ownerType and t.ownerId = :ownerId "
            + "and t.videoId = :videoId and t.status <> 5")
    boolean holds(@Param("ownerType") String ownerType, @Param("ownerId") Long ownerId,
                  @Param("videoId") String videoId);

    /**
     * Which of these owners have at least one tour — for the "Video tour" pill on a page of cards.
     *
     * <p>One query for the page rather than one per card: search is the hottest read path here.
     */
    @Query("select distinct t.ownerId from VirtualTour t where t.ownerType = :ownerType "
            + "and t.ownerId in :ownerIds and t.status <> 5")
    List<Long> ownersWithTours(@Param("ownerType") String ownerType, @Param("ownerIds") Collection<Long> ownerIds);
}
