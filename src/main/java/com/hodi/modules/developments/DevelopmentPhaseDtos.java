package com.hodi.modules.developments;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Requests and responses for a development's phases. */
public final class DevelopmentPhaseDtos {

    private DevelopmentPhaseDtos() {}

    public record SavePhaseRequest(
            @NotBlank @Size(max = 160) String name,
            String description,
            @Min(1) @Max(99) Short sequenceNo,
            LocalDate plannedStartOn,
            LocalDate plannedCompletionOn,
            /** The current forecast. Moves; the planned date does not. */
            LocalDate revisedCompletionOn,
            LocalDate actualStartOn,
            LocalDate actualCompletionOn,
            @DecimalMin("0") BigDecimal budgetAmount,
            @DecimalMin("0") BigDecimal plannedSpend,
            @Min(1) @Max(100) Short weightPct,
            @Min(0) @Max(100) Short percentComplete,
            @Size(max = 32) String milestoneCode,
            @Min(0) Integer plannedUnitCount) {}

    public record PhaseResponse(
            String id,
            String reference,
            String name,
            String description,
            short sequenceNo,
            LocalDate plannedStartOn,
            LocalDate plannedCompletionOn,
            LocalDate revisedCompletionOn,
            LocalDate actualStartOn,
            LocalDate actualCompletionOn,
            BigDecimal budgetAmount,
            BigDecimal plannedSpend,
            BigDecimal committedAmount,
            BigDecimal spentAmount,
            String currency,
            Short weightPct,
            short percentComplete,
            String milestoneCode,
            Integer plannedUnitCount,
            /** Days late against the original promise. Negative is early, null when there is no promise. */
            Long slippageDays,
            int unitCount,
            int photoCount) {}
}
