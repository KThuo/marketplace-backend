package com.hodi.modules.campaigns;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.approvals.ApprovalHandler;
import com.hodi.modules.approvals.ApprovalWorkflow;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** A campaign is approved by a second person of the organisation that wrote it — or of the platform, for its own. */
@Component
@RequiredArgsConstructor
public class CampaignApprovalHandler implements ApprovalHandler {

    private final CampaignService service;

    @Override
    public String entityType() {
        return AppConstant.APPROVAL_ENTITY_CAMPAIGN;
    }

    @Override
    public String decidePermission() {
        return "CAMPAIGNS_APPROVE";
    }

    @Override
    public void assertMayDecide(ApprovalWorkflow workflow, UserPrincipal caller) {
        if (caller.isPlatformStaff()) return;
        boolean own = (workflow.getTenantId() != null && workflow.getTenantId().equals(caller.getTenantId()))
                || (workflow.getInstitutionId() != null && workflow.getInstitutionId().equals(caller.getInstitutionId()));
        if (!own) throw new HodiException("A campaign is approved by its own organisation's checker.", HttpStatus.FORBIDDEN);
    }

    @Override
    public void onApproved(ApprovalWorkflow workflow) {
        service.applyApproval(workflow.getEntityId(), workflow.getCheckedByUsername());
    }

    @Override
    public void onRefused(ApprovalWorkflow workflow) {
        service.applyRefusal(workflow.getEntityId(), workflow.getCheckedByUsername(), workflow.getDecisionReason());
    }
}
