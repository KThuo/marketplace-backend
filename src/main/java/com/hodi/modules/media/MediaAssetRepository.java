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

    /**
     * The public photographs for several owners of one kind, in one query.
     *
     * <p>For a page that shows every typology in a development with its pictures: four typologies would
     * otherwise be four queries, and the same shape at twenty would be twenty. Ordered so the caller can group
     * by owner and keep each gallery in its intended sequence.
     *
     * <p>An empty collection is the caller's job to short-circuit — {@code in ()} is not valid SQL.
     */
    @Query("select m from MediaAsset m where m.ownerType = :ownerType and m.ownerId in :ownerIds "
            + "and m.publicVisible = true and m.status <> 5 order by m.ownerId, m.sortOrder, m.id")
    List<MediaAsset> findPublicForOwners(@Param("ownerType") String ownerType,
                                         @Param("ownerIds") java.util.Collection<Long> ownerIds);

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
