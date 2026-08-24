package com.hodi.modules.users;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long>, JpaSpecificationExecutor<User> {

    /**
     * Login accepts a username or an email, which is what the form offers.
     *
     * <p>Both are unique across the whole table, so one query serves all four populations — a buyer, a
     * seller's agent and a super admin sign in through the same path.
     */
    @Query("select u from User u where lower(u.username) = lower(:username) or u.email = :email")
    Optional<User> findByUsernameIgnoreCaseOrEmail(@Param("username") String username,
                                                   @Param("email") String email);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByUsernameIgnoreCase(String username);

    /**
     * By phone number, for password recovery that identifies somebody by their phone (BRD FR007).
     *
     * <p>Matched on the generated {@code phone_local} column — the last nine digits — so the format somebody
     * types does not decide whether they can recover their account. Indexed, because this endpoint is
     * unauthenticated and a full scan behind an unauthenticated endpoint is a free denial of service.
     *
     * <p>Phone is <em>not</em> unique: two family members can share a handset, and staff rows are created by
     * administrators who sometimes type the office number. So this returns a list and the caller refuses to
     * act on an ambiguous one — sending a reset link for "whichever account matched" is a way to take over
     * the account you did not mean to name.
     */
    @Query("select u from User u where u.phoneLocal = :local and u.phoneLocal <> ''")
    java.util.List<User> findByPhoneLocal(@Param("local") String local);

    /**
     * Everybody, for a search of people rather than of profiles.
     *
     * <p>The counts, the per-organisation lookups and the label rewrites that used to live here moved to
     * {@code UserProfileRepository} with the columns they read. What is left is the credential: find a
     * person, check an address is free, check a username is free.
     */
    java.util.List<User> findByIdIn(java.util.Collection<Long> ids);
}
