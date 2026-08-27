package com.hodi.modules.media;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {

    @Query("select m from MediaAsset m where m.ownerType = :ownerType and m.ownerId = :ownerId "
            + "and m.status <> 5 order by m.sortOrder, m.id")
    List<MediaAsset> findForOwner(@Param("ownerType") String ownerType, @Param("ownerId") Long ownerId);

    /** Public-visible only, for a buyer-facing gallery. Scoped in the query, not filtered afterwards. */
    @Query("select m from MediaAsset m where m.ownerType = :ownerType and m.ownerId = :ownerId "
            + "and m.publicVisible = true and m.status <> 5 order by m.sortOrder, m.id")
    List<MediaAsset> findPublicForOwner(@Param("ownerType") String ownerType,
                                        @Param("ownerId") Long ownerId);

    @Query("select m from MediaAsset m where m.ownerType = :ownerType and m.ownerId = :ownerId "
            + "and m.primary = true and m.status <> 5")
    Optional<MediaAsset> findPrimary(@Param("ownerType") String ownerType, @Param("ownerId") Long ownerId);

    @Query("select count(m) from MediaAsset m where m.ownerType = :ownerType and m.ownerId = :ownerId "
            + "and m.status <> 5")
    long countForOwner(@Param("ownerType") String ownerType, @Param("ownerId") Long ownerId);

    /**
     * Clears the cover flag across an owner's assets.
     *
     * <p>Called before setting a new one, because the partial unique index permits exactly one — the same
     * two-step PropertyMediaService already does, and for the same reason: the index is what makes "one
     * cover" true, so the service has to make room rather than hope.
     */
    @Modifying
    @Query("update MediaAsset m set m.primary = false "
            + "where m.ownerType = :ownerType and m.ownerId = :ownerId")
    int clearPrimary(@Param("ownerType") String ownerType, @Param("ownerId") Long ownerId);
}
