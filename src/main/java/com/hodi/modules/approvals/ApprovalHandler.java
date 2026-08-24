package com.hodi.modules.approvals;

import com.hodi.security.principal.UserPrincipal;

/**
 * What a module has to supply for its things to be approvable.
 *
 * <p>The workflow owns the queue, the Maker/Checker rule and the record of who decided what. It owns nothing
 * about the domain — approving a partnership means something only {@code PartnershipService} knows how to do.
 * So a decision runs in two halves: this table records it, and the handler for that entity type applies it.
 *
 * <p><strong>An entity type with no handler cannot be decided.</strong> That is the property that keeps the
 * loose {@code entity_type} reference honest: a row naming a type nothing implements is refused rather than
 * quietly marked approved while nothing happens to the thing it names.
 */
public interface ApprovalHandler {

    /** The {@code entity_type} this handles, e.g. {@code PARTNERSHIP}. */
    String entityType();

    /**
     * The permission a decider must hold.
     *
     * <p>Deliberately the domain's own — {@code PARTNERSHIPS_APPROVE} — rather than a generic
     * "may decide things". A single approve-anything permission would make the queue a way around every
     * module's own gate, which is the opposite of what a Maker/Checker table is for.
     */
    String decidePermission();

    /**
     * Domain rules on top of the generic ones, before a decision is recorded.
     *
     * <p>The generic rule is "not the person who submitted it". A module may need more: for partnerships,
     * the whole <em>organisation</em> that proposed is barred, not just the individual, because consent has
     * to come from the party whose data is at stake.
     *
     * @throws com.hodi.common.exception.HodiException when this caller may not decide this row
     */
    default void assertMayDecide(ApprovalWorkflow workflow, UserPrincipal caller) {}

    /** Make the decision real. Runs in the deciding transaction, so a failure here rolls the decision back. */
    void onApproved(ApprovalWorkflow workflow);

    /** Rejection and send-back both land here; the workflow's {@code decision} says which. */
    default void onRefused(ApprovalWorkflow workflow) {}
}
