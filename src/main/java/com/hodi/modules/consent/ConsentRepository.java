package com.hodi.modules.consent;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ConsentRepository extends JpaRepository<ConsentPreference, Long> {

    List<ConsentPreference> findByUserIdOrderByPurposeAscChannelAsc(Long userId);

    boolean existsByUserId(Long userId);

    Optional<ConsentPreference> findByUserIdAndChannelAndPurpose(Long userId, String channel, String purpose);

    /**
     * The channels one person has agreed to be contacted on for one purpose.
     *
     * <p>The dispatcher's question, and the only read on this table that decides whether a message is sent.
     * Returns channel codes rather than rows: the caller wants a set to iterate, and giving it entities
     * invites somebody to make a delivery decision from a field other than {@code granted}.
     */
    @Query("select c.channel from ConsentPreference c "
            + "where c.userId = :userId and c.purpose = :purpose and c.granted = true")
    List<String> grantedChannels(@Param("userId") Long userId, @Param("purpose") String purpose);
}
