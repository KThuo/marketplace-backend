package com.hodi.modules.developments;

import com.hodi.common.dto.PagedDataRequest;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** What goes in and out of a development's finance endpoints. */
public final class DevelopmentFinanceDtos {

    private DevelopmentFinanceDtos() {}

    // ── the summary ───────────────────────────────────────────────────────────

    /**
     * Where a development's money stands and how its programme is running, in one read.
     *
     * <p>Every figure is a sum over the ledger, the drawdowns, the phases and the booking balances, read from
     * {@code v_development_finance} — the same view the report and the comparison table read.
     *
     * @param plannedToDate what should have been spent by now: the planned spend of every phase already due
     * @param spendVariance planned to date less spent; positive is under plan
     * @param netFunding    collected plus drawn less spent — whether the project is funding itself
     * @param daysRemaining to the target date; negative once it has passed
     * @param slippageDays  how far the forecast finish sits from the target, in days
     */
    public record FinanceSummary(
            String developmentId,
            String currency,
            BigDecimal budget,
            BigDecimal phaseBudget,
            BigDecimal plannedSpend,
            BigDecimal plannedToDate,
            BigDecimal committed,
            BigDecimal spent,
            BigDecimal remaining,
            BigDecimal spendVariance,
            BigDecimal facilityAmount,
            String facilityReference,
            BigDecimal drawn,
            BigDecimal undrawn,
            BigDecimal contracted,
            BigDecimal collected,
            BigDecimal receivable,
            BigDecimal overdue,
            int liveBookings,
            BigDecimal netFunding,
            short percentComplete,
            String percentBasis,
            String constructionStatus,
            LocalDate startedOn,
            LocalDate targetOn,
            LocalDate forecastOn,
            Long daysRemaining,
            Long slippageDays,
            int phases,
            int phasesLate,
            List<PhaseMoneyRow> phaseRows) {}

    /**
     * The two statements totalled for a period, from the same views the exportable reports read.
     *
     * <p>Money out counts what actually left: payments the bank confirmed, and costs recorded by hand. What is
     * proposed, approved or with the bank is {@code inFlight}, shown beside it and never added to it.
     */
    public record StatementSummary(
            String developmentId,
            String developmentName,
            String currency,
            LocalDate from,
            LocalDate to,
            BigDecimal moneyIn,
            BigDecimal moneyOut,
            BigDecimal inFlight,
            BigDecimal net,
            int paymentsIn,
            int paymentsOut,
            List<Slice> outByCategory,
            List<Slice> outByBeneficiaryType,
            List<Slice> outByPhase,
            List<Slice> inByPlacement) {}

    /** One row of a breakdown: a label, a total and how many lines made it. */
    public record Slice(String label, BigDecimal amount, long count) {}

    /** One phase, in money and in time. */
    public record PhaseMoneyRow(
            String id,
            short sequenceNo,
            String name,
            short percentComplete,
            BigDecimal budget,
            BigDecimal plannedSpend,
            BigDecimal committed,
            BigDecimal spent,
            /** Budget less spent; null when there is no budget to measure against. */
            BigDecimal remaining,
            LocalDate plannedCompletionOn,
            LocalDate revisedCompletionOn,
            LocalDate actualCompletionOn,
            Long slippageDays,
            boolean late) {}

    // ── the ledger ────────────────────────────────────────────────────────────

    public record ExpenditureResponse(
            String id,
            String reference,
            String kind,
            String kindLabel,
            String categoryId,
            String categoryCode,
            String categoryName,
            String phaseId,
            String phaseName,
            BigDecimal amount,
            String currency,
            LocalDate incurredOn,
            String payee,
            String referenceNo,
            String notes,
            /** The evidence's vault reference, opened through the finance endpoint. */
            String documentReference,
            String documentName,
            Integer status,
            String statusLabel,
            OffsetDateTime voidedAt,
            String voidedBy,
            String voidReason,
            OffsetDateTime createdAt,
            String createdBy,
            /** Who was paid, as a beneficiary; {@code payee} carries the name either way. */
            String beneficiaryId,
            /** MANUAL, typed in; DISBURSEMENT, written by a payment that succeeded. */
            String entryKind,
            String disbursementId,
            String disbursementReference) {}

    /**
     * Record a cost.
     *
     * <p>The evidence is not here: it is attached afterwards through its own multipart endpoint, so this
     * body stays JSON and its validation stays Bean Validation.
     */
    public record RecordExpenditureRequest(
            @NotBlank(message = "Choose what kind of cost this is") String categoryId,
            /** Omit for a project-level cost. */
            String phaseId,
            @NotBlank(message = "Say whether this is committed or spent") String kind,
            @NotNull(message = "How much?") @DecimalMin(value = "0.01", message = "The amount must be above zero")
            BigDecimal amount,
            /** When the cost was incurred. Defaults to today. */
            LocalDate incurredOn,
            @Size(max = 160) String payee,
            @Size(max = 64) String referenceNo,
            String notes,
            /** A registered beneficiary; their name becomes the payee. Optional — a one-off payee is typed. */
            String beneficiaryId) {

        /** Without a beneficiary: a one-off payee by name, which is every cost line before beneficiaries existed. */
        public RecordExpenditureRequest(String categoryId, String phaseId, String kind, BigDecimal amount,
                                        LocalDate incurredOn, String payee, String referenceNo, String notes) {
            this(categoryId, phaseId, kind, amount, incurredOn, payee, referenceNo, notes, null);
        }
    }

    public record DrawdownResponse(
            String id,
            String reference,
            BigDecimal amount,
            String currency,
            LocalDate drawnOn,
            String referenceNo,
            String notes,
            String documentReference,
            String documentName,
            Integer status,
            String statusLabel,
            OffsetDateTime voidedAt,
            String voidedBy,
            String voidReason,
            OffsetDateTime createdAt,
            String createdBy) {}

    public record RecordDrawdownRequest(
            @NotNull(message = "How much was drawn?") @DecimalMin(value = "0.01", message = "The amount must be above zero")
            BigDecimal amount,
            LocalDate drawnOn,
            @Size(max = 64) String referenceNo,
            String notes) {}

    public record VoidRequest(
            @NotBlank(message = "Say why this is being voided — it stays on the record") String reason) {}

    @Getter @Setter
    public static class ExpenditureListRequest extends PagedDataRequest {
        private String kind;
        private String categoryId;
        private String phaseId;
    }

    // ── the categories ────────────────────────────────────────────────────────

    public record CategoryResponse(
            String id,
            String code,
            String name,
            String description,
            int sortOrder,
            Integer status,
            String statusFlag,
            /** Lines filed under it, so suspending one can say what it affects. */
            long inUse) {}

    public record SaveCategoryRequest(
            @NotBlank(message = "Give the category a code") @Size(max = 32) String code,
            @NotBlank(message = "Name the category") @Size(max = 120) String name,
            String description,
            Integer sortOrder) {}
}
