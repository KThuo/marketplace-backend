package com.hodi.modules.developments;

import com.hodi.common.AppConstant;
import com.hodi.common.PagedResponse;
import com.hodi.common.exception.HodiException;
import com.hodi.common.exception.ResourceNotFoundException;
import com.hodi.common.util.RrnGenerator;
import com.hodi.common.util.SearchSpecs;
import com.hodi.modules.audit.AuditService;
import com.hodi.modules.developments.DevelopmentFinanceDtos.*;
import com.hodi.modules.kyc.DocumentService;
import com.hodi.modules.kyc.VaultDocument;
import com.hodi.modules.kyc.VaultDocumentRepository;
import com.hodi.security.hashid.HashIdUtil;
import com.hodi.security.principal.AuthContext;
import com.hodi.security.principal.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Where a development's money went, and when it will be finished.
 *
 * <h2>Read from one view</h2>
 *
 * <p>Every figure on the summary comes from {@code v_development_finance}, the same view the report and the
 * comparison table read. Two implementations of "spent" is two spends, and the day they disagreed nobody
 * could say which was right.
 *
 * <h2>Who may write</h2>
 *
 * <p><b>Costs:</b> whoever manages spending on the development, holding {@code DEVELOPMENTS_FINANCE_RECORD}.
 * The development says which side that is ({@code spending_managed_by}): the owning organisation, or the
 * bank for a project it finances or runs. The other side reads every line and records none —
 * {@link DevelopmentVisibility#assertMayManageSpending}. This replaced "anyone who can see the development",
 * which let a bank officer write costs into a developer's own books and a developer write them into a
 * bank's.
 *
 * <p><b>Drawdowns:</b> unchanged — anyone with the permission who can see the development. A drawdown is the
 * lender's facility money arriving, not spending, and who records it is a question for the facility work.
 *
 * <h2>Never edited, only voided</h2>
 *
 * <p>A cost line and a drawdown are voided with a reason and stay, as a payment is. The phase's committed and
 * spent columns are recounted from the lines that remain, by the one writer of every derived figure.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DevelopmentFinanceService {

    private static final String EVIDENCE_FOLDER = "development-finance";

    private final DevelopmentRepository developments;
    private final DevelopmentPhaseRepository phases;
    private final DevelopmentExpenditureRepository expenditures;
    private final FacilityDrawdownRepository drawdowns;
    private final DevelopmentCostCategoryRepository categories;
    private final DevelopmentVisibility visibility;
    private final DevelopmentInventoryService inventory;
    private final DocumentService documents;
    private final VaultDocumentRepository vaultDocuments;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final com.hodi.modules.beneficiaries.BeneficiaryRepository beneficiaries;
    private final com.hodi.modules.disbursements.DisbursementRepository disbursements;

    // ── the summary ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public FinanceSummary summary(String developmentHashId) {
        Development development = requireVisible(developmentHashId);
        Map<String, Object> row = jdbc.queryForMap(
                "select * from v_development_finance where development_id = ?", development.getId());

        BigDecimal budget = development.getBudgetAmount();
        BigDecimal spent = money(row, "spent");
        BigDecimal committed = money(row, "committed");
        BigDecimal plannedToDate = money(row, "planned_to_date");
        BigDecimal facility = development.getFacilityAmount();
        BigDecimal drawn = money(row, "drawn");
        BigDecimal collected = money(row, "collected");

        LocalDate today = LocalDate.now();
        LocalDate target = development.getProjectedCompletionOn();
        LocalDate forecast = development.getActualCompletionOn() != null ? development.getActualCompletionOn()
                : date(row, "forecast_on") != null ? date(row, "forecast_on") : target;

        List<PhaseMoneyRow> phaseRows = phases.findForDevelopment(development.getId()).stream()
                .map(p -> phaseRow(p, today))
                .toList();

        return new FinanceSummary(
                HashIdUtil.encodeId(development.getId()),
                development.getCurrency(),
                budget, money(row, "phase_budget"), money(row, "planned_spend"), plannedToDate,
                committed, spent,
                budget == null ? null : budget.subtract(spent),
                plannedToDate.subtract(spent),
                facility, development.getFacilityReference(),
                drawn, facility == null ? null : facility.subtract(drawn),
                money(row, "contracted"), collected, money(row, "receivable"), money(row, "overdue"),
                ((Number) row.getOrDefault("live_bookings", 0)).intValue(),
                collected.add(drawn).subtract(spent),
                development.getPercentComplete(), development.getPercentBasis(),
                development.getConstructionStatus(),
                development.getStartedOn(), target, forecast,
                target == null ? null : target.toEpochDay() - today.toEpochDay(),
                target == null || forecast == null ? null : forecast.toEpochDay() - target.toEpochDay(),
                ((Number) row.getOrDefault("phases", 0)).intValue(),
                ((Number) row.getOrDefault("phases_late", 0)).intValue(),
                phaseRows);
    }

    private PhaseMoneyRow phaseRow(DevelopmentPhase p, LocalDate today) {
        BigDecimal spent = zero(p.getSpentAmount());
        LocalDate expected = p.getRevisedCompletionOn() != null ? p.getRevisedCompletionOn()
                : p.getPlannedCompletionOn();
        boolean late = p.getActualCompletionOn() == null && expected != null && expected.isBefore(today);
        return new PhaseMoneyRow(HashIdUtil.encodeId(p.getId()), p.getSequenceNo(), p.getName(),
                p.getPercentComplete(), p.getBudgetAmount(), p.getPlannedSpend(),
                zero(p.getCommittedAmount()), spent,
                p.getBudgetAmount() == null ? null : p.getBudgetAmount().subtract(spent),
                p.getPlannedCompletionOn(), p.getRevisedCompletionOn(), p.getActualCompletionOn(),
                p.slippageDays(), late);
    }

    // ── the ledger ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PagedResponse<ExpenditureResponse> expenditures(String developmentHashId,
                                                           ExpenditureListRequest request) {
        Development development = requireVisible(developmentHashId);
        Specification<DevelopmentExpenditure> spec = SearchSpecs.allOf(
                SearchSpecs.notArchived(),
                SearchSpecs.eq("developmentId", development.getId()),
                SearchSpecs.fuzzy("searchText", request.getSearch()),
                SearchSpecs.statusIn(request.effectiveStatuses()),
                SearchSpecs.eq("kind", upperOrNull(request.getKind())),
                SearchSpecs.eq("categoryId", HashIdUtil.decodeId(request.getCategoryId())),
                SearchSpecs.eq("phaseId", HashIdUtil.decodeId(request.getPhaseId())),
                SearchSpecs.between("incurredOn", request.getFrom(), request.getTo()));
        var page = expenditures.findAll(spec, request.toPageable(
                Sort.by(Sort.Direction.DESC, "incurredOn").and(Sort.by(Sort.Direction.DESC, "id"))));
        Lookups lookups = lookups(page.getContent(), List.of());
        return PagedResponse.from(page, e -> toResponse(e, lookups));
    }

    @Transactional
    public ExpenditureResponse recordExpenditure(String developmentHashId, RecordExpenditureRequest request) {
        Development development = requireVisible(developmentHashId);
        visibility.assertMayManageSpending(development, AuthContext.require());
        DevelopmentCostCategory category = categories.findById(HashIdUtil.decodeId(request.categoryId()))
                .filter(DevelopmentCostCategory::isLive)
                .orElseThrow(() -> new HodiException("Choose a cost category that is available.",
                        HttpStatus.BAD_REQUEST));
        String kind = kind(request.kind());
        Long phaseId = phaseOf(development, request.phaseId());
        LocalDate incurredOn = request.incurredOn() == null ? LocalDate.now() : request.incurredOn();
        if (incurredOn.isAfter(LocalDate.now())) {
            throw new HodiException("A cost cannot be incurred in the future.", HttpStatus.BAD_REQUEST);
        }
        /*
         * A registered beneficiary, or a one-off payee by name. The beneficiary's name becomes the payee, so
         * a statement reads the same whichever way the line was made; the id is what groups by payee.
         */
        com.hodi.modules.beneficiaries.Beneficiary beneficiary = null;
        if (request.beneficiaryId() != null && !request.beneficiaryId().isBlank()) {
            beneficiary = beneficiaries.findById(HashIdUtil.decodeId(request.beneficiaryId()))
                    .filter(b -> b.getStatus() != AppConstant.STATUS_DELETED
                            && b.visibleTo(development.getTenantId(), development.getInstitutionId()))
                    .orElseThrow(() -> new HodiException("Choose one of this organisation's beneficiaries.",
                            HttpStatus.BAD_REQUEST));
        }

        DevelopmentExpenditure saved = expenditures.save(DevelopmentExpenditure.builder()
                .reference(nextReference("EX", expenditures::existsByReference))
                .developmentId(development.getId())
                .phaseId(phaseId)
                .categoryId(category.getId())
                .kind(kind)
                .amount(request.amount())
                .currency(development.getCurrency() == null ? "KES" : development.getCurrency())
                .incurredOn(incurredOn)
                .payee(beneficiary != null ? beneficiary.getName() : blankToNull(request.payee()))
                .beneficiaryId(beneficiary == null ? null : beneficiary.getId())
                .entryKind(PaidCostRecorder.ENTRY_MANUAL)
                .referenceNo(blankToNull(request.referenceNo()))
                .notes(blankToNull(request.notes()))
                .tenantId(development.getTenantId())
                .institutionId(development.getInstitutionId())
                .createdBy(AuthContext.username())
                .updatedBy(AuthContext.username())
                .build());

        inventory.recountPhaseMoney(development.getId());
        audit.record(AppConstant.AUDIT_COST_RECORDED, "DevelopmentExpenditure", saved.getId(), null,
                snapshot(saved, category));
        log.info("{} recorded {} {} on {} ({})", AuthContext.username(), kind, saved.getAmount(),
                development.getReference(), category.getCode());
        return toResponse(saved, lookups(List.of(saved), List.of()));
    }

    @Transactional
    public ExpenditureResponse voidExpenditure(String developmentHashId, String expenditureHashId,
                                               VoidRequest request) {
        Development development = requireVisible(developmentHashId);
        visibility.assertMayManageSpending(development, AuthContext.require());
        DevelopmentExpenditure line = requireExpenditure(development, expenditureHashId);
        if (line.isVoided()) {
            throw new HodiException("Line " + line.getReference() + " is already voided.", HttpStatus.CONFLICT);
        }
        String before = snapshot(line, null);
        line.voidWith(AuthContext.username(), request.reason().trim());
        DevelopmentExpenditure saved = expenditures.save(line);
        inventory.recountPhaseMoney(development.getId());
        audit.record(AppConstant.AUDIT_COST_VOIDED, "DevelopmentExpenditure", saved.getId(), before,
                snapshot(saved, null));
        return toResponse(saved, lookups(List.of(saved), List.of()));
    }

    /**
     * Attaches the evidence — an invoice, a certificate — to a cost line.
     *
     * <p>Into the vault, with a grant to the owning organisation and to whoever uploaded it. Reading it back
     * goes through {@link #evidence}, which checks the development first: the vault's own ACL cannot say
     * "anyone who may see this project", so this service says it.
     */
    @Transactional
    public ExpenditureResponse attachEvidence(String developmentHashId, String expenditureHashId,
                                              MultipartFile file) {
        Development development = requireVisible(developmentHashId);
        visibility.assertMayManageSpending(development, AuthContext.require());
        DevelopmentExpenditure line = requireExpenditure(development, expenditureHashId);
        VaultDocument stored = store(file, development, "COST_EVIDENCE",
                "Evidence for " + line.getReference());
        line.setDocumentId(stored.getId());
        line.setUpdatedBy(AuthContext.username());
        DevelopmentExpenditure saved = expenditures.save(line);
        return toResponse(saved, lookups(List.of(saved), List.of()));
    }

    // ── the facility ──────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<DrawdownResponse> drawdowns(String developmentHashId) {
        Development development = requireVisible(developmentHashId);
        List<FacilityDrawdown> rows = drawdowns.findForDevelopment(development.getId());
        Lookups lookups = lookups(List.of(), rows);
        return rows.stream().map(d -> toResponse(d, lookups)).toList();
    }

    @Transactional
    public DrawdownResponse recordDrawdown(String developmentHashId, RecordDrawdownRequest request) {
        Development development = requireVisible(developmentHashId);
        if (development.getFacilityAmount() == null) {
            throw new HodiException("This development has no facility on record. Set the facility amount "
                    + "under Set-up before drawing against it.", HttpStatus.CONFLICT);
        }
        LocalDate drawnOn = request.drawnOn() == null ? LocalDate.now() : request.drawnOn();
        if (drawnOn.isAfter(LocalDate.now())) {
            throw new HodiException("A drawdown cannot happen in the future.", HttpStatus.BAD_REQUEST);
        }
        FacilityDrawdown saved = drawdowns.save(FacilityDrawdown.builder()
                .reference(nextReference("DD", drawdowns::existsByReference))
                .developmentId(development.getId())
                .amount(request.amount())
                .currency(development.getCurrency() == null ? "KES" : development.getCurrency())
                .drawnOn(drawnOn)
                .referenceNo(blankToNull(request.referenceNo()))
                .notes(blankToNull(request.notes()))
                .tenantId(development.getTenantId())
                .institutionId(development.getInstitutionId())
                .createdBy(AuthContext.username())
                .updatedBy(AuthContext.username())
                .build());
        audit.record(AppConstant.AUDIT_DRAWDOWN_RECORDED, "FacilityDrawdown", saved.getId(), null,
                snapshot(saved));
        return toResponse(saved, lookups(List.of(), List.of(saved)));
    }

    @Transactional
    public DrawdownResponse voidDrawdown(String developmentHashId, String drawdownHashId, VoidRequest request) {
        Development development = requireVisible(developmentHashId);
        FacilityDrawdown row = requireDrawdown(development, drawdownHashId);
        if (row.isVoided()) {
            throw new HodiException("Drawdown " + row.getReference() + " is already voided.", HttpStatus.CONFLICT);
        }
        String before = snapshot(row);
        row.voidWith(AuthContext.username(), request.reason().trim());
        FacilityDrawdown saved = drawdowns.save(row);
        audit.record(AppConstant.AUDIT_DRAWDOWN_VOIDED, "FacilityDrawdown", saved.getId(), before, snapshot(saved));
        return toResponse(saved, lookups(List.of(), List.of(saved)));
    }

    @Transactional
    public DrawdownResponse attachDrawdownEvidence(String developmentHashId, String drawdownHashId,
                                                   MultipartFile file) {
        Development development = requireVisible(developmentHashId);
        FacilityDrawdown row = requireDrawdown(development, drawdownHashId);
        VaultDocument stored = store(file, development, "DRAWDOWN_EVIDENCE",
                "Evidence for " + row.getReference());
        row.setDocumentId(stored.getId());
        row.setUpdatedBy(AuthContext.username());
        FacilityDrawdown saved = drawdowns.save(row);
        return toResponse(saved, lookups(List.of(), List.of(saved)));
    }

    // ── evidence ──────────────────────────────────────────────────────────────

    /**
     * The bytes of a piece of evidence, for somebody who may see the development's money.
     *
     * <p>The development is checked first and the document must belong to one of its lines; only then is the
     * vault read. The vault's own ACL grants the owning organisation and the uploader; this is the route in
     * for everybody else the project has let in — a bank officer on a developer's project — and it is
     * recorded in the audit trail like every vault read.
     */
    @Transactional
    public DocumentService.Fetched evidence(String developmentHashId, String reference) {
        Development development = requireVisible(developmentHashId);
        VaultDocument document = vaultDocuments.findByReference(reference == null ? "" : reference.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Document", reference));
        boolean belongs = expenditures.findAll(SearchSpecs.allOf(
                        SearchSpecs.eq("developmentId", development.getId()),
                        SearchSpecs.eq("documentId", document.getId()))).stream().findAny().isPresent()
                || drawdowns.findForDevelopment(development.getId()).stream()
                        .anyMatch(d -> document.getId().equals(d.getDocumentId()));
        if (!belongs) throw new ResourceNotFoundException("Document", reference);
        return documents.readTrusted(document, "development " + development.getReference() + " finance");
    }

    private VaultDocument store(MultipartFile file, Development development, String code, String name) {
        if (file == null || file.isEmpty()) {
            throw new HodiException("Choose a file to attach.", HttpStatus.BAD_REQUEST);
        }
        UserPrincipal caller = AuthContext.require();
        // Granted to the owning organisation where there is a tenant, and to the uploader. Everybody else the
        // project has let in reads it through evidence(), which checks the development.
        return documents.store(file, EVIDENCE_FOLDER, code, name, development.getTenantId(),
                caller.getUserId(), null, null, null);
    }

    // ── rules and lookups ─────────────────────────────────────────────────────

    private Development requireVisible(String hashId) {
        Development development = developments.findById(HashIdUtil.decodeId(hashId))
                .orElseThrow(() -> new ResourceNotFoundException("Development", hashId));
        if (!visibility.mayRead(development, AuthContext.require())) {
            throw new ResourceNotFoundException("Development", hashId);
        }
        return development;
    }

    private DevelopmentExpenditure requireExpenditure(Development development, String hashId) {
        DevelopmentExpenditure line = expenditures.findById(HashIdUtil.decodeId(hashId))
                .filter(e -> e.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Cost line", hashId));
        if (!development.getId().equals(line.getDevelopmentId())) {
            throw new ResourceNotFoundException("Cost line", hashId);
        }
        return line;
    }

    private FacilityDrawdown requireDrawdown(Development development, String hashId) {
        FacilityDrawdown row = drawdowns.findById(HashIdUtil.decodeId(hashId))
                .filter(d -> d.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new ResourceNotFoundException("Drawdown", hashId));
        if (!development.getId().equals(row.getDevelopmentId())) {
            throw new ResourceNotFoundException("Drawdown", hashId);
        }
        return row;
    }

    /** A phase named on a cost has to be one of this development's. */
    private Long phaseOf(Development development, String phaseHash) {
        Long phaseId = HashIdUtil.decodeId(phaseHash);
        if (phaseId == null) return null;
        DevelopmentPhase phase = phases.findById(phaseId)
                .filter(p -> p.getStatus() != AppConstant.STATUS_DELETED)
                .orElseThrow(() -> new HodiException("That phase does not exist.", HttpStatus.BAD_REQUEST));
        if (!development.getId().equals(phase.getDevelopmentId())) {
            throw new HodiException("That phase belongs to another development.", HttpStatus.BAD_REQUEST);
        }
        return phase.getId();
    }

    private static String kind(String requested) {
        String value = requested == null ? "" : requested.trim().toUpperCase(Locale.ROOT);
        if (!AppConstant.COST_COMMITTED.equals(value) && !AppConstant.COST_SPENT.equals(value)) {
            throw new HodiException("A cost is either committed or spent.", HttpStatus.BAD_REQUEST);
        }
        return value;
    }

    private static String nextReference(String prefix, Function<String, Boolean> exists) {
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = RrnGenerator.generate(prefix);
            if (!exists.apply(candidate)) return candidate;
        }
        throw new HodiException("Could not allocate a reference. Try again.", HttpStatus.CONFLICT);
    }

    private record Lookups(Map<Long, DevelopmentCostCategory> categories, Map<Long, DevelopmentPhase> phases,
                           Map<Long, VaultDocument> documents, Map<Long, String> paymentReferences) {}

    private Lookups lookups(List<DevelopmentExpenditure> lines, List<FacilityDrawdown> rows) {
        Set<Long> categoryIds = lines.stream().map(DevelopmentExpenditure::getCategoryId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> phaseIds = lines.stream().map(DevelopmentExpenditure::getPhaseId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> documentIds = new HashSet<>();
        lines.stream().map(DevelopmentExpenditure::getDocumentId).filter(Objects::nonNull).forEach(documentIds::add);
        rows.stream().map(FacilityDrawdown::getDocumentId).filter(Objects::nonNull).forEach(documentIds::add);
        return new Lookups(
                categories.findAllById(categoryIds).stream()
                        .collect(Collectors.toMap(DevelopmentCostCategory::getId, Function.identity())),
                phases.findAllById(phaseIds).stream()
                        .collect(Collectors.toMap(DevelopmentPhase::getId, Function.identity())),
                vaultDocuments.findAllById(documentIds).stream()
                        .collect(Collectors.toMap(VaultDocument::getId, Function.identity())),
                disbursements.findAllById(lines.stream().map(DevelopmentExpenditure::getDisbursementId)
                                .filter(Objects::nonNull).collect(Collectors.toSet())).stream()
                        .collect(Collectors.toMap(com.hodi.modules.disbursements.Disbursement::getId,
                                com.hodi.modules.disbursements.Disbursement::getReference)));
    }

    private ExpenditureResponse toResponse(DevelopmentExpenditure e, Lookups lookups) {
        DevelopmentCostCategory category = lookups.categories().get(e.getCategoryId());
        DevelopmentPhase phase = e.getPhaseId() == null ? null : lookups.phases().get(e.getPhaseId());
        VaultDocument document = e.getDocumentId() == null ? null : lookups.documents().get(e.getDocumentId());
        return new ExpenditureResponse(
                HashIdUtil.encodeId(e.getId()), e.getReference(), e.getKind(),
                AppConstant.COST_SPENT.equals(e.getKind()) ? "Spent" : "Committed",
                HashIdUtil.encodeId(e.getCategoryId()),
                category == null ? null : category.getCode(),
                category == null ? "Unknown" : category.getName(),
                HashIdUtil.encodeId(e.getPhaseId()), phase == null ? null : phase.getName(),
                e.getAmount(), e.getCurrency(), e.getIncurredOn(), e.getPayee(), e.getReferenceNo(),
                e.getNotes(),
                document == null ? null : document.getReference(),
                document == null ? null : document.getOriginalName(),
                e.getStatus(), e.isVoided() ? "Voided" : "Recorded",
                e.getVoidedAt(), e.getVoidedBy(), e.getVoidReason(), e.getCreatedAt(), e.getCreatedBy(),
                HashIdUtil.encodeId(e.getBeneficiaryId()), e.getEntryKind(),
                HashIdUtil.encodeId(e.getDisbursementId()),
                e.getDisbursementId() == null ? null : lookups.paymentReferences().get(e.getDisbursementId()));
    }

    private DrawdownResponse toResponse(FacilityDrawdown d, Lookups lookups) {
        VaultDocument document = d.getDocumentId() == null ? null : lookups.documents().get(d.getDocumentId());
        return new DrawdownResponse(
                HashIdUtil.encodeId(d.getId()), d.getReference(), d.getAmount(), d.getCurrency(),
                d.getDrawnOn(), d.getReferenceNo(), d.getNotes(),
                document == null ? null : document.getReference(),
                document == null ? null : document.getOriginalName(),
                d.getStatus(), d.isVoided() ? "Voided" : "Recorded",
                d.getVoidedAt(), d.getVoidedBy(), d.getVoidReason(), d.getCreatedAt(), d.getCreatedBy());
    }

    private static String snapshot(DevelopmentExpenditure e, DevelopmentCostCategory category) {
        return e.getReference() + " " + e.getKind() + " " + e.getCurrency() + " " + e.getAmount().toPlainString()
                + (category == null ? "" : " under " + category.getCode())
                + (e.getPayee() == null ? "" : " to " + e.getPayee())
                + " status=" + e.getStatus()
                + (e.getVoidReason() == null ? "" : " — " + e.getVoidReason());
    }

    private static String snapshot(FacilityDrawdown d) {
        return d.getReference() + " " + d.getCurrency() + " " + d.getAmount().toPlainString() + " on "
                + d.getDrawnOn() + " status=" + d.getStatus()
                + (d.getVoidReason() == null ? "" : " — " + d.getVoidReason());
    }

    private static BigDecimal money(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private static LocalDate date(Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) return null;
        if (value instanceof Date d) return d.toLocalDate();
        if (value instanceof LocalDate d) return d;
        return LocalDate.parse(value.toString());
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String upperOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
