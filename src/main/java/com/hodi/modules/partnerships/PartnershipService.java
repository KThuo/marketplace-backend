package com.hodi.modules.partnerships;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.dto.PagedDataRequest;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.approvals.ApprovalService;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.institutions.LendingInstitution;
import com.hodi.modules.institutions.LendingInstitutionRepository;
import com.hodi.modules.tenants.Tenant;
import com.hodi.modules.tenants.TenantRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Partnerships between seller organisations and lending institutions.
 *
 * <p><strong>This service hands out cross-organisation read access.</strong> Approving a row here is the act
 * that lets one organisation's staff see another's portfolio — {@code PrincipalFactory} reads the result on
 * every principal build — so the rules are worth stating rather than inferring:
 *
 * <ul>
 *   <li><strong>Either side may propose; the other side approves.</strong> A proposal grants nothing. The
 *       approval has to come from the party whose data is at stake, or from the platform.
 *   <li><strong>A seller approving means "you may see my portfolio".</strong> That is why a seller-initiated
 *       proposal is not auto-approved: the seller has already consented, but the lender has not agreed to take
 *       them on, and an arrangement one side has not accepted is not an arrangement.
 *   <li><strong>Revocation is immediate.</strong> Nothing is cached, so the lender's staff lose access on
 *       their next request rather than at the end of an idle window.
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PartnershipService {

    private final PartnershipRepository repository;
    private final TenantRepository tenants;
    private final LendingInstitutionRepository institutions;
    private final AuditService audit;
    private final ApprovalService approvals;

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record PartnershipResponse(
            String id,
            String tenantId, String tenantName, String tenantRef,
            String institutionId, String institutionName, String institutionRef,
            String portfolioScope,
            String state,
            String requestedBySide,
            OffsetDateTime requestedAt,
            OffsetDateTime approvedAt,
            OffsetDateTime revokedAt,
            String revokeReason,
            /** Whether the caller is the side that still has to answer this proposal. */
            boolean awaitingMyApproval,
            Integer status, String statusFlag) {}

    /**
     * @param note optional word to whoever has to decide it — "we met at the expo last week". Lands on the
     *             approval request rather than on the partnership: it is about this proposal, not about the
     *             arrangement, and a re-proposal after a refusal should not inherit the old one's covering
     *             note.
     */
    public record ProposeRequest(String tenantId, String institutionId, String portfolioScope,
                                 String note) {}

    // ── reads ─────────────────────────────────────────────────────────────────

    /**
     * The partnerships the caller is a party to.
     *
     * <p>Deliberately not {@code TenantScope.restrict("tenantId")}. That would be almost right and wrong in
     * one direction that matters: a lender's visible-tenant set is <em>derived from this table</em>, so using
     * it to filter this table would hide every proposal that has not been approved yet — which is exactly the
     * list a lender admin needs in order to approve one. Circular, and it would present as "requests
     * disappear until somebody else accepts them".
     */
    @Transactional(readOnly = true)
    public PagedResponse<PartnershipResponse> list(PagedDataRequest request) {
        UserPrincipal caller = AuthContext.require();
        Specification<Partnership> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                partyTo(caller));
        var page = repository.findAll(spec,
                request.toPageable(Sort.by(Sort.Direction.DESC, "createdAt")));
        return PagedResponse.from(page, p -> toResponse(p, caller));
    }

    /** Rows the caller is one of the two parties to. Platform staff see all of them. */
    private Specification<Partnership> partyTo(UserPrincipal caller) {
        if (caller.isPlatformStaff()) return null;
        return (root, query, cb) -> {
            List<Predicate> ors = new ArrayList<>();
            if (caller.getTenantId() != null) {
                ors.add(cb.equal(root.get("tenantId"), caller.getTenantId()));
            }
            if (caller.getInstitutionId() != null) {
                ors.add(cb.equal(root.get("institutionId"), caller.getInstitutionId()));
            }
            // Neither: a buyer, who is party to no partnership and should see none.
            if (ors.isEmpty()) return cb.disjunction();
            return cb.or(ors.toArray(new Predicate[0]));
        };
    }

    // ── writes ────────────────────────────────────────────────────────────────

    /**
     * Proposes a partnership.
     *
     * <p>The caller's own side is derived from their principal and the other side comes from the request — so
     * a seller names a lender, a lender names a seller, and neither can name both. Platform staff may name
     * both, which is how a partnership gets set up on the phone.
     */
    @Transactional
    public PartnershipResponse propose(ProposeRequest request) {
        UserPrincipal caller = AuthContext.require();

        Long tenantId;
        Long institutionId;
        String side;
        if (caller.getTenantId() != null) {
            tenantId = caller.getTenantId();
            institutionId = requireId(request.institutionId(), "Choose a lending institution");
            side = AppConstant.ACTOR_SELLER;
        } else if (caller.getInstitutionId() != null) {
            institutionId = caller.getInstitutionId();
            tenantId = requireId(request.tenantId(), "Choose a seller organisation");
            side = AppConstant.ACTOR_LENDER;
        } else if (caller.isPlatformStaff()) {
            tenantId = requireId(request.tenantId(), "Choose a seller organisation");
            institutionId = requireId(request.institutionId(), "Choose a lending institution");
            side = "PLATFORM";
        } else {
            throw new HodiException("Your account cannot propose a partnership.", HttpStatus.FORBIDDEN);
        }

        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Organisation", tenantId));
        LendingInstitution institution = institutions.findById(institutionId)
                .orElseThrow(() -> new ResourceNotFoundException("Institution", institutionId));

        if (!tenant.isTradeable()) {
            throw new HodiException("That organisation is not currently active.", HttpStatus.CONFLICT);
        }
        if (!AppConstant.isLive(institution.getStatus())) {
            throw new HodiException("That institution is not currently active.", HttpStatus.CONFLICT);
        }

        /*
         * One row per pair, ever — the table has a UNIQUE on (tenant_id, institution_id).
         *
         * So a re-proposal after a revocation reuses the row rather than inserting a second one. That keeps
         * the history in one place ("proposed, approved, revoked, proposed again") instead of scattering it
         * across rows whose order has to be reconstructed, and it means the unique constraint can stay,
         * which is what stops two concurrent proposals producing two live grants.
         */
        Partnership partnership = repository.findByTenantIdAndInstitutionId(tenantId, institutionId)
                .orElseGet(() -> Partnership.builder()
                        .tenantId(tenantId)
                        .institutionId(institutionId)
                        .createdBy(AuthContext.username())
                        .build());

        /*
         * Guarded on getId() — the state checks apply only to a row that already exists.
         *
         * A freshly built Partnership has status = 1 from its builder default and null approval and
         * revocation stamps, which is precisely what isPending() tests for. So without this, every first
         * proposal between two organisations was rejected as "already waiting for a decision" — the object
         * describing the proposal being made was mistaken for one already made.
         */
        if (partnership.getId() != null) {
            if (partnership.isActive()) {
                throw new HodiException("Those two are already partnered.", HttpStatus.CONFLICT);
            }
            if (partnership.isPending()) {
                throw new HodiException("A proposal between those two is already waiting for a decision.",
                        HttpStatus.CONFLICT);
            }
        }

        partnership.setTenantName(tenant.getName());
        partnership.setInstitutionName(institution.getName());
        partnership.setPortfolioScope(normaliseScope(request.portfolioScope()));
        partnership.setRequestedBySide(side);
        partnership.setRequestedByUserId(caller.getUserId());
        partnership.setRequestedAt(OffsetDateTime.now());
        // Cleared, so re-proposing after a revocation is a fresh proposal rather than a revoked one that
        // suddenly reads as pending.
        partnership.setApprovedAt(null);
        partnership.setApprovedByUserId(null);
        partnership.setRevokedAt(null);
        partnership.setRevokedByUserId(null);
        partnership.setRevokeReason(null);
        partnership.setStatus(AppConstant.STATUS_ACTIVE);
        partnership.setStatusFlag(AppConstant.FLAG_ACTIVE);
        partnership.setUpdatedBy(AuthContext.username());

        Partnership saved = repository.save(partnership);

        /*
         * The queue entry is raised here, in the same transaction, so a proposal and the decision it is
         * waiting on cannot exist without each other.
         *
         * It lands in the queue of the side that did NOT propose — they are the ones who owe an answer — and
         * the platform sees every queue, which is what keeps a one-person organisation from being stuck.
         */
        approvals.submit(
                AppConstant.APPROVAL_ENTITY_PARTNERSHIP,
                saved.getId(),
                AppConstant.APPROVAL_ACTION_ACTIVATE,
                AppConstant.ACTOR_LENDER.equals(side) ? saved.getTenantId() : null,
                AppConstant.ACTOR_SELLER.equals(side) ? saved.getInstitutionId() : null,
                saved.getTenantName() + " ↔ " + saved.getInstitutionName(),
                request.note());

        audit.record(AppConstant.ACTION_REQUEST, "Partnership", saved.getId(), null, snapshot(saved));
        log.info("Partnership proposed between seller {} and institution {} by {}",
                tenant.getSlug(), institution.getSlug(), side);
        return toResponse(saved, caller);
    }

    /**
     * Approves a proposal from the partnership screen.
     *
     * <p>Delegates to the approval workflow rather than stamping the row directly, so this button and the
     * approvals queue are the same act: one Maker/Checker record, one set of guards, one audit trail. The
     * checks that used to live here are now the handler's ({@link PartnershipApprovalHandler}) — the side
     * rule — and the workflow's — the user rule and the permission.
     */
    @Transactional
    public PartnershipResponse approve(String hashId) {
        UserPrincipal caller = AuthContext.require();
        Partnership partnership = requireParty(hashId, caller);

        if (partnership.isActive()) {
            throw new HodiException("That partnership is already active.", HttpStatus.CONFLICT);
        }
        if (!partnership.isPending()) {
            throw new HodiException("There is no live proposal to approve.", HttpStatus.CONFLICT);
        }

        approvals.decideFor(AppConstant.APPROVAL_ENTITY_PARTNERSHIP, partnership.getId(),
                AppConstant.APPROVAL_ACTION_ACTIVATE,
                new ApprovalService.DecisionRequest(AppConstant.APPROVAL_APPROVED, null));

        return toResponse(repository.findById(partnership.getId()).orElseThrow(), caller);
    }

    /**
     * Stamps the partnership active. Called only by the approval handler, inside the deciding transaction.
     *
     * <p>Not public API and not guarded: everything that decides whether this may happen has already run by
     * the time it is reached. Splitting it out is what lets the queue and the partnership screen share one
     * implementation of "what approval means".
     */
    @Transactional
    public void applyApproval(Long partnershipId) {
        Partnership partnership = repository.findById(partnershipId)
                .orElseThrow(() -> new ResourceNotFoundException("Partnership", partnershipId));
        String before = snapshot(partnership);
        partnership.setApprovedAt(OffsetDateTime.now());
        partnership.setApprovedByUserId(AuthContext.userId());
        partnership.setUpdatedBy(AuthContext.username());
        Partnership saved = repository.save(partnership);

        audit.record(AppConstant.ACTION_APPROVE, "Partnership", saved.getId(), before, snapshot(saved));
        log.info("Partnership {} approved — institution {} can now see seller {}",
                saved.getId(), saved.getInstitutionName(), saved.getTenantName());
    }

    /**
     * Clears a proposal that was rejected or sent back.
     *
     * <p>The row survives — it carries the history of two organisations having talked — but it stops being a
     * live proposal, so either side may propose again without tripping the "already waiting" guard. The
     * reason is kept where a re-proposer will see it.
     */
    @Transactional
    public void applyRefusal(Long partnershipId, String reason) {
        Partnership partnership = repository.findById(partnershipId)
                .orElseThrow(() -> new ResourceNotFoundException("Partnership", partnershipId));
        String before = snapshot(partnership);
        partnership.setRequestedAt(null);
        partnership.setRevokedAt(OffsetDateTime.now());
        partnership.setRevokedByUserId(AuthContext.userId());
        partnership.setRevokeReason(reason == null ? "Not approved" : reason);
        partnership.setUpdatedBy(AuthContext.username());
        Partnership saved = repository.save(partnership);

        audit.record(AppConstant.ACTION_REVOKE, "Partnership", saved.getId(), before, snapshot(saved));
        log.info("Partnership {} refused — {}", saved.getId(), saved.getRevokeReason());
    }

    /**
     * Ends an active partnership.
     *
     * <p>Either side may revoke, unilaterally and without the other's agreement. That asymmetry with approval
     * is deliberate: consent to being read has to be withdrawable by the party being read, and a lender that
     * no longer wants the relationship should not need permission to leave it.
     *
     * <p>Takes effect immediately. The visible-tenant set is resolved per request from this table, so the
     * lender's staff lose access on their next call.
     */
    @Transactional
    public PartnershipResponse revoke(String hashId, String reason) {
        UserPrincipal caller = AuthContext.require();
        Partnership partnership = requireParty(hashId, caller);

        if (partnership.getRevokedAt() != null) {
            throw new HodiException("That partnership has already been ended.", HttpStatus.CONFLICT);
        }

        String before = snapshot(partnership);
        partnership.setRevokedAt(OffsetDateTime.now());
        partnership.setRevokedByUserId(caller.getUserId());
        partnership.setRevokeReason(reason);
        partnership.setUpdatedBy(AuthContext.username());
        Partnership saved = repository.save(partnership);

        audit.record(AppConstant.ACTION_REVOKE, "Partnership", saved.getId(), before, snapshot(saved));
        log.info("Partnership {} revoked by {} — institution {} can no longer see seller {}",
                saved.getId(), AuthContext.username(), saved.getInstitutionName(), saved.getTenantName());
        return toResponse(saved, caller);
    }

    // ── guards ────────────────────────────────────────────────────────────────

    private Partnership requireParty(String hashId, UserPrincipal caller) {
        Partnership partnership = repository.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Partnership", hashId));
        if (caller.isPlatformStaff()) return partnership;

        boolean party = partnership.getTenantId().equals(caller.getTenantId())
                || partnership.getInstitutionId().equals(caller.getInstitutionId());
        if (!party) {
            // Not-found rather than forbidden: whether two other organisations are talking is not the
            // caller's business to confirm.
            throw new ResourceNotFoundException("Partnership", hashId);
        }
        return partnership;
    }

    /*
     * The proposer-cannot-approve rule used to live here as assertMayApprove. It is now
     * PartnershipApprovalHandler.assertMayDecide, so that the approvals queue and this screen enforce the
     * same thing — and so the user-level half of it (a colleague of the submitter is still a different
     * person, but the submitter is not) is the workflow's database CHECK rather than something this class
     * remembers to ask.
     */

    private static Long requireId(String hashId, String message) {
        Long id = HashIdUtil.decodeId(hashId);
        if (id == null) throw new HodiException(message, HttpStatus.BAD_REQUEST);
        return id;
    }

    /**
     * Only {@code FULL} is accepted for now.
     *
     * <p>{@code SELECTED} exists in the column and the constants so the eventual per-listing join is additive,
     * but accepting it here would create grants whose meaning no code implements — every read would treat a
     * SELECTED partnership as if it were FULL, which is the wrong direction to be wrong in.
     */
    private static String normaliseScope(String requested) {
        if (requested == null || requested.isBlank()) return AppConstant.PORTFOLIO_FULL;
        String scope = requested.trim().toUpperCase();
        if (AppConstant.PORTFOLIO_SELECTED.equals(scope)) {
            throw new HodiException(
                    "Partial portfolio sharing is not available yet — this partnership covers the whole "
                            + "portfolio.", HttpStatus.BAD_REQUEST);
        }
        if (!AppConstant.PORTFOLIO_FULL.equals(scope)) {
            throw new HodiException("Unknown portfolio scope: " + requested, HttpStatus.BAD_REQUEST);
        }
        return AppConstant.PORTFOLIO_FULL;
    }

    // ── mapping ───────────────────────────────────────────────────────────────

    private PartnershipResponse toResponse(Partnership p, UserPrincipal caller) {
        return new PartnershipResponse(
                HashIdUtil.encodeId(p.getId()),
                HashIdUtil.encodeId(p.getTenantId()),
                p.getTenantName(),
                tenants.findById(p.getTenantId()).map(Tenant::getTenantRef).orElse(null),
                HashIdUtil.encodeId(p.getInstitutionId()),
                p.getInstitutionName(),
                institutions.findById(p.getInstitutionId())
                        .map(LendingInstitution::getInstitutionRef).orElse(null),
                p.getPortfolioScope(),
                state(p),
                p.getRequestedBySide(),
                p.getRequestedAt(),
                p.getApprovedAt(),
                p.getRevokedAt(),
                p.getRevokeReason(),
                awaitingApprovalFrom(p, caller),
                p.getStatus(),
                p.getStatusFlag());
    }

    /** One word for the row's position in its lifecycle, so the UI does not derive it from three nulls. */
    private static String state(Partnership p) {
        if (p.getRevokedAt() != null) return "REVOKED";
        if (p.getApprovedAt() != null) return "ACTIVE";
        return "PENDING";
    }

    /**
     * Whether this caller is the one holding it up — drives the dashboard's action card and the list's badge.
     *
     * <p>Computed per caller rather than stored, because the answer differs by who is looking at the same row.
     */
    private static boolean awaitingApprovalFrom(Partnership p, UserPrincipal caller) {
        if (!p.isPending()) return false;
        if (caller.isPlatformStaff()) return true;
        boolean callerIsSeller = p.getTenantId().equals(caller.getTenantId());
        boolean callerIsLender = p.getInstitutionId().equals(caller.getInstitutionId());
        if (callerIsSeller) return !AppConstant.ACTOR_SELLER.equals(p.getRequestedBySide());
        if (callerIsLender) return !AppConstant.ACTOR_LENDER.equals(p.getRequestedBySide());
        return false;
    }

    private static String snapshot(Partnership p) {
        return ("{\"tenant\":\"%s\",\"institution\":\"%s\",\"scope\":\"%s\",\"state\":\"%s\","
                + "\"requestedBy\":\"%s\"}")
                .formatted(p.getTenantName(), p.getInstitutionName(), p.getPortfolioScope(),
                        state(p), p.getRequestedBySide());
    }
}
