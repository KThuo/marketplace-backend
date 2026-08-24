package com.hodi.modules.partnerships;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.approvals.ApprovalHandler;
import com.hodi.modules.approvals.ApprovalWorkflow;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * What "approved" means for a partnership.
 *
 * <p>The queue records the decision; this makes it real — and it is the same method the partnership screen's
 * own Approve button ends up calling, so there is one path to cross-organisation access rather than two that
 * can drift.
 *
 * <p>The dependency back onto {@code PartnershipService} would be a cycle if {@code ApprovalService} took
 * its handlers at construction; it resolves them per decision instead, which is what makes this direct
 * reference possible and keeps the activation in one place.
 *
 * <p><strong>The organisation rule is here, not in the queue.</strong> The generic rule is "not the person
 * who submitted it". Partnerships need more: the whole side that proposed is barred, because approving is the
 * moment one organisation consents to being read by another, and consent has to come from the party whose
 * data is at stake. Two people at the same seller are not two parties.
 */
@Component
@RequiredArgsConstructor
public class PartnershipApprovalHandler implements ApprovalHandler {

    private final PartnershipRepository partnerships;
    private final PartnershipService service;

    @Override
    public String entityType() {
        return AppConstant.APPROVAL_ENTITY_PARTNERSHIP;
    }

    @Override
    public String decidePermission() {
        return "PARTNERSHIPS_APPROVE";
    }

    @Override
    public void assertMayDecide(ApprovalWorkflow workflow, UserPrincipal caller) {
        if (caller.isPlatformStaff()) return;
        Partnership partnership = partnerships.findById(workflow.getEntityId())
                .orElseThrow(() -> new HodiException("That partnership no longer exists.",
                        HttpStatus.CONFLICT));

        boolean callerIsSeller = partnership.getTenantId().equals(caller.getTenantId());
        boolean callerIsLender = partnership.getInstitutionId().equals(caller.getInstitutionId());
        String proposedBy = partnership.getRequestedBySide();

        if (callerIsSeller && AppConstant.ACTOR_SELLER.equals(proposedBy)) {
            throw new HodiException("Your organisation proposed this — the institution has to accept it.",
                    HttpStatus.CONFLICT);
        }
        if (callerIsLender && AppConstant.ACTOR_LENDER.equals(proposedBy)) {
            throw new HodiException("Your institution proposed this — the seller has to accept it.",
                    HttpStatus.CONFLICT);
        }
        if (!callerIsSeller && !callerIsLender) {
            throw new HodiException("Your organisation is not a party to this.", HttpStatus.FORBIDDEN);
        }
    }

    @Override
    public void onApproved(ApprovalWorkflow workflow) {
        service.applyApproval(workflow.getEntityId());
    }

    @Override
    public void onRefused(ApprovalWorkflow workflow) {
        // A rejected or sent-back proposal leaves no active partnership and no live proposal: the row goes
        // back to being an arrangement nobody has agreed to, which either side may propose again.
        service.applyRefusal(workflow.getEntityId(), workflow.getDecisionReason());
    }
}
