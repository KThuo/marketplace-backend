package com.hodi.modules.disbursements;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.approvals.ApprovalHandler;
import com.hodi.modules.approvals.ApprovalWorkflow;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * What "approved" means for a disbursement: the money is sent.
 *
 * <p>The one approval on the platform that moves money out, and the reason the queue shows the maker's
 * snapshot in full: the checker reads the amount, the account, the name Co-op resolved it to and the
 * purpose, and releases exactly that. The generic rule already bars the maker from checking; this adds
 * only that the decider is the platform's, because the money is.
 */
@Component
@RequiredArgsConstructor
public class DisbursementApprovalHandler implements ApprovalHandler {

    private final DisbursementService service;

    @Override
    public String entityType() {
        return AppConstant.APPROVAL_ENTITY_DISBURSEMENT;
    }

    @Override
    public String decidePermission() {
        return "DISBURSEMENTS_APPROVE";
    }

    /**
     * The bank's staff decide anything. A development's payment is scoped to the organisation that manages
     * its spending, and that organisation's checker decides it — their money, their second pair of eyes. The
     * bank's own payouts carry no organisation, so nobody but the bank reaches them.
     */
    @Override
    public void assertMayDecide(ApprovalWorkflow workflow, UserPrincipal caller) {
        if (caller.isPlatformStaff()) return;
        boolean ownTenant = workflow.getTenantId() != null && workflow.getTenantId().equals(caller.getTenantId());
        boolean ownInstitution = workflow.getInstitutionId() != null
                && workflow.getInstitutionId().equals(caller.getInstitutionId());
        if (ownTenant || ownInstitution) return;
        throw new HodiException(workflow.getTenantId() == null && workflow.getInstitutionId() == null
                ? "Only the bank's own staff may release the bank's money."
                : "That payment is another organisation's to decide.", HttpStatus.FORBIDDEN);
    }

    @Override
    public void onApproved(ApprovalWorkflow workflow) {
        service.approved(workflow.getEntityId(), workflow.getCheckedByUsername());
    }

    @Override
    public void onRefused(ApprovalWorkflow workflow) {
        service.refused(workflow.getEntityId(), workflow.getDecision(), workflow.getDecisionReason(),
                workflow.getCheckedByUsername());
    }
}
