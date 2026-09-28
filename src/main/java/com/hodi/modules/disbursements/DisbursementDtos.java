package com.hodi.modules.disbursements;

import com.hodi.common.dto.PagedDataRequest;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public final class DisbursementDtos {

    private DisbursementDtos() {}

    /** "Who holds this account?" — asked before anything is proposed, and again when it is. */
    public record ValidateRequest(
            @Size(max = 8) String bankCode,
            @NotBlank(message = "Enter the account number") @Size(max = 32) String accountNo) {}

    /** What Co-op said the account is. Invalid carries the reason and nothing else. */
    public record ValidateResponse(boolean valid, String accountNo, String bankCode, String holderName,
                                   String message) {}

    /**
     * A proposed transfer. The account is validated again on the server before the row exists — the
     * name shown on the form and the name approved must be the bank's, not the browser's.
     */
    public record ProposeRequest(
            @NotBlank(message = "Say who is being paid") String payeeKind,
            /** The seller organisation, when that is who is paid. */
            String tenantId,
            /** Anybody else, by name. */
            @Size(max = 160) String payeeName,
            @Size(max = 8) String bankCode,
            @NotBlank(message = "Enter the account number") @Size(max = 32) String accountNo,
            @NotNull(message = "Enter the amount")
            @DecimalMin(value = "0.01", message = "The amount must be above zero") BigDecimal amount,
            @NotBlank(message = "Say what this pays for") @Size(max = 240) String purpose,
            @Size(max = 160) String narration) {}

    @Getter @Setter
    public static class ListRequest extends PagedDataRequest {
        private String state;
        /** Only one development's payments. */
        private String developmentId;
    }

    /**
     * A development paying one of its beneficiaries.
     *
     * <p>No account here: the beneficiary carries the one the bank confirmed, and the server asks the bank again
     * before the row exists. No payee name either — it is the beneficiary's.
     */
    public record PayFromDevelopmentRequest(
            @NotBlank(message = "Choose who is being paid") String beneficiaryId,
            @NotBlank(message = "Choose the account the money leaves") String sourceAccountId,
            @NotBlank(message = "Choose what kind of cost this is") String costCategoryId,
            /** Omit for a project-level cost. */
            String phaseId,
            @NotNull(message = "Enter the amount")
            @DecimalMin(value = "0.01", message = "The amount must be above zero") BigDecimal amount,
            @NotBlank(message = "Say what this pays for") @Size(max = 240) String purpose,
            @Size(max = 64) String invoiceReference,
            @Size(max = 160) String narration) {}

    /** What the pay-a-beneficiary form needs, for one development, in one read. */
    public record PaymentOptions(
            List<com.hodi.modules.beneficiaries.BeneficiaryDtos.PayableBeneficiary> beneficiaries,
            List<com.hodi.modules.payments.PaymentTypeDtos.OfferedAccount> debitAccounts,
            List<Option> categories,
            List<Option> phases,
            /** This caller manages spending here and may propose. */
            boolean mayPay) {}

    public record Option(String id, String name) {}

    public record DisbursementResponse(
            String id,
            String reference,
            String state,
            String stateLabel,
            boolean settled,
            String payeeKind,
            String tenantId,
            String payeeName,
            String bankCode,
            String accountNo,
            String validatedName,
            OffsetDateTime validatedAt,
            BigDecimal amount,
            String currency,
            String purpose,
            String narration,
            String sourceAccountId,
            String sourceAccountNo,
            String sourceAccountName,
            String bankReference,
            String responseCode,
            String responseDescription,
            OffsetDateTime sentAt,
            OffsetDateTime settledAt,
            Integer statusQueryAttempts,
            String processingReason,
            String madeBy,
            String checkedBy,
            OffsetDateTime checkedAt,
            String decisionReason,
            OffsetDateTime createdAt,
            /* A development's payment carries its context; null on the bank's own payouts. */
            String developmentId,
            String developmentName,
            String phaseName,
            String categoryName,
            String beneficiaryId,
            String beneficiaryType,
            String invoiceReference,
            String managedBy,
            String documentReference,
            String documentName,
            /* The sale this settles, when it is a settlement's transfer. */
            String bookingId,
            String bookingReference,
            String settlementKind) {}

    /**
     * One transfer of a sale's settlement, as the settlement service hands it to the engine: everything the
     * row needs, already confirmed with the bank. The engine writes it, sends it for approval and sends it.
     */
    public record SettlementLeg(
            String settlementKind,
            Long bookingId,
            String bookingReference,
            String payeeKind,
            Long tenantId,
            String payeeName,
            String bankCode,
            String accountNo,
            String holderName,
            BigDecimal amount,
            String currency,
            String purpose,
            String narration,
            Long developmentId,
            String developmentName,
            Long ownerTenantId,
            Long ownerInstitutionId) {}

    /** One disbursement with the bank's last answer verbatim. */
    public record DisbursementDetail(DisbursementResponse disbursement, String rawResponse) {}
}
