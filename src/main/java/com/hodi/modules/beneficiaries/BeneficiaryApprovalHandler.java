package com.hodi.modules.beneficiaries;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.approvals.ApprovalHandler;
import com.hodi.modules.approvals.ApprovalWorkflow;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * What "approved" means for a beneficiary: it may be paid, once the bank has confirmed the account.
 *
 * <p>The same shape as a payment account's handler and for the same reason: the row decides where real money
 * goes, so it is written but not live until a second person, holding a permission of their own, agrees.
 */
@Component
@RequiredArgsConstructor
public class BeneficiaryApprovalHandler implements ApprovalHandler {

    private final BeneficiaryService service;

    @Override
    public String entityType() {
        return AppConstant.APPROVAL_ENTITY_BENEFICIARY;
    }

    /** Its own permission, not {@code BENEFICIARIES_MANAGE}: held by one person they are not two pairs of eyes. */
    @Override
    public String decidePermission() {
        return "BENEFICIARIES_APPROVE";
    }

    /** The decider belongs to the organisation that pays, or is the bank's staff. */
    @Override
    public void assertMayDecide(ApprovalWorkflow workflow, UserPrincipal caller) {
        if (caller.isPlatformStaff()) return;
        boolean ownTenant = workflow.getTenantId() != null && workflow.getTenantId().equals(caller.getTenantId());
        boolean ownInstitution = workflow.getInstitutionId() != null
                && workflow.getInstitutionId().equals(caller.getInstitutionId());
        if (ownTenant || ownInstitution) return;
        throw new HodiException("That beneficiary belongs to another organisation.", HttpStatus.FORBIDDEN);
    }

    @Override
    public void onApproved(ApprovalWorkflow workflow) {
        service.applyApproval(workflow.getEntityId(), workflow.getCheckedByUsername());
    }

    @Override
    public void onRefused(ApprovalWorkflow workflow) {
        service.applyRefusal(workflow.getEntityId(), workflow.getDecision(), workflow.getDecisionReason());
    }
}
