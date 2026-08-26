package com.hodi.modules.operations;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AssignmentRuleRepository
        extends JpaRepository<AssignmentRule, Long>, JpaSpecificationExecutor<AssignmentRule> {

    Optional<AssignmentRule> findByReference(String reference);

    /**
     * The rules that could route this lead, in the order they are tried.
     *
     * <p>The organisation's own rules and the platform's, together and ordered by priority — a seller's
     * routing is theirs to set, and the platform's is the fallback for organisations that have set none.
     * Inactive rules are excluded here rather than skipped later, so "why did nothing match" is answerable
     * by reading this list.
     */
    @Query("select r from AssignmentRule r where r.workType = :workType "
            + "and (r.tenantId is null or r.tenantId = :tenantId) "
            + "and r.status not in (4, 5) order by r.priority asc, r.id asc")
    List<AssignmentRule> findCandidates(@Param("workType") String workType,
                                        @Param("tenantId") Long tenantId);
}
