package com.hodi.modules.properties;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.approvals.ApprovalHandler;
import com.hodi.modules.approvals.ApprovalWorkflow;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * What "approved" means for a listing: it goes on the marketplace.
 *
 * <p>The second entity type to use the queue, and the one that shows the shape was worth building — this
 * class is short because everything about who may decide, when, and what is recorded already exists.
 *
 * <p>The only domain rule it adds is that the decider must belong to the seller whose listing it is, or be the
 * platform. Unlike a partnership there is no "other side": a listing has one organisation behind it and the
 * second pair of eyes comes from inside it. The person who submitted it is still barred, by the workflow's
 * rule and by the database's.
 */
@Component
@RequiredArgsConstructor
public class PropertyApprovalHandler implements ApprovalHandler {

    private final PropertyRepository properties;
    private final PropertyService service;

    @Override
    public String entityType() {
        return AppConstant.APPROVAL_ENTITY_PROPERTY;
    }

    @Override
    public String decidePermission() {
        return "PROPERTIES_APPROVE";
    }

    @Override
    public void assertMayDecide(ApprovalWorkflow workflow, UserPrincipal caller) {
        if (caller.isPlatformStaff()) return;
        Property property = properties.findById(workflow.getEntityId())
                .orElseThrow(() -> new HodiException("That listing no longer exists.",
                        HttpStatus.CONFLICT));
        if (!property.getTenantId().equals(caller.getTenantId())) {
            throw new HodiException("That listing belongs to another organisation.",
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
