package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.developments.DevelopmentPhaseDtos.PhaseResponse;
import com.hodi.modules.developments.DevelopmentPhaseDtos.SavePhaseRequest;
import com.hodi.modules.media.MediaAssetRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * A development's phases — the build divided into stages with dates and money against each.
 *
 * <h2>The rule a database cannot enforce</h2>
 *
 * <p>When every phase of a development carries a weight, the weights have to sum to 100, and no CHECK can say
 * so: a constraint sees one row. So it is checked here on every save, and refused as a field error on
 * {@code weightPct} rather than silently normalised — scaling somebody's arithmetic mistake into a plausible
 * total is how it survives to the next screen and out to a lender.
 *
 * <p>The refusal is deliberately not fatal to the shape: weights are optional, and a development where only
 * some phases carry one simply derives its percentage a different way ({@link InventoryMaths} tries budget,
 * then unit counts, then a plain mean). What is refused is the half-finished weighting, because that is the
 * state that looks precise and is not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevelopmentPhaseService {

    private final DevelopmentPhaseRepository repository;
    private final DevelopmentUnitRepository units;
    private final DevelopmentRepository developments;
    private final MediaAssetRepository media;
    private final DevelopmentVisibility visibility;
    private final DevelopmentInventoryService inventory;
    private final AuditService audit;

    @Transactional(readOnly = true)
    public List<PhaseResponse> list(String developmentHashId) {
        Development development = requireVisible(developmentHashId);
        return repository.findForDevelopment(development.getId()).stream().map(this::toResponse).toList();
    }

    @Transactional
    public PhaseResponse create(String developmentHashId, SavePhaseRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayManage(development, caller);

        DevelopmentPhase phase = DevelopmentPhase.builder()
                .developmentId(development.getId())
                .reference(nextReference())
                .currency(development.getCurrency())
                .build();
        apply(phase, request);
        phase.setSequenceNo(request.sequenceNo() != null ? request.sequenceNo() : nextSequence(development));
        assertSequenceFree(development.getId(), phase.getSequenceNo(), -1L);
        phase.setCreatedBy(AuthContext.username());

        DevelopmentPhase saved = repository.save(phase);
        assertWeightsWhole(development.getId());
        inventory.recomputeDevelopment(development.getId());

        audit.record(AppConstant.ACTION_CREATE, "DevelopmentPhase", saved.getId(), null, snapshot(saved));
        return toResponse(saved);
    }

    @Transactional
    public PhaseResponse update(String developmentHashId, String phaseHashId, SavePhaseRequest request) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayManage(development, caller);

        DevelopmentPhase phase = require(development, phaseHashId);
        String before = snapshot(phase);
        apply(phase, request);
        if (request.sequenceNo() != null) {
            assertSequenceFree(development.getId(), request.sequenceNo(), phase.getId());
            phase.setSequenceNo(request.sequenceNo());
        }
        phase.setStatus(AppConstant.STATUS_EDITED);
        phase.setStatusFlag(AppConstant.FLAG_EDITED);
        phase.setUpdatedBy(AuthContext.username());

        DevelopmentPhase saved = repository.save(phase);
        assertWeightsWhole(development.getId());
        inventory.recomputeDevelopment(development.getId());

        audit.record(AppConstant.ACTION_UPDATE, "DevelopmentPhase", saved.getId(), before, snapshot(saved));
        return toResponse(saved);
    }

    /**
     * Archives a phase.
     *
     * <p>Refused while units are assigned to it. A unit pointing at an archived phase is a unit whose stage
     * nobody can name, and the composite foreign key would still hold it — the database would let this happen,
     * so the service is where it is stopped.
     */
    @Transactional
    public void archive(String developmentHashId, String phaseHashId) {
        UserPrincipal caller = AuthContext.require();
        Development development = requireVisible(developmentHashId);
        visibility.assertMayManage(development, caller);

        DevelopmentPhase phase = require(development, phaseHashId);
        long assigned = units.countForPhase(phase.getId());
        if (assigned > 0) {
            throw new HodiException(
                    "That phase still has " + assigned + " unit" + (assigned == 1 ? "" : "s")
                            + " assigned to it. Move them to another phase first.",
                    HttpStatus.CONFLICT);
        }

        String before = snapshot(phase);
        phase.setStatus(AppConstant.STATUS_DELETED);
        phase.setStatusFlag(AppConstant.FLAG_DELETED);
        phase.setUpdatedBy(AuthContext.username());
        DevelopmentPhase saved = repository.save(phase);

        // Weights are only checked on save, not on archive: removing a phase from a weighted set legitimately
        // leaves the rest not summing to 100, and refusing to let somebody delete a phase until they have
        // fixed the arithmetic first is a trap rather than a safeguard. The next save will ask.
        inventory.recomputeDevelopment(development.getId());
        audit.record(AppConstant.ACTION_DELETE, "DevelopmentPhase", saved.getId(), before, snapshot(saved));
    }

    // ── internals ─────────────────────────────────────────────────────────────

    /**
     * Refuses a weighting that describes no whole.
     *
     * <p>Only when every live phase carries one. A partial weighting is allowed to exist because it is
     * meaningless rather than wrong — the derivation ignores it and says which rule it used instead.
     */
    private void assertWeightsWhole(Long developmentId) {
        List<DevelopmentPhase> live = repository.findForDevelopment(developmentId);
        if (live.isEmpty()) return;
        if (!live.stream().allMatch(p -> p.getWeightPct() != null && p.getWeightPct() > 0)) return;

        int sum = live.stream().mapToInt(DevelopmentPhase::getWeightPct).sum();
        if (sum != 100) {
            throw new HodiException(
                    "The phase weights add up to " + sum + "%. They have to make 100% between them, "
                            + "or leave them blank and the progress figure will be worked out another way.",
                    HttpStatus.BAD_REQUEST);
        }
    }

    private void assertSequenceFree(Long developmentId, short sequenceNo, Long exceptId) {
        if (repository.countAtSequence(developmentId, sequenceNo, exceptId) > 0) {
            throw new HodiException("Another phase is already number " + sequenceNo + ".",
                    HttpStatus.CONFLICT);
        }
    }

    private short nextSequence(Development development) {
        List<DevelopmentPhase> live = repository.findForDevelopment(development.getId());
        return (short) (live.stream().mapToInt(DevelopmentPhase::getSequenceNo).max().orElse(0) + 1);
    }

    private void apply(DevelopmentPhase phase, SavePhaseRequest request) {
        phase.setName(request.name().trim());
        phase.setDescription(blankToNull(request.description()));
        phase.setPlannedStartOn(request.plannedStartOn());
        phase.setPlannedCompletionOn(request.plannedCompletionOn());
        phase.setRevisedCompletionOn(request.revisedCompletionOn());
        phase.setActualStartOn(request.actualStartOn());
        phase.setActualCompletionOn(request.actualCompletionOn());
        phase.setBudgetAmount(request.budgetAmount());
        phase.setPlannedSpend(request.plannedSpend());
        phase.setCommittedAmount(request.committedAmount());
        phase.setSpentAmount(request.spentAmount());
        phase.setWeightPct(request.weightPct());
        phase.setMilestoneCode(blankToNull(request.milestoneCode()));
        phase.setPlannedUnitCount(request.plannedUnitCount());

        if (request.percentComplete() != null) phase.setPercentComplete(request.percentComplete());

        /*
         * A phase at 100% is a phase that finished, and the database refuses the pair unless they agree.
         *
         * Rather than reject the save, the two are reconciled here in the direction that is almost always
         * meant: somebody typing 100% has finished the phase today, and somebody entering a completion date
         * has finished it. The alternative is a validation error about a second field the person did not think
         * they were filling in.
         */
        if (phase.getPercentComplete() == 100 && phase.getActualCompletionOn() == null) {
            phase.setActualCompletionOn(java.time.LocalDate.now());
        }
        if (phase.getActualCompletionOn() != null && phase.getPercentComplete() != 100) {
            phase.setPercentComplete((short) 100);
        }
    }

    private Development requireVisible(String developmentHashId) {
        Development development = developments.findById(HashIdUtil.decodeId(developmentHashId))
                .orElseThrow(() -> new ResourceNotFoundException("Development", developmentHashId));
        if (!visibility.mayRead(development, AuthContext.require())) {
            throw new ResourceNotFoundException("Development", developmentHashId);
        }
        return development;
    }

    /** A phase, checked to belong to the development in the path rather than merely to exist. */
    private DevelopmentPhase require(Development development, String phaseHashId) {
        DevelopmentPhase phase = repository.findById(HashIdUtil.decodeId(phaseHashId))
                .orElseThrow(() -> new ResourceNotFoundException("Phase", phaseHashId));
        if (!phase.getDevelopmentId().equals(development.getId())) {
            // Not-found rather than forbidden: the caller has no business knowing it exists elsewhere.
            throw new ResourceNotFoundException("Phase", phaseHashId);
        }
        return phase;
    }

    private String nextReference() {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = RrnGenerator.generate("PH");
            if (!repository.existsByReference(candidate)) return candidate;
        }
        throw new HodiException("Could not allocate a phase reference. Try again.", HttpStatus.CONFLICT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private PhaseResponse toResponse(DevelopmentPhase p) {
        return new PhaseResponse(
                HashIdUtil.encodeId(p.getId()),
                p.getReference(),
                p.getName(),
                p.getDescription(),
                p.getSequenceNo(),
                p.getPlannedStartOn(),
                p.getPlannedCompletionOn(),
                p.getRevisedCompletionOn(),
                p.getActualStartOn(),
                p.getActualCompletionOn(),
                p.getBudgetAmount(),
                p.getPlannedSpend(),
                p.getCommittedAmount(),
                p.getSpentAmount(),
                p.getCurrency(),
                p.getWeightPct(),
                p.getPercentComplete(),
                p.getMilestoneCode(),
                p.getPlannedUnitCount(),
                p.slippageDays(),
                (int) units.countForPhase(p.getId()),
                (int) media.countForOwner(AppConstant.MEDIA_OWNER_DEVELOPMENT_PHASE, p.getId()));
    }

    private String snapshot(DevelopmentPhase p) {
        return "name=" + p.getName() + ", seq=" + p.getSequenceNo()
                + ", percent=" + p.getPercentComplete() + ", weight=" + p.getWeightPct()
                + ", planned=" + p.getPlannedCompletionOn() + ", revised=" + p.getRevisedCompletionOn()
                + ", actual=" + p.getActualCompletionOn();
    }
}
