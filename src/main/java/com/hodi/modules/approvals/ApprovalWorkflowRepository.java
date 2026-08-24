package com.hodi.modules.approvals;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ApprovalWorkflowRepository
        extends JpaRepository<ApprovalWorkflow, Long>, JpaSpecificationExecutor<ApprovalWorkflow> {

    /**
     * The open request for one thing, if there is one.
     *
     * <p>At most one can exist — a partial unique index enforces it — so this is an Optional rather than a
     * list. Two open requests for the same decision would mean one could be approved while the other stayed
     * pending, and nothing would say which was the real state.
     */
    @Query("select w from ApprovalWorkflow w where w.entityType = :type and w.entityId = :id "
            + "and w.action = :action and w.state = 'PENDING'")
    Optional<ApprovalWorkflow> findPending(@Param("type") String entityType,
                                           @Param("id") Long entityId,
                                           @Param("action") String action);

    @Query("select count(w) from ApprovalWorkflow w where w.state = 'PENDING' "
            + "and (:tenantId is null or w.tenantId = :tenantId) "
            + "and (:institutionId is null or w.institutionId = :institutionId)")
    long countPendingForScope(@Param("tenantId") Long tenantId,
                              @Param("institutionId") Long institutionId);
}
