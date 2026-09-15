package com.hodi.modules.sellers;

import com.hodi.common.dto.PagedDataRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;

public final class SellerDtos {

    private SellerDtos() {}

    /**
     * What somebody sends to start an application.
     *
     * <p>The minimum that makes an account: a name, a way to reach them, and a password they chose. Every
     * other question — the organisation, the documents — is asked once they are signed in, which is the
     * whole point of issuing credentials at this step rather than at the end.
     *
     * @param coopAccountNumber present when they said they bank with Co-op. Validation is attempted at
     *                          registration and its verdict is recorded either way.
     */
    public record ApplyRequest(
            @NotBlank(message = "A first name is required")
            @Size(max = 64, message = "A first name is at most 64 characters")
            String firstName,

            @NotBlank(message = "A last name is required")
            @Size(max = 64, message = "A last name is at most 64 characters")
            String lastName,

            @NotBlank(message = "An email address is required")
            @Email(message = "That does not look like an email address")
            @Size(max = 128, message = "That email address is too long")
            String email,

            @NotBlank(message = "A phone number is required")
            @Size(max = 32, message = "That phone number is too long")
            String phone,

            @NotBlank(message = "Choose a password")
            String password,

            /** True, false, or null when they would rather not say — all three mean "ask them by hand". */
            Boolean hasCoopAccount,
            @Size(max = 34, message = "That account number is too long")
            String coopAccountNumber) {}

    /** What the applicant fills in once signed in. Everything optional: it saves as they go. */
    public record SaveApplicationRequest(
            String idNumber,
            String kraPin,
            String organisationName,
            String sellerType,
            String registrationNumber,
            String county,
            String town,
            String addressLine) {}

    public record DecisionRequest(String decision, String note) {}

    /**
     * The application as its owner and as the bank both see it.
     *
     * <p>One shape for both, because they want the same facts — the difference is what they may do next,
     * and that is decided by the endpoint rather than by hiding fields. The checks are included for both:
     * an applicant told "AML is pending integration" understands why nothing happened, and an applicant
     * shown nothing assumes something did.
     */
    public record SellerApplicationResponse(
            String id,
            String reference,
            String fullName,
            String email,
            String phone,
            String idNumber,
            String kraPin,
            String identitySource,
            String coopAccountNumber,
            boolean coopAccountVerified,
            String organisationName,
            String sellerType,
            String registrationNumber,
            String county,
            String town,
            String addressLine,
            String state,
            OffsetDateTime submittedAt,
            OffsetDateTime decidedAt,
            String decisionNote,
            String tenantId,
            List<IdentityCheckResponse> checks,
            /** What is still missing before it can be submitted. Empty means ready. */
            List<String> outstanding,
            OffsetDateTime createdAt) {}

    public record IdentityCheckResponse(
            String checkCode,
            String verdict,
            String detail,
            OffsetDateTime ranAt) {}

    /** What registering returns: the reference, and what happens next in words. */
    public record ApplyOutcome(String reference, String state, String username, String message) {}

    @Getter
    @Setter
    public static class SellerApplicationListRequest extends PagedDataRequest {
        /** {@code DRAFT}, {@code SUBMITTED}, {@code MORE_INFO}, {@code APPROVED}, {@code REJECTED}. */
        private String state;
    }
}
