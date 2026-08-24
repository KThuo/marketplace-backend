package com.hodi.common.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body for every {@code POST /…/deactivate/{id}} endpoint. The reason is required so the
 * audit trail captures <em>why</em> an entity was taken offline — operators can review it
 * later from the entity's detail view, and it lands in the audit diff as well.
 */
public record DeactivateRequest(
        @NotBlank(message = "Deactivation reason is required")
        @Size(max = 1000, message = "Reason must be 1000 characters or fewer")
        String reason
) {}
