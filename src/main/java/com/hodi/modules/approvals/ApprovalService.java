package com.hodi.modules.approvals;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Maker/Checker: one queue, one rule, every module.
 *
 * <h2>The rule</h2>
 *
 * <p>Whoever submitted a request may not decide it. Enforced twice on purpose — a service guard so the person
 * doing it gets a sentence explaining why, and a database CHECK so it is still true in code nobody has
 * written yet. It is not configurable: a segregation of duties that can be switched off is not one, and where
 * a team is genuinely one person the answer is to let the platform decide rather than to let that person
 * decide twice.
 *
 * <h2>The queue is a view; the authority stays with the module</h2>
 *
 * <p>Deciding requires the <em>domain's</em> permission — {@code PARTNERSHIPS_APPROVE} for a partnership —
 * declared by that module's {@link ApprovalHandler}. There is deliberately no "may approve anything"
 * permission: it would be a way around every module's own gate, granted from one screen.
 *
 * <p>{@code APPROVALS_VIEW} therefore grants sight of the queue and nothing else. Somebody can watch what is
 * waiting without being able to move any of it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final ApprovalWorkflowRepository repository;
    private final AuditService audit;

    /**
     * Handlers, resolved when a decision is made rather than when this bean is built.
     *
     * <p>An {@code ObjectProvider} rather than a plain {@code List} because the dependency genuinely is a
     * cycle: a module submits to this service, and this service applies decisions through that module. Taking
     * the handlers at construction time made it a cycle Spring refuses at startup. Deferring the lookup to
     * the moment a decision is made is the honest fix — the alternatives were a {@code @Lazy} proxy, which
     * hides the cycle rather than resolving it, or duplicating "what approval means" inside each handler,
     * which is the duplication this whole phase exists to remove.
     */
    private final ObjectProvider<ApprovalHandler> handlers;

    /** Handlers indexed by the type they own. Spring collects every implementation on the classpath. */
    private Map<String, ApprovalHandler> byType() {
        return handlers.stream().collect(Collectors.toMap(
                ApprovalHandler::entityType, Function.identity(), (a, b) -> a));
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record ApprovalResponse(
            String id,
            String entityType,
            String entityId,
            String action,
            String subjectLabel,
            String submittedBy,
            OffsetDateTime submittedAt,
            String submissionNote,
            String checkedBy,
            OffsetDateTime checkedAt,
            String decision,
            String decisionReason,
            String state,
            /** Whether <em>this</em> caller may decide it — so the queue does not offer buttons that refuse. */
            boolean decidable,
            /** Why not, when they cannot. Answers "why is this greyed out" without a failed attempt. */
            String blockedReason) {}

    public record DecisionRequest(String decision, String reason) {}

    // ── submitting ────────────────────────────────────────────────────────────

    /**
     * Records that something is waiting for a decision.
     *
     * <p>Called by the owning module as part of whatever created the thing, in the same transaction, so a
     * proposal and its queue entry cannot exist without each other.
     *
     * @param scopeTenantId      the seller whose queue this belongs in, or null
     * @param scopeInstitutionId the institution whose queue this belongs in, or null
     */
    /**
     * Raises a request, or restates the one already waiting.
     *
     * <p>{@link #submit} refuses a duplicate, and that is right when somebody presses Submit twice: the
     * second press is a mistake and should say so. It is wrong when the request is raised as a consequence
     * of something else — a seller changing three prices in a row has made one project stale, not three,
     * and the second change must not fail with "that is already waiting for a decision" over an edit they
     * did not know was raising anything.
     *
     * <p><strong>The latest change is the one the checker sees.</strong> The note is replaced rather than
     * kept, and the clock restarts, because the queue is a list of things to look at now and the earliest
     * reason is the least current description of what is wrong with the project. The submitter moves too:
     * the person whose work is being checked is the one who last touched it, and they are the one the
     * maker/checker rule should block.
     *
     * <p>The gap worth naming: two different people editing the same project between approvals leaves only
     * the later one blocked from deciding. The row holds one submitter, and the database CHECK compares
     * against that one. Rare, and better than the alternative of blocking nobody.
     */
    @Transactional
    public void submitOrRestate(String entityType, Long entityId, String action,
                                Long scopeTenantId, Long scopeInstitutionId,
                                String subjectLabel, String note) {
        UserPrincipal caller = AuthContext.require();
        ApprovalWorkflow waiting = repository.findPending(entityType, entityId, action).orElse(null);
        if (waiting == null) {
            submit(entityType, entityId, action, scopeTenantId, scopeInstitutionId, subjectLabel, note);
            return;
        }

        waiting.setSubmissionNote(note);
        waiting.setSubmittedAt(OffsetDateTime.now());
        waiting.setSubmittedByUserId(caller.getUserId());
        waiting.setSubmittedByUsername(caller.getUsername());
        repository.save(waiting);
        audit.record(AppConstant.AUDIT_APPROVAL_SUBMIT, "ApprovalWorkflow", waiting.getId(), null,
                entityType + "/" + action + " restated — " + note);
    }

    @Transactional
    public ApprovalWorkflow submit(String entityType, Long entityId, String action,
                                   Long scopeTenantId, Long scopeInstitutionId,
                                   String subjectLabel, String note) {
        UserPrincipal caller = AuthContext.require();
        repository.findPending(entityType, entityId, action).ifPresent(existing -> {
            throw new HodiException("That is already waiting for a decision.", HttpStatus.CONFLICT);
        });

        ApprovalWorkflow workflow = repository.save(ApprovalWorkflow.builder()
                .entityType(entityType)
                .entityId(entityId)
                .action(action)
                .tenantId(scopeTenantId)
                .institutionId(scopeInstitutionId)
                .subjectLabel(subjectLabel)
                .submittedByUserId(caller.getUserId())
                .submittedByUsername(caller.getUsername())
                .submittedAt(OffsetDateTime.now())
                .submissionNote(note)
                .state(AppConstant.APPROVAL_PENDING)
                .status(AppConstant.STATUS_ACTIVE)
                .statusFlag(AppConstant.FLAG_ACTIVE)
                .createdBy(AuthContext.username())
                .build());

        audit.record(AppConstant.AUDIT_APPROVAL_SUBMIT, "ApprovalWorkflow", workflow.getId(), null,
                entityType + "/" + action + " — " + subjectLabel);
        return workflow;
    }

    /** The open request for one thing, for a module that needs to find its own. */
    @Transactional(readOnly = true)
    public java.util.Optional<ApprovalWorkflow> pendingFor(String entityType, Long entityId,
                                                           String action) {
        return repository.findPending(entityType, entityId, action);
    }

    // ── deciding ──────────────────────────────────────────────────────────────

    /**
     * Records a decision and applies it.
     *
     * <p>The handler runs inside this transaction: if applying the decision fails, the decision is not
     * recorded either. A queue row reading "approved" beside a partnership that never activated would be the
     * worst of the available outcomes — it is the record everybody would then trust.
     */
    @Transactional
    public ApprovalResponse decide(String hashId, DecisionRequest request) {
        UserPrincipal caller = AuthContext.require();
        return applyDecision(requireVisible(hashId, caller), request, caller);
    }

    /**
     * The same decision, reached from the owning module's own screen rather than from the queue.
     *
     * <p>It skips only the queue-<em>visibility</em> check, because that check answers a different question:
     * whose list this appears in. A seller approving from the partnership screen is not in the bank's queue
     * and never will be, and answering "not found" there told them their own partnership did not exist. Every
     * guard that decides whether they may approve still runs — they simply now hear the real reason, which is
     * that their organisation proposed it.
     */
    @Transactional
    public void decideFor(String entityType, Long entityId, String action, DecisionRequest request) {
        UserPrincipal caller = AuthContext.require();
        ApprovalWorkflow workflow = repository.findPending(entityType, entityId, action)
                .orElseThrow(() -> new HodiException(
                        "There is nothing waiting for a decision on this.", HttpStatus.CONFLICT));
        applyDecision(workflow, request, caller);
    }

    private ApprovalResponse applyDecision(ApprovalWorkflow workflow, DecisionRequest request,
                                           UserPrincipal caller) {
        ApprovalHandler handler = handlerFor(workflow);

        if (!workflow.isPending()) {
            throw new HodiException("That has already been decided.", HttpStatus.CONFLICT);
        }
        assertMayDecide(workflow, caller, handler);

        String decision = normaliseDecision(request.decision());
        if (AppConstant.APPROVAL_REJECTED.equals(decision)
                && (request.reason() == null || request.reason().isBlank())) {
            // A rejection with no reason is a dead end for whoever submitted it: they cannot tell whether to
            // fix something or give up. An approval needs no such explanation.
            throw new HodiException("Say why this is being rejected.", HttpStatus.BAD_REQUEST);
        }

        String before = snapshot(workflow);
        workflow.setState(decision);
        workflow.setDecision(decision);
        workflow.setDecisionReason(blankToNull(request.reason()));
        workflow.setCheckedByUserId(caller.getUserId());
        workflow.setCheckedByUsername(caller.getUsername());
        workflow.setCheckedAt(OffsetDateTime.now());
        workflow.setUpdatedBy(AuthContext.username());
        ApprovalWorkflow saved = repository.save(workflow);

        if (AppConstant.APPROVAL_APPROVED.equals(decision)) {
            handler.onApproved(saved);
        } else {
            handler.onRefused(saved);
        }

        audit.record(AppConstant.AUDIT_APPROVAL_DECIDE, "ApprovalWorkflow", saved.getId(), before,
                snapshot(saved));
        log.info("Approval {} {} by {} ({} {})", saved.getId(), decision, caller.getUsername(),
                saved.getEntityType(), saved.getEntityId());
        return toResponse(saved, caller);
    }

    // ── reading ───────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<ApprovalResponse> list(ApprovalListRequest request) {
        UserPrincipal caller = AuthContext.require();
        Specification<ApprovalWorkflow> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.eq("state", blankToNull(request.getState())),
                SearchSpecs.eq("entityType", blankToNull(request.getEntityType())),
                SearchSpecs.betweenDays("submittedAt", request.getFrom(), request.getTo()),
                visibleTo(caller));
        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "submittedAt")));
        return PagedResponse.from(page, w -> toResponse(w, caller));
    }

    /**
     * Which requests a caller may see.
     *
     * <p>Their own organisation's queue, and nothing else. Not {@code TenantScope}: a bank's visible-tenant
     * set is the sellers they are partnered with, and a partnership grants sight of a portfolio, not of the
     * other organisation's internal approvals. The platform sees everything, as everywhere.
     */
    private Specification<ApprovalWorkflow> visibleTo(UserPrincipal caller) {
        if (caller.isPlatformStaff()) return null;
        if (caller.getTenantId() != null) {
            return (root, query, cb) -> cb.equal(root.get("tenantId"), caller.getTenantId());
        }
        if (caller.getInstitutionId() != null) {
            return (root, query, cb) -> cb.equal(root.get("institutionId"), caller.getInstitutionId());
        }
        return (root, query, cb) -> cb.disjunction();
    }

    /** How many decisions this caller's organisation owes — for the dashboard's nudge. */
    @Transactional(readOnly = true)
    public long pendingForCaller() {
        UserPrincipal caller = AuthContext.require();
        if (caller.isPlatformStaff()) return repository.countPendingForScope(null, null);
        return repository.countPendingForScope(caller.getTenantId(), caller.getInstitutionId());
    }

    // ── guards ────────────────────────────────────────────────────────────────

    private ApprovalWorkflow requireVisible(String hashId, UserPrincipal caller) {
        ApprovalWorkflow workflow = repository.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Approval", hashId));
        if (caller.isPlatformStaff()) return workflow;

        boolean mine = (caller.getTenantId() != null
                        && caller.getTenantId().equals(workflow.getTenantId()))
                || (caller.getInstitutionId() != null
                        && caller.getInstitutionId().equals(workflow.getInstitutionId()));
        if (!mine) {
            throw new ResourceNotFoundException("Approval", hashId);
        }
        return workflow;
    }

    private ApprovalHandler handlerFor(ApprovalWorkflow workflow) {
        ApprovalHandler handler = byType().get(workflow.getEntityType());
        if (handler == null) {
            // A row naming a type nothing implements. Refused rather than recorded as decided while nothing
            // happens to the thing it names.
            throw new HodiException(
                    "This kind of request cannot be decided here yet.", HttpStatus.CONFLICT);
        }
        return handler;
    }

    /** The generic rule, then the module's own. */
    private void assertMayDecide(ApprovalWorkflow workflow, UserPrincipal caller,
                                 ApprovalHandler handler) {
        if (!AuthContext.hasAuthority(handler.decidePermission())) {
            throw new HodiException("You cannot decide this kind of request.", HttpStatus.FORBIDDEN);
        }
        if (caller.getUserId().equals(workflow.getSubmittedByUserId())) {
            throw new HodiException(
                    "You submitted this — somebody else has to decide it.", HttpStatus.CONFLICT);
        }
        handler.assertMayDecide(workflow, caller);
    }

    /**
     * The same questions the guards ask, answered without throwing.
     *
     * <p>So the queue can grey out what this person cannot move and say why, instead of offering a button
     * that fails. One implementation would be neater; two is what lets the reason be shown rather than
     * caught.
     */
    private String blockedReason(ApprovalWorkflow workflow, UserPrincipal caller) {
        if (!workflow.isPending()) return "Already decided";
        ApprovalHandler handler = byType().get(workflow.getEntityType());
        if (handler == null) return "Not decidable here yet";
        if (!AuthContext.hasAuthority(handler.decidePermission())) {
            return "You do not have permission to decide this";
        }
        if (caller.getUserId().equals(workflow.getSubmittedByUserId())) {
            return "You submitted this";
        }
        try {
            handler.assertMayDecide(workflow, caller);
        } catch (HodiException e) {
            return e.getMessage();
        }
        return null;
    }

    private static String normaliseDecision(String raw) {
        String decision = raw == null ? "" : raw.trim().toUpperCase();
        return switch (decision) {
            case AppConstant.APPROVAL_APPROVED,
                 AppConstant.APPROVAL_REJECTED,
                 AppConstant.APPROVAL_SENT_BACK -> decision;
            default -> throw new HodiException(
                    "A decision is approve, reject or send back.", HttpStatus.BAD_REQUEST);
        };
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    private ApprovalResponse toResponse(ApprovalWorkflow w, UserPrincipal caller) {
        String blocked = blockedReason(w, caller);
        return new ApprovalResponse(
                HashIdUtil.encodeId(w.getId()),
                w.getEntityType(),
                HashIdUtil.encodeId(w.getEntityId()),
                w.getAction(),
                w.getSubjectLabel(),
                w.getSubmittedByUsername(),
                w.getSubmittedAt(),
                w.getSubmissionNote(),
                w.getCheckedByUsername(),
                w.getCheckedAt(),
                w.getDecision(),
                w.getDecisionReason(),
                w.getState(),
                blocked == null,
                blocked);
    }

    private static String snapshot(ApprovalWorkflow w) {
        return ("{\"entity\":\"%s#%s\",\"action\":\"%s\",\"state\":\"%s\",\"submittedBy\":\"%s\","
                + "\"checkedBy\":\"%s\"}")
                .formatted(w.getEntityType(), String.valueOf(w.getEntityId()), w.getAction(),
                        w.getState(), String.valueOf(w.getSubmittedByUsername()),
                        String.valueOf(w.getCheckedByUsername()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The queue's own filters on top of the shared paging and search. */
    @lombok.Getter
    @lombok.Setter
    public static class ApprovalListRequest extends PagedDataRequest {
        /** {@code PENDING}, {@code APPROVED}, {@code REJECTED}, {@code SENT_BACK}. */
        private String state;
        private String entityType;
    }
}
