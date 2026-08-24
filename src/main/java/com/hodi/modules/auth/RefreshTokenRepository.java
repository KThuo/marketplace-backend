package com.hodi.modules.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** A user's live sessions, newest first — what the profile's device list shows. */
    @Query("select t from RefreshToken t where t.userId = :userId and t.revoked = false "
            + "and t.expiresAt > :now order by t.createdAt desc")
    List<RefreshToken> findLiveForUser(@Param("userId") Long userId, @Param("now") OffsetDateTime now);

    @Modifying
    @Query("update RefreshToken t set t.revoked = true, t.revokedAt = :now "
            + "where t.userId = :userId and t.revoked = false")
    int revokeAllForUser(@Param("userId") Long userId, @Param("now") OffsetDateTime now);

    /**
     * Housekeeping, not a security control — {@code isUsable()} already checks the clock, so these rows
     * are inert before they are deleted. Kept a day past expiry so a reuse-detection investigation still
     * has something to look at.
     */
    @Modifying
    @Query("delete from RefreshToken t where t.expiresAt < :before")
    int deleteExpired(@Param("before") OffsetDateTime before);

    @Query("select count(t) from RefreshToken t where t.revoked = false and t.expiresAt > :now")
    long countLiveSessions(@Param("now") OffsetDateTime now);
}
