package com.hodi.modules.usertypes.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public final class UserTypeDtos {

    private UserTypeDtos() {}

    public record UserTypeResponse(
            String id,
            String code,
            String name,
            String description,
            String actorClass,
            Integer sortOrder,
            long userCount,
            long groupCount,
            Integer status,
            String statusFlag,
            OffsetDateTime createdAt,
            String createdBy) {}

    /**
     * @param code stable once seeded. Uppercase, underscore-separated, because it is matched as an exact
     *             token inside {@code app_modules.allowed_user_types} — a code with a comma or a space in it
     *             would break that CSV, and a lowercase one would never match.
     * @param actorClass fixed at creation. Changing it later would silently reclassify every user holding
     *                   the type, including which organisation column they are supposed to carry, so
     *                   {@code UserTypeService} refuses the change on update.
     */
    public record CreateUserTypeRequest(
            @NotBlank(message = "A code is required")
            @Size(max = 32, message = "A code is at most 32 characters")
            @Pattern(regexp = "^[A-Z][A-Z0-9_]*$",
                    message = "A code is uppercase letters, digits and underscores, starting with a letter")
            String code,

            @NotBlank(message = "A name is required")
            @Size(max = 64, message = "A name is at most 64 characters")
            String name,

            @Size(max = 2000) String description,

            @NotBlank(message = "Choose which kind of user this is")
            @Pattern(regexp = "^(PLATFORM|SELLER|BUYER|VALUER|AGENT|VENDOR)$",
                    message = "Must be PLATFORM, SELLER, BUYER, VALUER, AGENT or VENDOR")
            String actorClass,

            Integer sortOrder) {}

    public record UpdateUserTypeRequest(
            @NotBlank(message = "A name is required")
            @Size(max = 64, message = "A name is at most 64 characters")
            String name,
            @Size(max = 2000) String description,
            Integer sortOrder) {}
}
