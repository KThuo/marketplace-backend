package com.hodi.modules.valuations;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.approvals.ApprovalHandler;
import com.hodi.modules.approvals.ApprovalWorkflow;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * A valuation report is checked before it counts.
 *
 * <p>The figure a bank lends against should not be final on one person's say-so. The valuer submits; anyone
 * holding {@code VALUATIONS_APPROVE} — the platform's, never the requester's, because a seller approving the
 * figure on their own sale is the thing the panel exists to prevent — approves it to COMPLETED or sends it
 * back. The generic rule that the submitter cannot decide their own applies as everywhere.
 */
@Component
@RequiredArgsConstructor
public class ValuationApprovalHandler implements ApprovalHandler {

    private final ValuationService service;

    @Override
    public String entityType() {
        return AppConstant.APPROVAL_ENTITY_VALUATION;
    }

    @Override
    public String decidePermission() {
        return "VALUATIONS_APPROVE";
    }

    /** The platform's staff decide; a requester reads the outcome. */
    @Override
    public void assertMayDecide(ApprovalWorkflow workflow, UserPrincipal caller) {
        if (caller.isPlatformStaff()) return;
        throw new HodiException("A valuation report is reviewed by the platform, not by the party that "
                + "commissioned it.", HttpStatus.FORBIDDEN);
    }

    @Override
    public void onApproved(ApprovalWorkflow workflow) {
        service.applyApproval(workflow.getEntityId(), workflow.getCheckedByUsername(), workflow.getDecisionReason());
    }

    @Override
    public void onRefused(ApprovalWorkflow workflow) {
        service.applyRefusal(workflow.getEntityId(), workflow.getCheckedByUsername(), workflow.getDecisionReason());
    }
}
