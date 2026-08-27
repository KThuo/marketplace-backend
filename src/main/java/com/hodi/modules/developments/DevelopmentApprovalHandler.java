package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.approvals.ApprovalHandler;
import com.hodi.modules.approvals.ApprovalWorkflow;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * What "approved" means for a development: it goes on the marketplace.
 *
 * <p>The third entity type to use the queue, and shorter than either of the others — everything about who may
 * decide, when, and what gets recorded already exists. This class supplies only the part the workflow cannot
 * know.
 *
 * <h2>Who may decide, and the case that makes it different</h2>
 *
 * <p>A listing has one organisation behind it, so its second pair of eyes comes from inside that organisation.
 * A development may have two: the bank that owns the record and the seller marketing the units. Either may
 * approve, because either is a party to publishing it — and a project whose owner and marketer are different
 * organisations is exactly the arrangement a bank asked for.
 *
 * <p>What is not accepted is a third party. A collaborator granted progress rights may post photographs and
 * may not put a project on the marketplace; those are different acts and {@code DevelopmentVisibility} keeps
 * them apart, so this handler asks for ownership rather than access.
 */
@Component
@RequiredArgsConstructor
public class DevelopmentApprovalHandler implements ApprovalHandler {

    private final DevelopmentRepository developments;
    private final DevelopmentService service;

    @Override
    public String entityType() {
        return AppConstant.APPROVAL_ENTITY_DEVELOPMENT;
    }

    @Override
    public String decidePermission() {
        return "DEVELOPMENTS_APPROVE";
    }

    @Override
    public void assertMayDecide(ApprovalWorkflow workflow, UserPrincipal caller) {
        if (caller.isPlatformStaff()) return;
        Development development = developments.findById(workflow.getEntityId())
                .orElseThrow(() -> new HodiException("That development no longer exists.",
                        HttpStatus.CONFLICT));

        boolean owns = caller.getInstitutionId() != null
                && caller.getInstitutionId().equals(development.getInstitutionId());
        boolean isOwningTenant = caller.getTenantId() != null
                && caller.getTenantId().equals(development.getTenantId());
        boolean isMarketing = caller.getTenantId() != null
                && caller.getTenantId().equals(development.getSellingTenantId());

        if (!owns && !isOwningTenant && !isMarketing) {
            throw new HodiException("That development belongs to another organisation.",
                    HttpStatus.FORBIDDEN);
        }
    }

    @Override
    public void onApproved(ApprovalWorkflow workflow) {
        service.applyPublication(workflow.getEntityId());
    }

    @Override
    public void onRefused(ApprovalWorkflow workflow) {
        service.applyRefusal(workflow.getEntityId(), workflow.getDecisionReason());
    }
}
