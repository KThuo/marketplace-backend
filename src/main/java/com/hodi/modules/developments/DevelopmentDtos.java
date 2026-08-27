package com.hodi.modules.developments;

import com.hodi.common.dto.PagedDataRequest;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * What goes in and out of the development endpoints.
 *
 * <p>Two response records rather than one with fields blanked, the arrangement {@code PropertyDtos} already
 * uses. {@link DevelopmentResponse} carries the budget, the facility and the address; {@link
 * PublicDevelopmentResponse} does not have those fields at all. A record that cannot hold a figure cannot leak
 * it by somebody forgetting to null it, and the difference between the two is readable in one place instead of
 * spread across a mapper.
 */
public final class DevelopmentDtos {

    private DevelopmentDtos() {}

    /** The only write shape. The owning principal is never in here — it comes from the caller. */
    public record SaveDevelopmentRequest(
            @NotBlank @Size(max = 255) String name,
            String description,
            @NotBlank @Size(max = 32) String developmentType,
            @Size(max = 24) String purpose,
            @Size(max = 255) String developerName,
            /** Who markets it. Null for a project that is tracked and not offered for sale. */
            String sellingTenantHashId,
            @Size(max = 64) String county,
            @Size(max = 64) String town,
            @Size(max = 128) String estate,
            String addressLine,
            BigDecimal latitude,
            BigDecimal longitude,
            @Min(0) Integer plannedUnitCount,
            LocalDate startedOn,
            LocalDate projectedCompletionOn,
            @DecimalMin("0") BigDecimal budgetAmount,
            @Size(max = 32) String facilityReference,
            @DecimalMin("0") BigDecimal facilityAmount,
            /**
             * Only honoured when the development has no phases — the escape hatch for a one-line project.
             * With phases the figure is derived from them and this is ignored, which the response's
             * percentBasis then says.
             */
            @Min(0) @Max(100) Short percentComplete) {}

    /** What the owning organisation and the platform see. */
    public record DevelopmentResponse(
            String id,
            String reference,
            String name,
            String description,
            String developmentType,
            String purpose,
            String ownerKind,
            String ownerName,
            String developerName,
            String sellingTenantName,
            String county,
            String town,
            String estate,
            String addressLine,
            BigDecimal latitude,
            BigDecimal longitude,
            Integer plannedUnitCount,
            int unitsTotal,
            int unitsAvailable,
            int unitsReserved,
            int unitsSold,
            BigDecimal fromPrice,
            BigDecimal toPrice,
            String currency,
            String constructionStatus,
            short percentComplete,
            String percentBasis,
            LocalDate startedOn,
            LocalDate projectedCompletionOn,
            LocalDate actualCompletionOn,
            BigDecimal budgetAmount,
            String facilityReference,
            BigDecimal facilityAmount,
            String listingState,
            OffsetDateTime publishedAt,
            OffsetDateTime withdrawnAt,
            String withdrawnReason,
            String primaryImageUrl,
            int phaseCount,
            int unitTypeCount,
            int collaboratorCount,
            Integer status,
            String statusFlag,
            OffsetDateTime createdAt,
            String createdBy) {}

    /**
     * What a buyer sees. No budget, no facility, no address line, no owner id.
     *
     * <p>The address is withheld for the same reason a listing's is: an unbuilt site with a published street
     * address is an invitation, and the estate and town are enough to know where a project is.
     */
    public record PublicDevelopmentResponse(
            String reference,
            String name,
            String description,
            String developmentType,
            String developerName,
            String sellerName,
            String county,
            String town,
            String estate,
            BigDecimal latitude,
            BigDecimal longitude,
            int unitsTotal,
            int unitsAvailable,
            BigDecimal fromPrice,
            BigDecimal toPrice,
            String currency,
            String constructionStatus,
            short percentComplete,
            LocalDate projectedCompletionOn,
            String primaryImageUrl,
            List<String> imageUrls,
            List<PublicUnitTypeResponse> unitTypes) {}

    /** A typology as a buyer sees it: what it is, what it costs, how many are left. */
    public record PublicUnitTypeResponse(
            String reference,
            String code,
            String name,
            String description,
            String propertyType,
            Short bedrooms,
            Short bathrooms,
            BigDecimal floorAreaSqm,
            BigDecimal fromPrice,
            String currency,
            int unitsTotal,
            int unitsAvailable,
            String constructionStatus,
            String floorPlanUrl,
            /** The typology's own listing, when it has one, so a buyer can enquire about it. */
            String listingReference) {}

    /** A phase as a buyer sees it. Dates and progress; never a budget or a spend. */
    public record PublicPhaseResponse(
            String name,
            String description,
            short sequenceNo,
            short percentComplete,
            String milestoneCode,
            LocalDate plannedCompletionOn,
            LocalDate revisedCompletionOn,
            LocalDate actualCompletionOn) {}

    @Getter @Setter
    public static class DevelopmentListRequest extends PagedDataRequest {
        private String listingState;
        private String purpose;
        private String constructionStatus;
        private String county;
    }

    public record WithdrawRequest(@NotBlank String reason) {}
    public record SubmitRequest(String note) {}
}
