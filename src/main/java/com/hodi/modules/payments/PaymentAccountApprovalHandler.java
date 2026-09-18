package com.hodi.modules.payments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.modules.approvals.ApprovalHandler;
import com.hodi.modules.approvals.ApprovalWorkflow;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * What "approved" means for a payment account: it may start collecting.
 *
 * <h2>Why this is the one place Maker/Checker matters most</h2>
 *
 * <p>Every other approvable thing on the platform decides what somebody is <em>told</em> — a listing's
 * price, a development's details, whether a person may sign in. This one decides where a buyer's deposit
 * physically lands. An account number changed by one digit routes real money to somebody else, and looks
 * like a typo until the money is gone.
 *
 * <p>So an account is written but not live: it sits at {@code STATUS_NEW}, which every "live" query on
 * {@code PaymentAccountRepository} already excludes — {@code status in (1, 2)} — so nothing offers it, no
 * inbound notification matches it, and no payment can name it until a second person says so.
 *
 * <h2>The one-time code does not replace this, and this does not replace the code</h2>
 *
 * <p>They answer different questions. The code asks "is the person typing still holding their own phone" —
 * presence. This asks "did anybody else agree this change should happen" — intent. A stolen session
 * defeats neither on its own.
 */
@Component
@RequiredArgsConstructor
public class PaymentAccountApprovalHandler implements ApprovalHandler {

    private final PaymentAccountService service;

    @Override
    public String entityType() {
        return AppConstant.APPROVAL_ENTITY_PAYMENT_ACCOUNT;
    }

    /**
     * Its own permission, not {@code PAYMENT_TYPES_MANAGE}.
     *
     * <p>The separation is the control. One permission proposes an account, another lets it collect; held
     * by one person they are not two pairs of eyes at all, however the queue is drawn.
     */
    @Override
    public String decidePermission() {
        return "PAYMENTS_ACCOUNT_APPROVE";
    }

    /**
     * The decider belongs to the organisation whose money it is, or is platform staff.
     *
     * <p>The submitter is already barred twice over — by the workflow and by {@code ck_approval_maker_checker},
     * a database CHECK — so this adds only the organisational rule. A seller's account is not another
     * seller's business to wave through.
     */
    @Override
    public void assertMayDecide(ApprovalWorkflow workflow, UserPrincipal caller) {
        if (caller.isPlatformStaff()) return;
        boolean ownTenant = workflow.getTenantId() != null
                && workflow.getTenantId().equals(caller.getTenantId());
        boolean ownInstitution = workflow.getInstitutionId() != null
                && workflow.getInstitutionId().equals(caller.getInstitutionId());
        if (ownTenant || ownInstitution) return;
        throw new HodiException("That account belongs to another organisation.", HttpStatus.FORBIDDEN);
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
