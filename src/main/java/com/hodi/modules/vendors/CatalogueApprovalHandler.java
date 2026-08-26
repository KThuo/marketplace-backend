package com.hodi.modules.vendors;

import com.hodi.modules.approvals.ApprovalHandler;
import com.hodi.modules.approvals.ApprovalWorkflow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * What "approved" means for a catalogue item: it goes on the public directory (M10).
 *
 * <p>The third entity type to use the queue, and shorter than the second — everything about who may decide,
 * when, and what is recorded already exists.
 *
 * <p>Unlike a listing there is no {@code assertMayDecide} override. A listing's second pair of eyes comes
 * from inside the seller organisation; a vendor organisation is one person, so there is no colleague to
 * check them and the platform is the checker. {@code CATALOGUE_APPROVE} is platform-only, which says exactly
 * that — and the workflow's own rule still bars whoever submitted it.
 */
@Component
@RequiredArgsConstructor
public class CatalogueApprovalHandler implements ApprovalHandler {

    private final CatalogueService service;

    @Override
    public String entityType() {
        return VendorState.APPROVAL_ENTITY_CATALOGUE_ITEM;
    }

    @Override
    public String decidePermission() {
        return "CATALOGUE_APPROVE";
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
