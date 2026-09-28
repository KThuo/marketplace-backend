package com.hodi.modules.beneficiaries;

import com.hodi.common.dto.PagedDataRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/** What the beneficiary endpoints take and return. */
public final class BeneficiaryDtos {

    private BeneficiaryDtos() {}

    /**
     * Add or edit a beneficiary.
     *
     * <p>{@code tenantId} and {@code institutionId} are read only from platform staff, who set beneficiaries up
     * on an owner's behalf; everybody else's owner is their own organisation.
     */
    public record SaveBeneficiaryRequest(
            String tenantId,
            String institutionId,
            @NotBlank(message = "Say what kind of payee this is") String typeId,
            @NotBlank(message = "Enter the beneficiary's name") @Size(max = 160) String name,
            @Size(max = 16, message = "That KRA PIN is too long") String kraPin,
            @Size(max = 120) String contactName,
            @Size(max = 32) String contactPhone,
            @Email(message = "That email address does not look right") @Size(max = 160) String contactEmail,
            @Size(max = 8) String bankCode,
            @NotBlank(message = "Enter the account number they are paid into") @Size(max = 32) String accountNo,
            String notes) {}

    /** Who holds an account — asked before anybody commits to anything. */
    public record CheckAccountRequest(@Size(max = 8) String bankCode,
                                      @NotBlank(message = "Enter the account number") @Size(max = 32) String accountNo) {}

    public record AccountCheckResponse(boolean valid, String accountNo, String bankCode, String holderName,
                                       String message) {}

    public record BeneficiaryResponse(
            String id,
            String reference,
            String ownerKind,
            String ownerName,
            String tenantId,
            String institutionId,
            String typeId,
            String typeCode,
            String typeName,
            String name,
            String kraPin,
            String contactName,
            String contactPhone,
            String contactEmail,
            String bankCode,
            String accountNo,
            String verification,
            String confirmedName,
            OffsetDateTime confirmedAt,
            String verificationNote,
            String notes,
            /** Live, verified, and so nameable on a payment. */
            boolean payable,
            Integer status,
            String statusFlag,
            /** Whether this caller may edit or deactivate it. */
            boolean mayChange,
            OffsetDateTime createdAt,
            String createdBy,
            OffsetDateTime updatedAt,
            String updatedBy) {}

    /** A short row for a picker: the payment form's "who is being paid". */
    public record PayableBeneficiary(String id, String reference, String name, String typeName,
                                     String bankCode, String accountNo, String confirmedName) {}

    @Getter @Setter
    public static class BeneficiaryListRequest extends PagedDataRequest {
        private String typeId;
        private String verification;
        /** Platform staff narrow to an owner; everybody else's owner is fixed. */
        private String tenantId;
        private String institutionId;
    }

    public record TypeResponse(String id, String code, String name, String description, int sortOrder,
                               Integer status, String statusFlag, long inUse) {}

    public record SaveTypeRequest(
            @NotBlank(message = "Enter a code") @Size(max = 32) String code,
            @NotBlank(message = "Enter a name") @Size(max = 120) String name,
            String description,
            Integer sortOrder) {}
}
