package com.hodi.modules.usergroups.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;

public final class UserGroupDtos {

    private UserGroupDtos() {}

    public record UserGroupResponse(
            String id,
            String name,
            String description,
            String userTypeId,
            String userTypeCode,
            String userTypeName,
            /** Which organisation owns it; both null for a global template or platform role. */
            String tenantId,
            String tenantName,
            String institutionId,
            String institutionName,
            boolean template,
            boolean system,
            /** The scope label the list shows: {@code Global template}, {@code Platform}, or the org name. */
            String ownerLabel,
            int permissionCount,
            long memberCount,
            List<String> permissions,
            Integer status,
            String statusFlag,
            OffsetDateTime createdAt,
            String createdBy) {}

    /**
     * @param userTypeId the class of user this group is for. Fixed at creation — see
     *                   {@code UserGroupService.update} for why changing it is refused rather than
     *                   supported.
     * @param permissions action codes, not ids. Codes because they are what the picker deals in, what
     *                    {@code @PreAuthorize} names, and what survives a re-seed: a permission's id is an
     *                    implementation detail of one database, and a client that had cached ids would break
     *                    silently after a restore.
     */
    public record CreateUserGroupRequest(
            @NotBlank(message = "A name is required")
            @Size(max = 128, message = "A name is at most 128 characters")
            String name,

            @Size(max = 2000) String description,

            @NotNull(message = "Choose which kind of user this group is for")
            String userTypeId,

            List<String> permissions) {}

    public record UpdateUserGroupRequest(
            @NotBlank(message = "A name is required")
            @Size(max = 128, message = "A name is at most 128 characters")
            String name,
            @Size(max = 2000) String description,
            List<String> permissions) {}

    /**
     * Cloning a global template into the caller's own organisation.
     *
     * @param name optional; the template's own name is used when absent, which is what somebody cloning
     *             "Sales Agent" almost always wants
     */
    public record CloneTemplateRequest(
            @Size(max = 128, message = "A name is at most 128 characters") String name) {}
}
