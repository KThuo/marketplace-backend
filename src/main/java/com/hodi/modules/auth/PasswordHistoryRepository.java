package com.hodi.modules.auth;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PasswordHistoryRepository extends JpaRepository<PasswordHistory, Long> {

    /**
     * The user's most recent password hashes, newest first, at most {@code depth} of them.
     *
     * <p>{@link Limit} rather than a Pageable: this is a top-N read with no interest in a total count,
     * and asking for a page would make Postgres count the whole history to answer a question nobody asked.
     */
    @Query("select h from PasswordHistory h where h.userId = :userId order by h.createdAt desc")
    List<PasswordHistory> findRecent(@Param("userId") Long userId, Limit depth);
}
