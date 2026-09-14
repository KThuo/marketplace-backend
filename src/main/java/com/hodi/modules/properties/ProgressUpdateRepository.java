package com.hodi.modules.properties;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProgressUpdateRepository extends JpaRepository<ProgressUpdate, Long> {

    /** The seller's own view: everything, drafts included, newest work first. */
    @Query("select u from ProgressUpdate u where u.propertyId = :propertyId and u.status <> 5 "
            + "order by u.reportedOn desc, u.id desc")
    List<ProgressUpdate> findForProperty(@Param("propertyId") Long propertyId);

    /** What a buyer sees. Published only — the same construction the marketplace uses for listings. */
    @Query("select u from ProgressUpdate u where u.propertyId = :propertyId "
            + "and u.published = true and u.status <> 5 order by u.reportedOn desc, u.id desc")
    List<ProgressUpdate> findPublishedForProperty(@Param("propertyId") Long propertyId);

    @Query("select count(u) from ProgressUpdate u where u.propertyId = :propertyId "
            + "and u.published = true and u.status <> 5")
    long countPublished(@Param("propertyId") Long propertyId);

    // ── the development's timeline ─────────────────────────────────────────────
    //
    // Separate queries rather than one with a nullable subject parameter. `where u.propertyId = :p or
    // u.developmentId = :d` with one of them null reads as "or false" and works, right up to the day somebody
    // passes both — and then it silently returns another project's updates. Two queries cannot do that.

    /** The owner's own view of a development's timeline: drafts included, newest work first. */
    @Query("select u from ProgressUpdate u where u.developmentId = :developmentId and u.status <> 5 "
            + "order by u.reportedOn desc, u.id desc")
    List<ProgressUpdate> findForDevelopment(@Param("developmentId") Long developmentId);

    /**
     * What the public sees on a development's page.
     *
     * <p>Published <em>and</em> written for the public. A detailed update that somebody published is still a
     * detailed update, and this is the query that decides a stranger never reads one — not a filter applied
     * afterwards, which is a condition somebody can forget.
     */
    @Query("select u from ProgressUpdate u where u.developmentId = :developmentId "
            + "and u.published = true and u.audience = 'PUBLIC' and u.status <> 5 "
            + "order by u.reportedOn desc, u.id desc")
    List<ProgressUpdate> findPublicForDevelopment(@Param("developmentId") Long developmentId);

    @Query("select count(u) from ProgressUpdate u where u.developmentId = :developmentId "
            + "and u.published = true and u.audience = 'PUBLIC' and u.status <> 5")
    long countPublicForDevelopment(@Param("developmentId") Long developmentId);

    /**
     * The cross-project feed on the public site.
     *
     * <p>Paged, and the ids are supplied by the caller rather than joined here: which developments are live
     * is {@code PublicDevelopmentService}'s question, and answering it in JPQL would put the visibility rule
     * in two places. An empty list is the caller's job to short-circuit — {@code in ()} is not valid SQL.
     *
     * <p>Ordered by id as well as date so a page boundary between two updates reported on the same day is
     * stable. Without the tiebreak, page two can repeat a row or skip one.
     */
    @Query("select u from ProgressUpdate u where u.developmentId in :developmentIds "
            + "and u.published = true and u.audience = 'PUBLIC' and u.status <> 5 "
            + "order by u.reportedOn desc, u.id desc")
    Page<ProgressUpdate> findPublicFeed(
            @Param("developmentIds") List<Long> developmentIds, Pageable pageable);

    boolean existsByReference(String reference);

    /**
     * One post, for its own page.
     *
     * <p>The three conditions the feed applies, applied here too and in the query rather than after it. A
     * post page is reachable by anybody who has the address, so "published", "written for the public" and
     * "still not archived" have to be part of finding it — a filter applied afterwards is one somebody can
     * forget, and what leaks is a detailed build report meant for the people financing the project.
     *
     * <p>What this does <em>not</em> check is whether the development is still live, because that is a fact
     * about another table. {@code PublicDevelopmentService} re-checks it, and must: a post on a withdrawn
     * project is not public any more, however published the post itself remains.
     */
    @Query("select u from ProgressUpdate u where u.reference = :reference "
            + "and u.published = true and u.audience = 'PUBLIC' and u.status <> 5")
    Optional<ProgressUpdate> findPublicByReference(@Param("reference") String reference);
}
