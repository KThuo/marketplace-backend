package com.hodi.modules.users.dto;

import com.hodi.common.dto.PagedDataRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

public final class UserDtos {

    private UserDtos() {}

    public record UserResponse(
            String id,
            String firstName,
            String lastName,
            String fullName,
            String email,
            String username,
            String phone,
            String avatarUrl,
            String userTypeId,
            String userTypeCode,
            String userTypeName,
            String actorClass,
            String userGroupId,
            String userGroupName,
            String tenantId,
            String tenantName,
            String institutionId,
            String institutionName,
            /** What the list's scope column shows: the organisation name, or "Platform". */
            String organisationLabel,
            boolean totpEnabled,
            boolean smsOtpEnabled,
            boolean locked,
            OffsetDateTime lockedUntil,
            boolean mustChangePassword,
            boolean emailVerified,
            boolean phoneVerified,
            OffsetDateTime lastLogin,
            Integer status,
            String statusFlag,
            String deactivationReason,
            OffsetDateTime createdAt,
            String createdBy) {}

    /**
     * @param userTypeId the class of user. Its actor class decides which organisation this user belongs to,
     *                   and therefore which of the two organisation fields below is even looked at.
     * @param tenantId ignored unless the caller is platform staff. A seller owner's new staff go to their own
     *                 organisation and nowhere else, so accepting this from them would be accepting an
     *                 instruction we then have to refuse.
     * @param institutionId the same, for lender staff.
     */
    public record CreateUserRequest(
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

            @Size(max = 64, message = "A username is at most 64 characters")
            String username,

            @Size(max = 32, message = "That phone number is too long")
            String phone,

            @NotBlank(message = "Choose which kind of user this is")
            String userTypeId,

            String userGroupId,

            String tenantId,
            String institutionId) {}

    public record UpdateUserRequest(
            @NotBlank(message = "A first name is required")
            @Size(max = 64) String firstName,
            @NotBlank(message = "A last name is required")
            @Size(max = 64) String lastName,
            @NotBlank(message = "An email address is required")
            @Email(message = "That does not look like an email address")
            @Size(max = 128) String email,
            @Size(max = 32) String phone,
            String userGroupId) {}

    /** What a temporary password issue returns. Shown once and never retrievable again. */
    public record TemporaryPasswordResponse(String username, String temporaryPassword) {}

    /**
     * The user list's own filters, on top of the shared paging and search.
     *
     * <p>A subclass rather than more {@code @RequestParam}s so the shared shape stays identical across every
     * list endpoint — the house convention.
     */
    @Getter
    @Setter
    public static class UserListRequest extends PagedDataRequest {
        /** Narrow to one population: {@code PLATFORM}, {@code SELLER}, {@code LENDER}, {@code BUYER}. */
        private String actorClass;
        private String userTypeCode;
        /** Platform staff filtering to one organisation; hash id, decoded in the service. */
        private String tenantId;
        private String institutionId;
        private String userGroupId;
        /** Only accounts currently locked out — the list an administrator works through. */
        private Boolean locked;
    }
}
