package com.hodi.modules.users;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.approvals.ApprovalHandler;
import com.hodi.modules.approvals.ApprovalWorkflow;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * What "approved" means for a user: they can sign in.
 *
 * <p>A staff account is created disabled and put in front of the bank. Everything about who may decide,
 * when, and what gets recorded already exists on the queue; this class supplies only what the workflow
 * cannot know.
 *
 * <h2>Why the bank, and not the organisation that created them</h2>
 *
 * <p>Because the seller sells through the bank. A person given listing rights can change what a buyer is
 * told a home costs and how many are left, and the bank carries that relationship — so the bank decides who
 * gets to. Letting a seller's owner approve their own new staff would make the gate a formality performed by
 * the person it exists to check.
 *
 * <p>The bank's staff are platform staff, so that is the whole test — the same one
 * {@code DevelopmentApprovalHandler} applies, for the same reason.
 *
 * <p>Note what this does <strong>not</strong> relax: {@code ck_approval_maker_checker} is a database CHECK,
 * so the bank user who created an account still cannot be the one who approves it. Another bank user must.
 * On a fresh install with one platform account that is a genuine stop rather than a bug — one person cannot
 * be both pairs of eyes, and the way out is a second platform account, not a weaker rule.
 */
@Component
@RequiredArgsConstructor
public class UserApprovalHandler implements ApprovalHandler {

    private final UserService service;

    @Override
    public String entityType() {
        return AppConstant.APPROVAL_ENTITY_USER;
    }

    /**
     * Its own permission, not {@code USERS_ACTIVATE}.
     *
     * <p>Activating is switching a working account back on; this is deciding whether a person exists on the
     * platform at all. Sharing one code between them would mean every organisation that grants somebody the
     * tidying-up permission has also granted them the gate.
     */
    @Override
    public String decidePermission() {
        return "USERS_APPROVE";
    }

    @Override
    public void assertMayDecide(ApprovalWorkflow workflow, UserPrincipal caller) {
        if (caller.isPlatformStaff()) return;
        throw new HodiException(
                "Only the bank can approve a new user account.", HttpStatus.FORBIDDEN);
    }

    @Override
    public void onApproved(ApprovalWorkflow workflow) {
        service.applyApproval(workflow.getEntityId(), workflow.getCheckedByUsername());
    }

    @Override
    public void onRefused(ApprovalWorkflow workflow) {
        service.applyRefusal(workflow.getEntityId(), workflow.getDecision(),
                workflow.getDecisionReason());
    }
}
