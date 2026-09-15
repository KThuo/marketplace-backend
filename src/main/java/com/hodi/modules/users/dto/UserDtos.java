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

    /**
     * One person, on one profile.
     *
     * <p>{@code id} is the <strong>profile</strong>, because a list of "users" is now a list of somebody in an
     * organisation, and a person holding two profiles legitimately appears twice. {@code userId} is the
     * account behind it: the two are different things, and the actions on a row — deactivate, reset the
     * password, sign out everywhere — all act on the account.
     */
    public record UserResponse(
            String id,
            String userId,
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
            /**
             * Created, but the bank has not let it sign in yet.
             *
             * <p>Its own flag rather than something the client infers from {@code status == 0}: the list
             * needs to say "Waiting for approval" instead of "New", and it needs to hide Deactivate and
             * Delete, both of which refuse on a waiting account.
             */
            boolean awaitingApproval,
            OffsetDateTime createdAt,
            String createdBy) {}

    /**
     * @param userGroupId <strong>the only access field.</strong> A group belongs to exactly one user type, so
     *                    asking for both was asking the same question twice and inviting the two answers to
     *                    disagree — a group for Mortgage Officers assigned to somebody typed as a Sales Agent
     *                    is a user whose permissions resolve against one axis and whose module access resolves
     *                    against another. The type is read off the group.
     * @param tenantId ignored unless the caller is platform staff. A seller owner's new staff go to their own
     *                 organisation and nowhere else, so accepting this from them would be accepting an
     *                 instruction we then have to refuse.
     * @param institutionId the same, for the bank's staff.
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

            @NotBlank(message = "Choose the user group this person belongs to")
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

    /**
     * What a temporary password issue returns. Shown once and never retrievable again.
     *
     * <p>{@code awaitingApproval} is true on a newly created account, and it changes what the screen has to
     * say: hand the password over, but the person cannot use it until the bank approves them. An
     * administrator who tells a new colleague to go and sign in, when they cannot, generates the support
     * call this flag exists to prevent.
     */
    public record TemporaryPasswordResponse(String username, String temporaryPassword,
                                            boolean awaitingApproval) {}

    /**
     * The user list's own filters, on top of the shared paging and search.
     *
     * <p>A subclass rather than more {@code @RequestParam}s so the shared shape stays identical across every
     * list endpoint — the house convention.
     */
    @Getter
    @Setter
    public static class UserListRequest extends PagedDataRequest {
        /** Narrow to one population: {@code PLATFORM}, {@code SELLER}, {@code BUYER} and the rest. */
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
