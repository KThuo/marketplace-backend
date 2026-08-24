package com.hodi.modules.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    Optional<PasswordResetToken> findByCodeHash(String codeHash);

    /**
     * Spends every outstanding reset for a user.
     *
     * <p>Called when a new one is issued, so requesting a second link invalidates the first. Without it,
     * every reset ever emailed to somebody stays usable until it expires — and the one an attacker
     * triggered is as good as the one the user asked for.
     */
    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :now "
            + "where t.userId = :userId and t.usedAt is null")
    int spendOutstanding(@Param("userId") Long userId, @Param("now") OffsetDateTime now);
}
