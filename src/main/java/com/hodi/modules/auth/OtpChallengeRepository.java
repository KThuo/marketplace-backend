package com.hodi.modules.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, Long> {

    Optional<OtpChallenge> findByChallengeToken(String challengeToken);

    Optional<OtpChallenge> findByUserIdAndPurposeAndConsumedAtIsNull(Long userId, String purpose);

    /** Consumes any outstanding challenge of one purpose, so issuing a new code retires the old. */
    @Modifying
    @Query("update OtpChallenge c set c.consumedAt = :now "
            + "where c.userId = :userId and c.purpose = :purpose and c.consumedAt is null")
    int consumeOutstanding(@Param("userId") Long userId,
                           @Param("purpose") String purpose,
                           @Param("now") OffsetDateTime now);

    @Modifying
    @Query("delete from OtpChallenge c where c.expiresAt < :before")
    int deleteExpired(@Param("before") OffsetDateTime before);
}
