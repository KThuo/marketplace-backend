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

/** Requests and responses for the unit inventory. */
public final class DevelopmentUnitDtos {

    private DevelopmentUnitDtos() {}

    public record SaveUnitRequest(
            @NotBlank String unitTypeHashId,
            String phaseHashId,
            @NotBlank @Size(max = 32) String unitLabel,
            @Size(max = 32) String block,
            @Min(-5) @Max(200) Short floorNo,
            @Size(max = 16) String doorNo,
            @DecimalMin("0") BigDecimal listPrice,
            @Size(max = 24) String constructionStatus,
            LocalDate completedOn,
            String notes) {}

    /**
     * A block of units at once.
     *
     * <p>Capped at 500 a call. Not because more would break anything, but because a mistyped count is the
     * failure this feature invites and five hundred rows is already more than anybody undoes by hand.
     */
    public record GenerateUnitsRequest(
            @NotBlank String unitTypeHashId,
            String phaseHashId,
            @Min(1) @Max(500) int count,
            @Size(max = 32) String block,
            @Min(-5) @Max(200) Short firstFloor,
            @Min(1) @Max(100) Short unitsPerFloor,
            /** Tokens: {block} {floor} {n} {nn} {i} {iii}. Blank takes a default that suits the input. */
            @Size(max = 64) String labelPattern,
            @DecimalMin("0") BigDecimal listPrice) {}

    /** What the generator would produce, before anything is written. */
    public record GeneratePreview(
            int count,
            List<String> labels,
            boolean hasDuplicates,
            List<String> clashesWithExisting) {}

    public record UnitResponse(
            String id,
            String reference,
            /** The short code a buyer quotes when paying for this unit. */
            String payReference,
            String unitLabel,
            String block,
            Short floorNo,
            String doorNo,
            String unitTypeCode,
            String unitTypeName,
            String phaseName,
            BigDecimal listPrice,
            BigDecimal effectivePrice,
            String currency,
            String saleState,
            String constructionStatus,
            LocalDate completedOn,
            LocalDate handedOverOn,
            boolean holdExpired,
            OffsetDateTime reservedUntil,
            /** Buyer identity. Never on a public response, and absent from the reporting views. */
            String buyerName,
            String buyerPhone,
            String buyerEmail,
            BigDecimal soldPrice,
            OffsetDateTime soldAt,
            String notes,
            /*
             * The booking holding this unit, when one does.
             *
             * On the row so the inventory screen knows which set of actions applies: a booked unit is managed
             * through its booking, and the unit's own hold path refuses it. Without this the screen would have
             * to offer both and let the server say no, which is a refusal for every click.
             */
            String bookingId,
            String bookingReference,
            String bookingState) {}

    /**
     * What a buyer may see of one unit.
     *
     * <h3>Sold units are shown, and their buyers are not</h3>
     *
     * <p>Showing what has gone is the point of an availability list on an off-plan scheme: "four of the six
     * third-floor two-beds are taken" is what makes somebody decide this week rather than next month. What
     * must never appear is who took them — no name, no phone, no price they paid. This record has no field for
     * any of it, which is a stronger guarantee than remembering not to populate one.
     *
     * <p>{@code saleState} is narrowed on the way out too: the six internal states collapse to three a buyer
     * can act on, because "retained by the developer" and "not for sale" are the same answer from outside.
     */
    public record PublicUnitAvailability(
            String unitLabel,
            String block,
            Short floorNo,
            /** The unit's own price where it has one, otherwise the typology's. What a buyer is quoted. */
            BigDecimal price,
            String currency,
            /** AVAILABLE, TAKEN or UNAVAILABLE. Never the internal six. */
            String state,
            /** So the list can link to each one. */
            String reference) {}

    /**
     * One specific home, as a buyer sees it.
     *
     * <p>Everything resolved: its own answers where it gave them, its kind's where it did not. A buyer reading
     * this should not have to know which is which — they are choosing a flat, not auditing our data model —
     * so the figures are final and {@code inheritedFields} is there only for a screen that wants to be
     * scrupulous about it.
     *
     * <p>No buyer, no sold price, no internal state. Same discipline as everywhere else on the public side.
     */
    public record PublicUnitDetail(
            String reference,
            String unitLabel,
            String block,
            Short floorNo,
            String doorNo,
            String aspect,
            String description,
            Short bedrooms,
            Short bathrooms,
            Short balconies,
            Short parkingSpaces,
            BigDecimal floorAreaSqm,
            BigDecimal balconyAreaSqm,
            BigDecimal price,
            String currency,
            String state,
            String constructionStatus,
            List<PublicFeature> features,
            List<String> imageUrls,
            /** Which figures came from the kind rather than from this home. */
            List<String> inheritedFields,
            /** Where it sits, so the page can offer its way back up. */
            String developmentReference,
            String developmentName,
            String unitTypeReference,
            String unitTypeName,
            /*
             * Where the home is, read from the development rather than from the unit row.
             *
             * A unit copies its project's coordinates when it is generated, so the two normally agree — but
             * a developer who moves the project's pin afterwards updates one row, not two hundred. Reading
             * the development is the answer that cannot go stale, and a flat on the fourth floor has no
             * coordinate of its own worth distinguishing from the site's anyway.
             */
            String town,
            String county,
            BigDecimal latitude,
            BigDecimal longitude) {}

    /** A feature, in words a buyer reads rather than the code we store. */
    public record PublicFeature(String code, String name, String category) {}

    public record ReserveUnitRequest(
            @NotBlank @Size(max = 160) String buyerName,
            @Size(max = 32) String buyerPhone,
            @Size(max = 128) String buyerEmail,
            /** How long the hold lasts. Defaults to the configured window. */
            @Min(1) @Max(365) Integer holdDays,
            String note) {}

    public record SellUnitRequest(
            @NotBlank @Size(max = 160) String buyerName,
            @Size(max = 32) String buyerPhone,
            @Size(max = 128) String buyerEmail,
            @DecimalMin("0") BigDecimal soldPrice,
            String note) {}

    public record BuildStatusRequest(@NotBlank @Size(max = 24) String constructionStatus,
                                     LocalDate completedOn,
                                     LocalDate handedOverOn) {}

    @Getter @Setter
    public static class UnitListRequest extends PagedDataRequest {
        private String unitTypeHashId;
        private String phaseHashId;
        private String saleState;
        private String constructionStatus;
        private String block;
    }
}
