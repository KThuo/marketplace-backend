package com.hodi.modules.developments;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Requests and responses for a development's typologies. */
public final class DevelopmentUnitTypeDtos {

    private DevelopmentUnitTypeDtos() {}

    public record SaveUnitTypeRequest(
            @NotBlank @Size(max = 32) String code,
            @NotBlank @Size(max = 160) String name,
            String description,
            @NotBlank @Size(max = 32) String propertyType,
            /** Zero is the truthful answer for a studio. Null means the question does not apply. */
            @Min(0) Short bedrooms,
            @Min(0) Short bathrooms,
            @Min(0) Short parkingSpaces,
            @DecimalMin("0") BigDecimal floorAreaSqm,
            @DecimalMin("0") BigDecimal balconyAreaSqm,
            @DecimalMin("0") BigDecimal listPrice,
            @DecimalMin("0") BigDecimal serviceCharge,
            @Min(0) Integer plannedUnitCount,
            Integer sortOrder,
            /**
             * What every home of this kind comes with.
             *
             * <p>The first writer this column has ever had. {@code unit_features.unit_type_id}, the XOR
             * constraint and the inheritance rule in {@link UnitSpec} were all built together and none of
             * them could be reached, because the only writer in the codebase always wrote {@code unit_id}.
             *
             * <p>What belongs here rather than on the project: the things true of this typology and not of
             * its neighbour — all-en-suite, a private garden, a roof terrace. The estate's borehole belongs
             * on the development, and the picker says which is which by offering the project's own set
             * first.
             */
            java.util.List<String> amenityCodes) {}

    public record UnitTypeResponse(
            String id,
            String reference,
            String code,
            String name,
            String description,
            String propertyType,
            Short bedrooms,
            Short bathrooms,
            Short parkingSpaces,
            BigDecimal floorAreaSqm,
            BigDecimal balconyAreaSqm,
            BigDecimal listPrice,
            BigDecimal serviceCharge,
            String currency,
            Integer plannedUnitCount,
            int unitsTotal,
            int unitsAvailable,
            int unitsReserved,
            int unitsSold,
            BigDecimal fromPrice,
            String constructionStatus,
            String floorPlanUrl,
            String primaryImageUrl,
            int sortOrder,
            /** The listing this typology is marketed through, when it has one. */
            String listingReference,
            String listingState,
            int photoCount,
            java.util.List<String> amenityCodes) {}
}
